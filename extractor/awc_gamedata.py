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
AD_MIN = 3.0
SONG_MIN = 45.0                             # seconds
PART_MIN = 60.0                             # a talk station's own streams of this length are parts of a show
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
        kind = CATEGORIES.get(game.hash_at(_u32(d, 8)), kind)
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


def build(items, game, songs, found, cfg_names):
    """-> {station: {"songs": [track], "jingles": [track], "ads": [track]}} for the stations the config defines.

    items      scan items            game       result of read()
    songs      credits list          found      {stream id: (title, artist, station)} matched with it
    cfg_names  {stream id: name} from the config strings
    Songs: tracks of the music lists and tracks with a title. Jingles: short streams only this station uses (idents
    and DJ talk). Talk radio: every track list with long own streams is one show, its streams are the parts.
    Adverts (the lists the config marks so) are kept apart: "ads" is only played when the listener wants them."""
    banks = _Banks(items, game)
    adverts = advert_waves(game)
    spelling = credits.artist_names(songs)
    out = {}
    for h, lists in game.items():
        talk = TALK_STATIONS.get(h)
        station = talk[0] if talk else MUSIC_STATIONS[h]
        names = _show_names(talk[1], talk[2]) if talk else {}
        st = {"songs": [], "jingles": []}
        used, episodes = set(), []
        for lst in lists:                                   # songs / shows first, so they never become jingles
            parts, name = [], None
            for tr in lst:
                hit = banks.find(h, tr.wave)
                if not hit:
                    continue
                item, s = hit
                key = (item["base"], s["i"])
                if key in used:
                    continue
                if talk:
                    if tr.wave in banks.own and s["sec"] >= PART_MIN:
                        used.add(key)
                        parts.append((item, s))
                        name = name or names.get(s["id"] & STREAM_ID_MASK)
                elif (tr.title or tr.music) and s["sec"] >= SONG_MIN:
                    used.add(key)
                    song = _track(item, s, *_song_name(tr, station, s["id"] & STREAM_ID_MASK, songs, found, cfg_names, spelling))
                    if not any((x["title"], x["artist"]) == (song["title"], song["artist"]) and abs(x["sec"] - song["sec"]) < 1.0
                               for x in st["songs"]):             # the same recording twice (a variant of the track)
                        st["songs"].append(song)
            if parts:
                ep = _track(*parts[0])
                ep["sec"] = round(sum(s["sec"] for _i, s in parts), 2)
                ep["parts"] = [[i["base"], s["i"]] for i, s in parts]
                episodes.append([name, ep])
        if episodes:
            episodes.sort(key=lambda e: e[1]["base"])
            _name_episodes(episodes, station)
            st["songs"] = [e[1] for e in episodes]
        for lst in lists:
            for tr in lst:
                hit = banks.find(h, tr.wave)
                if hit and tr.wave in banks.own and tr.wave not in adverts and not tr.title and JINGLE_MIN <= hit[1]["sec"] <= JINGLE_MAX:
                    key = (hit[0]["base"], hit[1]["i"])
                    if key not in used:
                        used.add(key)
                        st["jingles"].append(_track(*hit))
        ads = {}
        for lst in lists:
            for tr in lst:
                hit = banks.find(h, tr.wave) if tr.kind == "advert" else None
                if hit and hit[1]["sec"] >= AD_MIN:
                    ads.setdefault((hit[0]["base"], hit[1]["i"]), _track(*hit, advert_name(hit[1]["id"])))
        if ads:
            st["ads"] = [ads[k] for k in sorted(ads)]
        if st["songs"]:
            out[station] = st
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
