"""Radio stations as the game itself defines them (the audio config at the start of audio.rpf).

The config consists of a few metadata files, stored as 64 KiB blocks (plain zlib; a block that does not shrink is stored
as it is). Nothing is decrypted. Two of the files matter here:

    game file    one object per radio station -> its track lists -> tracks -> a sound and (for songs) title and artists
    sound file   the sounds: each track sound leads to one "simple sound" = (bank hash, stream ID)

So for every station the config says exactly which streams it plays, no matter how they are named. build() turns that
into the station dictionaries of AWC Radio. Talk radio has no songs: its shows are cut into parts, which are played
one after the other as one long item ("parts").

Layout of a metadata file: u32 version, u32 data length, u32 magic, data. After the data: two blocks (u32 length +
bytes), the index (u32 count, count x (name hash, offset)), three u32 arrays (u32 count + values) and the reference
table (u32 count, count x (name hash, x)). Objects refer to each other by their position in that last table.
"""
import bisect
import collections
import json
import os
import struct
import zlib

import awc_config as gamecfg
import awc_credits as credits
from awc_names import STREAM_ID_MASK, joaat

MAGIC = 0x045A88EA
BLOCK = 0x10000
NONE = 0xFFFFFFFF
TYPE_LIST_OF_LISTS, TYPE_ARTIST, TYPE_TITLE, TYPE_TRACK, TYPE_STATION, TYPE_TRACKLIST = 2, 7, 12, 15, 16, 17
SOUND_WRAPPER, SOUND_SIMPLE = 8, 12
MUSIC_CATEGORY = 0x2BE42A81                 # category of the track lists that hold the music
# every track list has a category: joaat("radio_track_category_<kind>")
CATEGORIES = {joaat("radio_track_category_" + k): k for k in (
    "music", "advert", "ident", "dj_banter", "dj_banter_night", "dj_banter_to_ads", "dj_banter_from_ads", "dj_banter_from_news",
    "dj_banter_story", "music_bed", "music_night", "music_dj2", "music_b", "showintro", "showintro_night", "showintro_dj2", "showoutro",
    "showoutro_night", "showoutro_dj2", "ident_night", "ident_b", "talk", "talk_night", "news")}
# categories without a known name: spoken segments over music (Dirty South Classics, Vice City Rap Radio) and the list
# with the last songs of a night show. Any other unknown category is called by its number.
CATEGORIES.update({0xF8BF94E8: "segment", 0x20E2BCB0: "segment", 0x35224942: "music_night2"})
NEWS = joaat("radio_track_category_news")
SONG_CATEGORIES = ("music", "music_night", "music_night2", "music_dj2")
TALK_CATEGORIES = ("talk", "talk_night", "showintro", "showintro_night", "showoutro_night", "segment")
AD_MIN = 3.0
SONG_MIN = 45.0                             # seconds
JINGLE_MIN, JINGLE_MAX = 2.0, 25.0

# station object (hash of its internal name) -> name on the credits page (the logos are found under these names)
MUSIC_STATIONS = {joaat(k): v for k, v in (
    ("radio_circoloco", "Circoloco Records Radio"), ("radio_cocoteo_fm", "Cocoteo FM"),
    ("radio_dirty_south_classics", "Dirty South Classics"), ("radio_emotion", "Emotion 98.3"), ("radio_flash_fm", "Flash FM"),
    ("radio_honey_fm", "Honey FM"), ("radio_kaleidoscope_fm", "Kaleidoscope FM"), ("radio_la_ola", "Radio La Ola"),
    ("radio_leonida_jazz_radio", "Leonida Jazz Radio"), ("radio_stockyard_fm", "Stockyard FM"),
    ("radio_symphony_fm", "Symphony FM"), ("radio_the_chamber", "The Chamber 106.6"), ("radio_v_rock", "V-Rock"),
    ("radio_vcrr", "Vice City Rap Radio"), ("radio_vice_city_fm", "Vice City FM"))}
# (stations whose internal name is not known; the last one is the playlist with the single songs of Worldwide FM -
# "radio_wwfm" itself only plays its long mix)
MUSIC_STATIONS.update({0xF3992334: "Afrobank FM", 0xF2082835: "Back Country Radio", 0xBD3EE99F: "Gloriana Blues Radio",
                       0xD62AB46E: "Worldwide FM"})
# talk radio: station object -> (name, prefix of the stream names, {show part of the stream names: show title})
TALK_STATIONS = {
    joaat("radio_wktt"): ("WKTT Talk Radio", "wktt", {"bastion": "The Richard Bastion Show", "hitthebricks": "Hit the Bricks"}),
    joaat("radio_the_wipe"): ("The Wipe Talk Radio", "thewipe",
                              {"askdrnita": "Ask Dr. Nita", "elotrolado": "El Otro Lado", "shegotassets": "She Got Assets"}),
}

Track = collections.namedtuple("Track", "wave title artists music kind", defaults=("",))      # wave = (bank hash, stream hash)

# DJ sets of venues: the track list "core.radiostation.<name>_music" holds what the club plays.
# (track list name, station name, resident DJ)
CLUB_SETS = (("club_joh_bodega", "Jack of Hearts", "Bodega The DJ"), ("club_vyb_stevie_j", "VYB", "DJ Stevie J"),
             ("dj_mixes_the_vault", "The Vault (Club)", ""))
# venues that play a playlist of songs: (station object, station name)
VENUE_PLAYLISTS = (("radio_mudclub", "Thrillbilly Mud Club"),)
# A club sound can have three layers that play together in the game: the DJ set itself and the quiet rest of the room.
# The set is the loud layer; these are its stream IDs (found by measuring the layers).
CLUB_MAIN_LAYERS = frozenset((0x0582dfac, 0x0dbfce2a, 0x16b32680, 0x01ae6157, 0x1d7ca6cc))
# the podcasts: the track list "core.radiostation.podcast_<name>_talk" holds the episodes in their order
PODCASTS = (("drunk_kings", "Drunk Kings"), ("final_act", "Final Act"), ("fmm", "Fuck Marry Murder"),
            ("safetrap", "The Safe Trap"), ("vice_city_true_stories", "Vice City True Stories"))


def _u32(d, o):
    return struct.unpack_from("<I", d, o)[0]


def _cstr(d, o):
    return d[o:].split(b"\0", 1)[0].decode("latin-1")


# ------------------------------------------------------------------------------------------------ files
def config_files(path):
    """The metadata files of the audio config at the start of an archive (bytes each)."""
    with open(path, "rb") as f:
        data = f.read(gamecfg.MAX_SCAN)
    data = data[:gamecfg.first_adat(data)]
    files, parts, prev_end, prev_len = [], [], 0, 0
    for start, end, out in gamecfg.inflate_blocks(data):
        if parts:
            raw = (prev_end + 15) & ~15                          # blocks are aligned to 16 bytes
            if prev_len != BLOCK:
                files.append(b"".join(parts))
                parts = []
            elif start - prev_end > 16:
                if start > raw and (start - raw) % BLOCK == 0:  # blocks that were stored uncompressed
                    parts.append(data[raw:start])
                else:
                    files.append(b"".join(parts))
                    parts = []
        parts.append(out)
        prev_end, prev_len = end, len(out)
    if parts:
        files.append(b"".join(parts))
    return files


class MetaFile:
    def __init__(self, data):
        if len(data) < 12 or _u32(data, 8) != MAGIC:
            raise ValueError("not a metadata file")
        self.data = data
        length = _u32(data, 4)
        p = 8 + length
        for _ in range(2):
            p += 4 + _u32(data, p)
        count = _u32(data, p)
        p += 4
        if p + 8 * count > len(data):
            raise ValueError("index outside the file")
        entries = [struct.unpack_from("<II", data, p + 8 * k) for k in range(count)]
        offsets = sorted({o for _h, o in entries}) + [length]
        end = {a: b for a, b in zip(offsets, offsets[1:])}
        self.index = {h: (8 + o, 8 + end.get(o, o)) for h, o in entries}
        q = p + 8 * count
        for _ in range(3):
            q += 4 + 4 * _u32(data, q)
        n = _u32(data, q)
        q += 4
        if q + 8 * n > len(data):
            raise ValueError("reference table outside the file")
        self.refs = [_u32(data, q + 8 * k) for k in range(n)]

    def get(self, name_hash):
        """The bytes of an object, or None."""
        span = self.index.get(name_hash)
        return self.data[span[0]:span[1]] if span else None

    def hash_at(self, ref):
        return self.refs[ref] if ref < len(self.refs) else None

    def follow(self, ref):
        h = self.hash_at(ref)
        return None if h is None else self.get(h)


# ------------------------------------------------------------------------------------------------ stations
def _wave(sounds, name_hash):
    """(bank hash, stream hash) of a track sound, or None."""
    d = sounds.get(name_hash)
    if not d or len(d) < 5 or d[0] != SOUND_WRAPPER or d[-5] != 1:
        return None
    d = sounds.follow(_u32(d, len(d) - 4))
    if not d or d[0] != SOUND_SIMPLE:
        return None
    for count in range(8, -1, -1):                  # the object ends with: bank, stream, 2 bytes, count, count x reference
        p = len(d) - 1 - 4 * count
        if p >= 10 and d[p] == count and all(_u32(d, p + 1 + 4 * i) < len(sounds.refs) for i in range(count)):
            return _u32(d, p - 10), _u32(d, p - 6)
    return None


def _title(game, ref):
    """(title, [artists]) as the config writes them, or ("", [])."""
    d = game.follow(ref) if ref != NONE else None
    if not d or d[0] != TYPE_TITLE:
        return "", []
    artists = []
    if len(d) >= 100:
        for k in range(min(_u32(d, 96), 16)):
            if 104 + 8 * k > len(d):
                break
            a = game.follow(_u32(d, 100 + 8 * k))
            if a and a[0] == TYPE_ARTIST:
                artists.append(_cstr(a, 5))
    return _cstr(d[:96], 12), artists


def _tracks(game, sounds, ref, music, seen, out, kind=""):
    h = game.hash_at(ref)
    d = None if h is None else game.get(h)
    if not d or h in seen:
        return
    seen.add(h)
    if d[0] == TYPE_TRACK and len(d) >= 16:
        wave = _wave(sounds, game.hash_at(_u32(d, 12)))
        if wave:
            title, artists = _title(game, _u32(d, 16) if len(d) >= 20 else NONE)
            out.append(Track(wave, title, artists, music, kind))
    elif d[0] == TYPE_LIST_OF_LISTS:
        for k in range(_u32(d, 8)):
            _tracks(game, sounds, _u32(d, 12 + 4 * k), music, seen, out, kind)
    elif d[0] == TYPE_TRACKLIST:
        category = game.hash_at(_u32(d, 8))
        if category:
            kind = CATEGORIES.get(category) or "%08X" % category
        if _u32(d, 68) != NONE:
            _tracks(game, sounds, _u32(d, 68), music, seen, out, kind)
        for k in range(_u32(d, 72)):
            _tracks(game, sounds, _u32(d, 76 + 4 * k), music, seen, out, kind)


def parse(files):
    """-> {station hash: [[Track, ...] per track list]} for the stations of MUSIC_STATIONS and TALK_STATIONS."""
    metas = []
    for data in files:
        try:
            metas.append(MetaFile(data))
        except (ValueError, struct.error):
            continue
    probe = joaat("radio_flash_fm")
    game = next((m for m in metas if (m.get(probe) or b"\0")[0] == TYPE_STATION), None)
    if game is None:
        raise ValueError("no radio stations in the audio config")
    wanted = set()
    for h in list(MUSIC_STATIONS) + list(TALK_STATIONS):          # which file holds the sounds of the tracks?
        d = game.get(h)
        if d:
            for k in range(_u32(d, 60)):
                lst = game.follow(_u32(d, 64 + 12 * k))
                if lst and lst[0] == TYPE_TRACKLIST:
                    for i in range(_u32(lst, 72)):
                        tr = game.follow(_u32(lst, 76 + 4 * i))
                        if tr and tr[0] == TYPE_TRACK:
                            wanted.add(game.hash_at(_u32(tr, 12)))
        if len(wanted) > 200:
            break
    sounds = max(metas, key=lambda m: sum(1 for h in wanted if h in m.index))
    out = {}
    for h in list(MUSIC_STATIONS) + list(TALK_STATIONS):
        d = game.get(h)
        if not d or d[0] != TYPE_STATION:
            continue
        lists = []
        for k in range(_u32(d, 60)):
            ref = _u32(d, 64 + 12 * k)
            lst = game.follow(ref)
            if not lst or lst[0] != TYPE_TRACKLIST:
                continue
            found = []
            _tracks(game, sounds, ref, game.hash_at(_u32(lst, 8)) == MUSIC_CATEGORY, set(), found)
            lists.append(found)
        out[h] = lists
    return out


def read(path):
    """parse() for the archive at `path`. Raises OSError / ValueError."""
    try:
        return parse(config_files(path))
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


# ------------------------------------------------------------------------------------------------ clubs
def _layers(sounds, name_hash):
    """All (bank hash, stream hash) of a sound: one, or several layers that sound together."""
    d = sounds.get(name_hash)
    if not d or d[0] != SOUND_WRAPPER:
        return []
    for count in range(8, 0, -1):                   # the wrapper ends with: count, count x reference
        p = len(d) - 1 - 4 * count
        if p < 1 or d[p] != count or any(_u32(d, p + 1 + 4 * i) >= len(sounds.refs) for i in range(count)):
            continue
        out = []
        for i in range(count):
            s = sounds.follow(_u32(d, p + 1 + 4 * i))
            if not s or s[0] != SOUND_SIMPLE:
                break
            for n in range(8, -1, -1):
                q = len(s) - 1 - 4 * n
                if q >= 10 and s[q] == n and all(_u32(s, q + 1 + 4 * k) < len(sounds.refs) for k in range(n)):
                    out.append((_u32(s, q - 10), _u32(s, q - 6)))
                    break
        if len(out) == count:
            return out
    return []


def _club_tracks(game, sounds, name_hash, seen, out):
    d = None if name_hash is None else game.get(name_hash)
    if not d or name_hash in seen:
        return
    seen.add(name_hash)
    if d[0] == TYPE_TRACK and len(d) >= 16:
        layers = _layers(sounds, game.hash_at(_u32(d, 12)))
        if layers:
            out.append(layers)
    elif d[0] == TYPE_LIST_OF_LISTS:
        for k in range(_u32(d, 8)):
            _club_tracks(game, sounds, game.hash_at(_u32(d, 12 + 4 * k)), seen, out)
    elif d[0] == TYPE_TRACKLIST:
        if _u32(d, 68) != NONE:
            _club_tracks(game, sounds, game.hash_at(_u32(d, 68)), seen, out)
        for k in range(_u32(d, 72)):
            _club_tracks(game, sounds, game.hash_at(_u32(d, 76 + 4 * k)), seen, out)


def _game_file(files):
    """(all metadata files, the one with the radio stations or None)."""
    metas = []
    for data in files:
        try:
            metas.append(MetaFile(data))
        except (ValueError, struct.error):
            continue
    return metas, next((m for m in metas if (m.get(joaat("radio_flash_fm")) or b"\0")[0] == TYPE_STATION), None)


def parse_venues(files):
    """-> {station name: [Track, ...]} for the venues of VENUE_PLAYLISTS."""
    metas, game = _game_file(files)
    if game is None:
        return {}
    wanted = {}
    for key, name in VENUE_PLAYLISTS:
        d = game.get(joaat(key))
        if d and d[0] == TYPE_STATION:
            wanted[name] = [_u32(d, 64 + 12 * k) for k in range(_u32(d, 60))]
    probe = []
    for refs in wanted.values():
        for ref in refs:
            lst = game.follow(ref)
            if lst and lst[0] == TYPE_TRACKLIST:
                for i in range(_u32(lst, 72)):
                    tr = game.follow(_u32(lst, 76 + 4 * i))
                    if tr and tr[0] == TYPE_TRACK:
                        probe.append(game.hash_at(_u32(tr, 12)))
    if not probe:
        return {}
    sounds = max(metas, key=lambda m: sum(1 for h in probe if h in m.index))
    out = {}
    for name, refs in wanted.items():
        found, seen = [], set()
        for ref in refs:
            _tracks(game, sounds, ref, True, seen, found)
        if found:
            out[name] = found
    return out


def build_venues(items, venues, songs, found, cfg_names):
    """-> {station: {"songs": [track], "jingles": []}} from the result of parse_venues(). The songs are mostly those of
    other stations, so they get their names the same way."""
    by_id = {}
    for it in items:
        if not it.get("cues"):
            for s in it["streams"]:
                by_id.setdefault(s["id"] & STREAM_ID_MASK, (it, s))
    spelling = credits.artist_names(songs)
    out = {}
    for name, tracks in venues.items():
        st = {"songs": [], "jingles": []}
        used = set()
        for tr in tracks:
            sid = tr.wave[1] & STREAM_ID_MASK
            if sid not in by_id or sid in used:
                continue
            used.add(sid)
            item, s = by_id[sid]
            if s["sec"] >= SONG_MIN:
                st["songs"].append(_track(item, s, *_song_name(tr, name, sid, songs, found, cfg_names, spelling)))
        if st["songs"]:
            out[name] = st
    return out


def parse_clubs(files):
    """-> {station name: (resident DJ, [[layer, ...] per track of the set])} for the venues of CLUB_SETS."""
    metas, game = _game_file(files)
    if game is None:
        return {}
    lists = {name: joaat("core.radiostation.%s_music" % key) for key, name, _dj in CLUB_SETS}
    # which file holds the sounds? the one that knows the sounds of the club tracks
    probe = []
    for h in lists.values():
        seen, todo = set(), [h]
        while todo:
            x = todo.pop()
            d = game.get(x) if x is not None else None
            if not d or x in seen:
                continue
            seen.add(x)
            if d[0] == TYPE_TRACK and len(d) >= 16:
                probe.append(game.hash_at(_u32(d, 12)))
            elif d[0] == TYPE_LIST_OF_LISTS:
                todo += [game.hash_at(_u32(d, 12 + 4 * k)) for k in range(_u32(d, 8))]
            elif d[0] == TYPE_TRACKLIST:
                todo += [game.hash_at(_u32(d, 76 + 4 * k)) for k in range(_u32(d, 72))]
                if _u32(d, 68) != NONE:
                    todo.append(game.hash_at(_u32(d, 68)))
    if not probe:
        return {}
    sounds = max(metas, key=lambda m: sum(1 for h in probe if h in m.index))
    out = {}
    for _key, name, dj in CLUB_SETS:
        tracks = []
        _club_tracks(game, sounds, lists[name], set(), tracks)
        if tracks:
            out[name] = (dj, tracks)
    return out


def build_clubs(items, clubs):
    """-> {station: {"songs": [one DJ set, marked "set"], "jingles": []}} from the result of parse_clubs().

    A set in several tracks is played in the order of the list, as one long item ("parts"). A club is left out when
    one of its tracks is not among the scanned banks."""
    by_id = {}
    for it in items:
        for s in it["streams"]:
            by_id.setdefault(s["id"] & STREAM_ID_MASK, (it, s))
    out = {}
    for name, (dj, tracks) in clubs.items():
        found = []
        for layers in tracks:
            ids = [w[1] & STREAM_ID_MASK for w in layers]
            main = next((i for i in ids if i in CLUB_MAIN_LAYERS), ids[0])
            if main not in by_id:
                break
            found.append(by_id[main])
        if len(found) != len(tracks):
            continue
        entry = _track(found[0][0], found[0][1], "DJ set", dj)
        entry["set"] = True
        if len(found) > 1:
            entry["sec"] = round(sum(s["sec"] for _i, s in found), 2)
            entry["parts"] = [[i["base"], s["i"]] for i, s in found]
        out[name] = {"songs": [entry], "jingles": []}
    return out


def read_venues(path):
    """(parse_clubs(), parse_venues()) for the archive at `path`. Raises OSError / ValueError."""
    try:
        files = config_files(path)
        return parse_clubs(files), parse_venues(files)
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


def hits(game):
    """{stream id: (title, [artists], station)} of all songs that have a title in the config."""
    out = {}
    for h, name in MUSIC_STATIONS.items():
        for lst in game.get(h, ()):
            for tr in lst:
                if tr.title:
                    out.setdefault(tr.wave[1] & STREAM_ID_MASK, (tr.title, tr.artists, name))
    return out


# ------------------------------------------------------------------------------------------------ podcasts
def parse_podcasts(files):
    """-> {podcast name: [(bank hash, stream hash) per episode, in order]}."""
    metas, game = _game_file(files)
    if game is None:
        return {}

    def walk(name_hash, seen, out):                     # -> the sound of every track below an object
        d = None if name_hash is None else game.get(name_hash)
        if not d or name_hash in seen:
            return
        seen.add(name_hash)
        if d[0] == TYPE_TRACK and len(d) >= 16:
            out.append(game.hash_at(_u32(d, 12)))
        elif d[0] == TYPE_LIST_OF_LISTS:
            for k in range(_u32(d, 8)):
                walk(game.hash_at(_u32(d, 12 + 4 * k)), seen, out)
        elif d[0] == TYPE_TRACKLIST:
            if _u32(d, 68) != NONE:
                walk(game.hash_at(_u32(d, 68)), seen, out)
            for k in range(_u32(d, 72)):
                walk(game.hash_at(_u32(d, 76 + 4 * k)), seen, out)

    found = {}
    for key, name in PODCASTS:
        walk(joaat("core.radiostation.podcast_%s_talk" % key), set(), found.setdefault(name, []))
    probe = [h for hs in found.values() for h in hs]
    if not probe:
        return {}
    sounds = max(metas, key=lambda m: sum(1 for h in probe if h in m.index))
    out = {}
    for name, hs in found.items():
        waves = [w for w in (_wave(sounds, h) for h in hs) if w]
        if waves:
            out[name] = waves
    return out


def read_podcasts(path):
    """parse_podcasts() for the archive at `path`. Raises OSError / ValueError."""
    try:
        return parse_podcasts(config_files(path))
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


def build_podcasts(items, podcasts):
    """-> {name: {"songs": [episodes], "jingles": [], "podcast": True}} for the podcasts whose episodes are all among
    the scanned banks. An episode's title is its number, its artist the podcast."""
    by_id = {}
    for it in items:
        for s in it["streams"]:
            by_id.setdefault(s["id"] & STREAM_ID_MASK, (it, s))
    out = {}
    for name, waves in podcasts.items():
        found = [by_id.get(w[1] & STREAM_ID_MASK) for w in waves]
        if all(found):
            out[name] = {"songs": [_track(it, s, "Episode %d" % n, name) for n, (it, s) in enumerate(found, 1)],
                         "jingles": [], "podcast": True}
    return out


# ------------------------------------------------------------------------------------------------ AWC Radio stations
class _Banks:
    """Finds the stream of a wave among the scanned banks. A stream ID can exist in several banks (every station has
    its own "link_01"): the banks of one station lie together in the archive, so the nearest one is the right one."""

    def __init__(self, items, game):
        self.by_id = {}
        for it in items:
            if not it.get("cues"):
                for s in it["streams"]:
                    self.by_id.setdefault(s["id"] & STREAM_ID_MASK, []).append((it, s))
        users = {}
        for h, lists in game.items():
            for lst in lists:
                for tr in lst:
                    users.setdefault(tr.wave, set()).add(h)
        self.own = {w for w, s in users.items() if len(s) == 1}       # used by one station only (adverts are shared)
        self.home = {}
        for h, lists in game.items():
            bases = set()
            for lst in lists:
                for tr in lst:
                    cand = self.by_id.get(tr.wave[1] & STREAM_ID_MASK, ())
                    if len(cand) == 1 and tr.wave in self.own:
                        bases.add(cand[0][0]["base"])
            self.home[h] = sorted(bases)

    def find(self, station, wave):
        """(item, stream) or None."""
        cand = self.by_id.get(wave[1] & STREAM_ID_MASK)
        if not cand:
            return None
        home = self.home.get(station)
        if len(cand) == 1 or not home:
            return cand[0]

        def distance(c):
            i = bisect.bisect_left(home, c[0]["base"])
            return min(abs(c[0]["base"] - b) for b in home[max(0, i - 1):i + 1])
        return min(cand, key=distance)


def _track(item, s, title="", artist=""):
    return {"base": item["base"], "idx": s["i"], "sid": s["id"], "title": title, "artist": artist, "sec": s["sec"]}


def _song_name(tr, station, sid, songs, found, cfg_names, spelling):
    """(title, artist) for display: the spelling of the credits list if it can be found there."""
    if tr.title:
        hit = credits.lookup(songs, station, tr.title, tr.artists)
        if hit:
            return hit
    old = found.get(sid)
    if old and old[2] == station:
        return old[0], old[1]
    name = tr.title or cfg_names.get(sid, "")
    if not name and old:
        return old[0], old[1]
    if not name:
        return "Unknown track", ""
    hit = credits.lookup(songs, station, name, tr.artists)
    if hit:
        return hit
    who = [spelling.get(a.replace("_", "").lower()) or gamecfg.pretty(a) for a in tr.artists if a != "SPECIAL"]
    return gamecfg.pretty(name), ", ".join(who)


def _show_names(prefix, shows):
    """{stream id: (show title, episode)} for all part names the stations could use."""
    return {joaat("%s_%s_ep%d_pt%d" % (prefix, key, e, p)) & STREAM_ID_MASK: (title, e)
            for key, title in shows.items() for e in range(1, 13) for p in range(1, 16)}


def _name_episodes(episodes, station):
    """episodes: [[(show, episode) or None, track]] in archive order. An unnamed one gets its place before the next
    named one (the archive stores the shows in order): the one before "El Otro Lado, episode 3" is its episode 2."""
    expect = None
    for ep in reversed(episodes):
        if ep[0] is None and expect and expect[1] > 1:
            ep[0] = (expect[0], expect[1] - 1)
        expect = ep[0]
    for n, (name, tr) in enumerate(episodes, 1):
        tr["title"], tr["artist"] = (name[0], "Episode %d" % name[1]) if name else (station, "Show %d" % n)


def advert_waves(game):
    """The adverts of all stations, as the config marks them: {(bank hash, stream hash)}."""
    return {tr.wave for lists in game.values() for lst in lists for tr in lst if tr.kind == "advert"}


def advert_keys(items, game):
    """The (bank position, stream index) of every advert."""
    banks = _Banks(items, game)
    out = set()
    for h, lists in game.items():
        for lst in lists:
            for tr in lst:
                if tr.kind == "advert":
                    hit = banks.find(h, tr.wave)
                    if hit:
                        out.add((hit[0]["base"], hit[1]["i"]))
    return out


def parse_news(files):
    """-> [(bank hash, stream hash)] of the news bulletins. They lie in lists of their own that belong to no station."""
    metas, game = _game_file(files)
    if game is None:
        return []
    lists = [h for h in game.index if len(game.get(h)) >= 76 and game.get(h)[0] == TYPE_TRACKLIST
             and game.hash_at(_u32(game.get(h), 8)) == NEWS]
    heard, seen = [], set()

    def walk(name_hash):
        d = None if name_hash is None else game.get(name_hash)
        if not d or name_hash in seen:
            return
        seen.add(name_hash)
        if d[0] == TYPE_TRACK and len(d) >= 16:
            heard.append(game.hash_at(_u32(d, 12)))
        elif d[0] == TYPE_LIST_OF_LISTS:
            for k in range(_u32(d, 8)):
                walk(game.hash_at(_u32(d, 12 + 4 * k)))
        elif d[0] == TYPE_TRACKLIST:
            if _u32(d, 68) != NONE:
                walk(game.hash_at(_u32(d, 68)))
            for k in range(_u32(d, 72)):
                walk(game.hash_at(_u32(d, 76 + 4 * k)))
    for h in sorted(lists):
        walk(h)
    if not heard:
        return []
    sounds = max(metas, key=lambda m: sum(1 for h in heard if h in m.index))
    waves = [w for w in (_wave(sounds, h) for h in heard) if w]
    return list(dict.fromkeys(waves))


def read_news(path):
    """parse_news() for the archive at `path`. Raises OSError / ValueError."""
    try:
        return parse_news(config_files(path))
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


def read_places(path):
    """parse_places() for the archive at `path`. Raises OSError / ValueError."""
    try:
        return parse_places(config_files(path))
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


def build_news(items, waves):
    """The bulletins among the scanned banks, as tracks."""
    by_id = {}
    for it in items:
        if not it.get("cues"):
            for s in it["streams"]:
                by_id.setdefault(s["id"] & STREAM_ID_MASK, (it, s))
    out = {}
    for wave in waves:
        hit = by_id.get(wave[1] & STREAM_ID_MASK)
        if hit and hit[1]["sec"] >= AD_MIN:
            out.setdefault((hit[0]["base"], hit[1]["i"]), _track(*hit))
    return [out[k] for k in sorted(out)]


# ------------------------------------------------------------------------------------------------ programme rules
# A station names (at offset 24) a "rules root": the rules for the beginning and, per category, those for what may
# follow an item of it. A rule is a list of (next category, factors); the weight of a category is the product of its
# factors. A factor is a number, a switch on a game variable, or a curve over the seconds since a category was last
# heard, the number of its items in a row, or the hour of the day. The curves lie in a file of their own.
T_ROOT, T_RULES = 30, 31
F_BRANCH, F_CONST, F_COUNT, F_SINCE, F_CONST2, F_HOUR, F_CHOICE = 5, 33, 35, 36, 38, 39, 49
CURVE_POINTS, CURVE_TABLE = 4, 6
# The mixing rules: the station names for every category whose item ends how the following one comes in - one rule for
# any follower, or one per category of the follower. Such a rule (TYPE_MIX) holds for the item that ends: seconds its
# end is moved forward, the length of its fade-out and the shape of that fade; and for the item that follows: when it
# starts (seconds before the end), the length of its fade-in and its shape. Instead of one rule there can be a weighted
# list (TYPE_LIST_OF_LISTS' neighbour, type 3) or rows with conditions (T_RULES).
TYPE_WEIGHTED, TYPE_MIX, TYPE_MIX_BY_NEXT, F_VARIABLE = 3, 22, 23, 197
EQUAL_POWER = (joaat("equal_power_rise"), joaat("equal_power_fall"))     # every other shape counts as straight


def _objects(data):
    """{name hash: bytes} of a metadata file, read from its index alone (the curve file has no reference table)."""
    length = _u32(data, 4)
    p = 8 + length
    for _ in range(2):
        p += 4 + _u32(data, p)
    count = _u32(data, p)
    if p + 4 + 8 * count > len(data):
        raise ValueError("index outside the file")
    entries = [struct.unpack_from("<II", data, p + 4 + 8 * k) for k in range(count)]
    offsets = sorted({o for _h, o in entries}) + [length]
    end = dict(zip(offsets, offsets[1:]))
    return {h: data[8 + o:8 + end.get(o, o)] for h, o in entries}


def _curve(d):
    """A curve as the rules carry it: [[x, y], ...] (straight lines between the points) or [value, ...] (a table by the
    number of items in a row), or None."""
    if not d or len(d) < 20 or d[0] not in (CURVE_POINTS, CURVE_TABLE):
        return None
    n = _u32(d, 16)
    width = 2 if d[0] == CURVE_POINTS else 1
    if not 1 <= n <= 256 or 20 + 4 * width * n > len(d):
        return None
    values = struct.unpack_from("<%df" % (width * n), d, 20)
    return [[values[2 * k], values[2 * k + 1]] for k in range(n)] if width == 2 else list(values)


def parse_rules(files):
    """-> {station hash: {"table": {category after which, or "": [[next category, [factor, ...]], ...]},
    "history": {category: how many of its items do not come back}, "sequence": [categories played in their order],
    "mix": {category of the item that ends: {category of the one that follows, or "": [[weight, move, fade, shape,
    start, fade, shape], ...]}}}}. The numbers of a mix are milliseconds: move <= 0 ends the item early, start < 0 lets
    the follower begin before that end; a shape is 0 (straight) or 1 (equal power).

    A factor is a number or ["s" since | "c" count | "h" hour, category ("" for the hour), curve]."""
    metas, game = _game_file(files)
    if game is None:
        return {}
    wanted = set()
    for h in game.index:
        d = game.get(h)
        if len(d) == 16 and d[0] in (F_COUNT, F_SINCE):
            wanted.add(game.hash_at(_u32(d, 12)))
        elif len(d) == 12 and d[0] == F_HOUR:
            wanted.add(game.hash_at(_u32(d, 8)))
    curves = {}
    for data in files:
        try:
            if len(data) < 12 or _u32(data, 8) != MAGIC:
                continue
            found = _objects(data)
        except (ValueError, struct.error):
            continue
        if sum(h in found for h in wanted) > sum(h in curves for h in wanted):
            curves = found

    def category(ref):
        h = game.hash_at(ref)
        return CATEGORIES.get(h) or "%08X" % (h or 0)

    def factor(ref, depth=0):
        d = game.follow(ref) if ref != NONE else None
        if not d or depth > 8:
            return 1.0
        t = d[0]
        if t in (F_COUNT, F_SINCE) and len(d) >= 16:
            curve = _curve(curves.get(game.hash_at(_u32(d, 12))))
            return 1.0 if curve is None else ["c" if t == F_COUNT else "s", category(_u32(d, 8)), curve]
        if t == F_HOUR and len(d) >= 12:
            curve = _curve(curves.get(game.hash_at(_u32(d, 8))))
            return 1.0 if curve is None else ["h", "", curve]
        if t in (F_CONST, F_CONST2) and len(d) >= 12:
            return struct.unpack_from("<f", d, 8)[0]
        if t == F_BRANCH and len(d) >= 20:                      # the branch for a game that has set no variable
            return factor(_u32(d, 16), depth + 1)
        if t == F_CHOICE and len(d) >= 20:                      # depends on the story: either, half as likely each
            return 0.5 * sum(struct.unpack_from("<ff", d, 12))
        return 1.0

    def rows(ref):
        d = game.follow(ref) if ref != NONE else None
        out, o = [], 8
        while d and d[0] == T_RULES and o + 8 <= len(d):
            follower, n = category(_u32(d, o)), _u32(d, o + 4)
            if n > 16 or o + 8 + 4 * n > len(d):
                break
            out.append([follower, [factor(_u32(d, o + 8 + 4 * k)) for k in range(n)]])
            o += 8 + 4 * n
        return out

    def variants(ref, depth=0):
        """[(weight, mixing rule)] behind a reference."""
        d = game.follow(ref) if ref != NONE else None
        if not d or depth > 4:
            return []
        if d[0] == TYPE_MIX and len(d) >= 40:
            return [(1.0, d)]
        if d[0] == TYPE_WEIGHTED and len(d) >= 8:
            count, base = (_u32(d, 12), 16) if d[7] == 0xFF else (d[7], 8)
            found = []
            for k in range(min(count, (len(d) - base) // 8)):
                x, weight = struct.unpack_from("<If", d, base + 8 * k)
                found += [(weight * w, rule) for w, rule in variants(x, depth + 1)]
            return found
        if d[0] == T_RULES:                                     # rows: (what, number of conditions, conditions)
            found, o = [], 8
            while o + 8 <= len(d):
                x, n = _u32(d, o), _u32(d, o + 4)
                if n > 16 or o + 8 + 4 * n > len(d):
                    break
                weight = 1.0
                for k in range(n):
                    c = game.follow(_u32(d, o + 8 + 4 * k))
                    if c and c[0] == F_VARIABLE and len(c) >= 12:       # what counts while the game has set nothing
                        weight *= struct.unpack_from("<f", c, 8)[0]
                    else:
                        value = factor(_u32(d, o + 8 + 4 * k))
                        weight *= value if not isinstance(value, list) else 1.0
                if weight > 0:
                    found += [(weight * w, rule) for w, rule in variants(x, depth + 1)]
                o += 8 + 4 * n
            return found
        return []

    def mixes(ref):
        """[[weight, move, fade, shape, start, fade, shape]] of a reference, equal rules put together."""
        found = {}
        for weight, d in variants(ref):
            move, _x, fade_out, shape_out, start, fade_in, shape_in = struct.unpack_from("<iiiiiii", d, 12)
            values = (min(0, max(-60000, move)), max(0, min(60000, fade_out)), int(game.hash_at(shape_out) in EQUAL_POWER),
                      max(-60000, min(60000, start)), max(0, min(60000, fade_in)), int(game.hash_at(shape_in) in EQUAL_POWER))
            found[values] = found.get(values, 0.0) + weight
        return [[int(round(w * 1000))] + list(v) for v, w in found.items() if w >= 0.0005]

    def mix_table(d):
        """The mixing rules of a station object: they follow behind its track lists, one entry per category."""
        out = {}
        p = 64 + 12 * _u32(d, 60)
        if p + 4 > len(d):
            return out
        for k in range(min(_u32(d, p), (len(d) - p - 4) // 8)):
            name, ref = _u32(d, p + 4 + 8 * k), _u32(d, p + 8 + 8 * k)
            target = game.follow(ref) if name != NONE and ref != NONE else None
            if not target:
                continue
            if target[0] == TYPE_MIX_BY_NEXT and len(target) >= 12:
                by = {}
                for i in range(min(_u32(target, 8), (len(target) - 12) // 8)):
                    found = mixes(_u32(target, 16 + 8 * i))
                    if found:
                        by[category(_u32(target, 12 + 8 * i))] = found
            else:
                by = {"": mixes(ref)} if mixes(ref) else {}
            if by:
                out[category(name)] = by
        return out

    out = {}
    for h in game.index:
        d = game.get(h)
        if len(d) < 68 or d[0] != TYPE_STATION:
            continue
        root = game.follow(_u32(d, 24))
        if not root or root[0] != T_ROOT or len(root) < 16:
            continue
        table = {"": rows(_u32(root, 8))}
        for k in range(min(_u32(root, 12), (len(root) - 16) // 8)):
            table[category(_u32(root, 16 + 8 * k))] = rows(_u32(root, 20 + 8 * k))
        history, sequence = {}, []
        p = 64 + 12 * _u32(d, 60)                               # behind the lists: settings per category, then histories
        if p + 4 <= len(d):
            p += 4 + 8 * _u32(d, p)
        if p + 4 <= len(d):
            for k in range(min(_u32(d, p), (len(d) - p - 4) // 12)):
                ref, n, mode = struct.unpack_from("<III", d, p + 4 + 12 * k)
                if mode == 2:
                    history[category(ref)] = n
                elif mode == 1:
                    sequence.append(category(ref))
        out[h] = {"table": table, "history": history, "sequence": sequence, "mix": mix_table(d)}
    return out


def read_rules(path):
    """parse_rules() for the archive at `path`. Raises OSError / ValueError."""
    try:
        return parse_rules(config_files(path))
    except (struct.error, IndexError, zlib.error) as e:
        raise ValueError("audio config not understood: %s" % e) from e


def tracks_of(st):
    """Every recording a station can put on air (not the DJ's lines, see awc_dj.station_lines)."""
    return st.get("songs", []) + st.get("jingles", []) + st.get("talk", []) + st.get("ads", []) + st.get("beds", [])


def has_programme(rules):
    """True when a station's rules know more than songs: then its programme follows them."""
    return bool(rules) and any(follower not in SONG_CATEGORIES for rows in rules["table"].values() for follower, _f in rows)


def _ruled_station(h, lists, banks, adverts, station, songs, found, cfg_names, spelling, rules):
    """A station whose programme follows the game's rules: its recordings by category (rules["pools"]), and sorted into
    songs, jingles (short), talk (longer spoken items) and adverts."""
    talk_radio = h in TALK_STATIONS
    names = show_parts() if talk_radio else {}
    used = {follower for rows in rules["table"].values() for follower, _f in rows}
    st = {"songs": [], "jingles": [], "talk": [], "ads": []}
    pools, kept, flagged = {}, {}, []                      # kept: recording -> its key in the lists
    beds = {}
    for lst in lists:
        for tr in lst:
            category = tr.kind
            if category == "music_bed":
                hit = banks.find(h, tr.wave)
                if hit:
                    beds.setdefault("%s_%s" % (hit[0]["base"], hit[1]["i"]), _track(*hit))
                continue
            if category not in used or category == "news":        # what no rule ever asks for is never heard
                continue
            hit = banks.find(h, tr.wave)
            if not hit:
                continue
            item, s = hit
            song = category in SONG_CATEGORIES
            if s["sec"] < (SONG_MIN if song else AD_MIN if category == "advert" else JINGLE_MIN):
                continue
            key = "%s_%s" % (item["base"], s["i"])
            if key not in kept:
                advert = category == "advert" or (not song and tr.wave in adverts)
                if song:
                    entry = _track(item, s, *_song_name(tr, station, s["id"] & STREAM_ID_MASK, songs, found, cfg_names, spelling))
                    same = next((x for x in st["songs"] if (x["title"], x["artist"]) == (entry["title"], entry["artist"])
                                 and abs(x["sec"] - entry["sec"]) < 1.0), None)
                    if same and category == "music":              # the same recording twice (a variant of the track)
                        kept[key] = "%s_%s" % (same["base"], same["idx"])
                    else:
                        st["songs"].append(entry)
                        kept[key] = key
                else:
                    entry = _track(item, s, advert_name(s["id"]) if advert else "")
                    name = names.get(s["id"] & STREAM_ID_MASK)
                    if name and not advert:
                        entry["title"], entry["artist"] = name[0], "Episode %d" % name[1]
                    short = s["sec"] <= JINGLE_MAX and category not in TALK_CATEGORIES
                    st["ads" if advert else "jingles" if short else "talk"].append(entry)
                    if advert and category != "advert":
                        flagged.append(key)
                    kept[key] = key
            pool = pools.setdefault(category, [])
            if kept[key] not in pool:
                pool.append(kept[key])
    if talk_radio:                                         # its songs are what it says
        st["songs"], st["talk"] = st["talk"], []
    rules = dict(rules, pools=pools)
    if flagged:
        rules["ads"] = flagged
    if talk_radio:
        rules["talkradio"] = True
    st = {k: v for k, v in st.items() if v or k in ("songs", "jingles")}
    st["rules"] = rules
    if beds and not talk_radio:
        st["beds"] = list(beds.values())
    if "news" in used:
        st["news"] = True
    return st


def build(items, game, songs, found, cfg_names, rules=None):
    """-> {station: {"songs": [track], "jingles": [track], ...}} for the stations the config defines.

    items      scan items            game       result of read()
    songs      credits list          found      {stream id: (title, artist, station)} matched with it
    cfg_names  {stream id: name} from the config strings                rules: from parse_rules()
    A station with programme rules gets its recordings by category (see _ruled_station), also "talk", "ads", "rules"
    and "news": True where it plays the bulletins. The others are songs (tracks of the music lists and tracks with a
    title) with jingles: short streams only that station uses."""
    banks = _Banks(items, game)
    adverts = advert_waves(game)
    spelling = credits.artist_names(songs)
    out = {}
    for h, lists in game.items():
        talk = TALK_STATIONS.get(h)
        station = talk[0] if talk else MUSIC_STATIONS[h]
        own = (rules or {}).get(h)
        if has_programme(own):
            st = _ruled_station(h, lists, banks, adverts, station, songs, found, cfg_names, spelling, own)
        elif talk:
            continue
        else:
            st = {"songs": [], "jingles": []}
            used = set()
            for lst in lists:                               # songs first, so they never become jingles
                for tr in lst:
                    hit = banks.find(h, tr.wave)
                    if not hit or not (tr.title or tr.music) or hit[1]["sec"] < SONG_MIN:
                        continue
                    item, s = hit
                    key = (item["base"], s["i"])
                    if key in used:
                        continue
                    used.add(key)
                    song = _track(item, s, *_song_name(tr, station, s["id"] & STREAM_ID_MASK, songs, found, cfg_names, spelling))
                    if not any((x["title"], x["artist"]) == (song["title"], song["artist"]) and abs(x["sec"] - song["sec"]) < 1.0
                               for x in st["songs"]):
                        st["songs"].append(song)
            for lst in lists:
                for tr in lst:
                    hit = banks.find(h, tr.wave)
                    if hit and tr.wave in banks.own and tr.wave not in adverts and not tr.title and JINGLE_MIN <= hit[1]["sec"] <= JINGLE_MAX:
                        key = (hit[0]["base"], hit[1]["i"])
                        if key not in used:
                            used.add(key)
                            st["jingles"].append(_track(*hit))
        if st["songs"]:
            out[station] = st
    return out


# ------------------------------------------------------------------------------------------------ places with music
# station objects of places that play music of their own (named as far as the names are known)
PLACE_NAMES = {joaat("hidden_radio_" + k): v for k, v in (
    ("muzak", "Muzak"), ("muzak_backbeat", "Muzak Backbeat"), ("muzak_country", "Muzak Country"),
    ("muzak_latin_classic", "Muzak Latin Classic"), ("muzak_latin_modern", "Muzak Latin Modern"),
    ("muzak_soft_rock", "Muzak Soft Rock"), ("hotel_lobby_generic", "Hotel Lobby"), ("minigolf", "Minigolf"),
    ("cellerica", "Cellerica"), ("clb_evi_dungeon", "Club EVI Dungeon"), ("kolt", "Kolt"), ("vcrr", "VCRR"))}
PLACE_NAMES[0xC74B75F4] = "Bear Collector"


def parse_places(files):
    """-> [(object hash, name or "", [(bank hash, stream hash)])] of the station objects that are no radio station: the
    music of shops, lobbies, lifts and other places. Recordings the radio or a podcast plays are left out. Named objects
    come first."""
    metas, game = _game_file(files)
    if game is None:
        return []
    radio = set(MUSIC_STATIONS) | set(TALK_STATIONS) | {joaat(k) for k, _n in VIEW_OBJECTS} | {joaat(k) for k, _n in VENUE_PLAYLISTS}
    objects = {}
    heard = []
    for h in game.index:
        d = game.get(h)
        if len(d) < 64 or d[0] != TYPE_STATION:
            continue
        sound_hashes, seen = [], set()
        todo = [game.hash_at(_u32(d, 64 + 12 * k)) for k in range(min(_u32(d, 60), (len(d) - 64) // 12))][::-1]
        while todo:
            x = todo.pop()
            o = None if x is None else game.get(x)
            if not o or x in seen:
                continue
            seen.add(x)
            if o[0] == TYPE_TRACK and len(o) >= 16:
                sound_hashes.append(game.hash_at(_u32(o, 12)))
            elif o[0] == TYPE_LIST_OF_LISTS:
                todo += [game.hash_at(_u32(o, 12 + 4 * k)) for k in range(_u32(o, 8))][::-1]
            elif o[0] == TYPE_TRACKLIST:
                inner = [game.hash_at(_u32(o, 76 + 4 * k)) for k in range(_u32(o, 72))]
                if _u32(o, 68) != NONE:
                    inner.insert(0, game.hash_at(_u32(o, 68)))
                todo += inner[::-1]
        objects[h] = sound_hashes
        heard += sound_hashes
    if not heard:
        return []
    sounds = max(metas, key=lambda m: sum(1 for h in set(heard) if h in m.index))
    waves = {h: [w for w in (_wave(sounds, x) for x in xs) if w] for h, xs in objects.items()}
    played = {w for h in radio for w in waves.get(h, ())}
    played |= {t.wave for tracks in catalog(files).values() for t in tracks}
    played |= set(parse_news(files))
    out = []
    for h in sorted((h for h in waves if h not in radio), key=lambda h: (h not in PLACE_NAMES, h)):
        own = [w for w in dict.fromkeys(waves[h]) if w not in played]
        if own:
            out.append((h, PLACE_NAMES.get(h, ""), own))
    return out


# ------------------------------------------------------------------------------------------------ for the extractor
# more station objects and track lists than the radio plays: (object name or "core.radiostation.<key>_music", station)
VIEW_OBJECTS = (("radio_on_u", "Radio On-U"), ("radio_wwfm", "Worldwide FM"), ("radio_the_vault", "The Vault Radio"),
                ("radio_mudclub", "Thrillbilly Mud Club"))
VIEW_LISTS = (("club_joh_bodega", "Jack of Hearts"), ("club_joh_tracks", "Jack of Hearts"), ("club_vyb_stevie_j", "VYB"),
              ("dj_mixes_the_vault", "The Vault (Club)"), ("haitian_mixtape", "The Sniper Of La Perle"))


def catalog(files):
    """-> {station name: [Track, ...]} of everything the config assigns to a station or club; Track.kind is the
    category of its list ("music", "advert", "ident", "dj_banter", ... , "set" for a club, "" for unknown ones)."""
    metas, game = _game_file(files)
    if game is None:
        return {}
    stations = {h: n for h, n in MUSIC_STATIONS.items()}
    stations.update({h: v[0] for h, v in TALK_STATIONS.items()})
    stations.update({joaat(k): n for k, n in VIEW_OBJECTS})
    probe = []
    d = game.get(joaat("radio_flash_fm"))
    for k in range(_u32(d, 60)):
        lst = game.follow(_u32(d, 64 + 12 * k))
        if lst and lst[0] == TYPE_TRACKLIST:
            for i in range(_u32(lst, 72)):
                tr = game.follow(_u32(lst, 76 + 4 * i))
                if tr and tr[0] == TYPE_TRACK:
                    probe.append(game.hash_at(_u32(tr, 12)))
    sounds = max(metas, key=lambda m: sum(1 for h in probe if h in m.index))
    out = {}
    for h, name in stations.items():
        d = game.get(h)
        if not d or d[0] != TYPE_STATION:
            continue
        seen = set()
        for k in range(_u32(d, 60)):
            found = []
            _tracks(game, sounds, _u32(d, 64 + 12 * k), False, seen, found)
            out.setdefault(name, []).extend(found)
    for name, waves in parse_podcasts(files).items():
        out[name] = [Track(w, name, [str(n)], False, "podcast") for n, w in enumerate(waves, 1)]
    for key, name in VIEW_LISTS:
        tracks = []
        _club_tracks(game, sounds, joaat("core.radiostation.%s_music" % key), set(), tracks)
        for layers in tracks:
            for wave in layers:
                out.setdefault(name, []).append(Track(wave, "", [], True, "set" if key != "haitian_mixtape" else "music"))
    return out


def assign(items, stations):
    """-> [(station, kind, track, item, stream)] : the streams of the scan that the tracks of catalog() mean."""
    banks = _Banks(items, {name: [tracks] for name, tracks in stations.items()})
    mixes = {}
    for it in items:
        if it.get("cues"):
            for s in it["streams"]:
                mixes.setdefault(s["id"] & STREAM_ID_MASK, (it, s))
    out, seen = [], set()
    for name, tracks in stations.items():
        for tr in tracks:
            hit = banks.find(name, tr.wave) or mixes.get(tr.wave[1] & STREAM_ID_MASK)
            if hit and (name, hit[0]["base"], hit[1]["i"]) not in seen:
                seen.add((name, hit[0]["base"], hit[1]["i"]))
                out.append((name, tr.kind, tr, hit[0], hit[1]))
    return out


def show_parts():
    """{stream id: (show title, episode, part)} for the parts of the talk shows, as their streams are named."""
    return {joaat("%s_%s_ep%d_pt%d" % (prefix, key, e, p)) & STREAM_ID_MASK: (title, e, p)
            for _name, prefix, shows in TALK_STATIONS.values() for key, title in shows.items()
            for e in range(1, 13) for p in range(1, 16)}


def advert_name(stream_id, _cache={}):
    """The product an advert is for, if known: from advert_names.json in the tool's data folder ({"<ID hex>": name})."""
    if "names" not in _cache:
        path = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool", "advert_names.json")
        try:
            with open(path, "r", encoding="utf-8") as f:
                _cache["names"] = {int(k, 16) & STREAM_ID_MASK: str(v) for k, v in json.load(f).items()}
        except (OSError, ValueError, AttributeError):
            _cache["names"] = {}
    return _cache["names"].get(stream_id & STREAM_ID_MASK, "")
