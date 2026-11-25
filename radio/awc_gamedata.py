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
import os
import struct
import sys
import zlib

sys.path.append(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "extractor"))   # shared modules
import awc_config as gamecfg  # noqa: E402
import awc_credits as credits  # noqa: E402
from awc_names import STREAM_ID_MASK, joaat  # noqa: E402

MAGIC = 0x045A88EA
BLOCK = 0x10000
NONE = 0xFFFFFFFF
TYPE_LIST_OF_LISTS, TYPE_ARTIST, TYPE_TITLE, TYPE_TRACK, TYPE_STATION, TYPE_TRACKLIST = 2, 7, 12, 15, 16, 17
SOUND_WRAPPER, SOUND_SIMPLE = 8, 12
MUSIC_CATEGORY = 0x2BE42A81                 # category of the track lists that hold the music
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

Track = collections.namedtuple("Track", "wave title artists music")          # wave = (bank hash, stream hash)


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


def _tracks(game, sounds, ref, music, seen, out):
    h = game.hash_at(ref)
    d = None if h is None else game.get(h)
    if not d or h in seen:
        return
    seen.add(h)
    if d[0] == TYPE_TRACK and len(d) >= 16:
        wave = _wave(sounds, game.hash_at(_u32(d, 12)))
        if wave:
            title, artists = _title(game, _u32(d, 16) if len(d) >= 20 else NONE)
            out.append(Track(wave, title, artists, music))
    elif d[0] == TYPE_LIST_OF_LISTS:
        for k in range(_u32(d, 8)):
            _tracks(game, sounds, _u32(d, 12 + 4 * k), music, seen, out)
    elif d[0] == TYPE_TRACKLIST:
        if _u32(d, 68) != NONE:
            _tracks(game, sounds, _u32(d, 68), music, seen, out)
        for k in range(_u32(d, 72)):
            _tracks(game, sounds, _u32(d, 76 + 4 * k), music, seen, out)


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


def build(items, game, songs, found, cfg_names):
    """-> {station: {"songs": [track], "jingles": [track]}} for the stations the config defines.

    items      scan items            game       result of read()
    songs      credits list          found      {stream id: (title, artist, station)} matched with it
    cfg_names  {stream id: name} from the config strings
    Songs: tracks of the music lists and tracks with a title. Jingles: short streams only this station uses (idents
    and DJ talk). Talk radio: every track list with long own streams is one show, its streams are the parts."""
    banks = _Banks(items, game)
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
                if hit and tr.wave in banks.own and not tr.title and JINGLE_MIN <= hit[1]["sec"] <= JINGLE_MAX:
                    key = (hit[0]["base"], hit[1]["i"])
                    if key not in used:
                        used.add(key)
                        st["jingles"].append(_track(*hit))
        if st["songs"]:
            out[station] = st
    return out
