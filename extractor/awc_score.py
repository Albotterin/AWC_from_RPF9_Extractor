"""The score of the game: its interactive music, put together from the audio config and the sound banks.

The game does not store its score as finished songs. The music file of the config describes it:

    project (type 95)   one per mission or area -> the list of its section names
    name list (103)     count x (name, number)
    section (104)       tempo, its number (= its name), its instrument layers (stems) and what follows it
    follow action (109) after so many beats: go on with that section (or, for a clip, with that clip)
    clip (107)          one stem of a section: a sound, looping or played once
    stem (105)          the name of the instrument layer ("DRUMS", "BASS", ...)

Each sound ends in a stereo Opus stream of a "sound bank" (ADAT with flags 0xFF21/0xFF61), found by a pass over the
archive. A *piece* here is a section the game starts by an event, together with the sections that follow it by
themselves (until the music would loop or the next event is needed). render() mixes the stems of a piece into one Ogg
Opus file: loops fill their section, a stem that plays once rings out until a later section gives its layer something
new (or stops it); silence at the end is cut off. That is a reconstruction: in the game, layers also come and go with what the player does. The names
are the developers' working names. Nothing is decrypted.

Needs FFmpeg for render().
"""
import array
import collections
import functools
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile

import awc_core as core
import awc_gamedata as gamedata
from awc_names import STREAM_ID_MASK

VERSION = 3                                  # of the cached catalog
RATE = 48000
FRAME = 4                                    # bytes of a decoded stereo frame (16 bit)
BANK_FLAGS = (0xFF21, 0xFF61)
UNIT = 0x800
BITRATE = "128k"
QUIET = 64                                   # samples up to this (-54 dB) count as silence at the end of a piece
TAIL = 1.5                                   # seconds kept after the last sound
NO_WINDOW = 0x08000000 if os.name == "nt" else 0
NONE = 0xFFFFFFFF
T_PROJECT, T_NAMES, T_SECTION, T_STEM, T_CLIP, T_FOLLOW = 95, 103, 104, 105, 107, 109
S_WRAPPER = 6
GROUPS = ("openworld", "mission", "compound", "activity")
ACTIVITY = ((re.compile(r"^(?:MEDITATION|MED)_"), "Meditation", 1), (re.compile(r"^DANCINGMINIGAME_"), "Dancing Minigame", 1),
            (re.compile(r"^DRN_RACE_"), "Drone Race", 2), (re.compile(r"^DRUG_"), "Drugs", 1), (re.compile(r"^PUZZLE_"), "Puzzle", 1),
            (re.compile(r"^SECRELM_"), "Secrelm", 1))
COMPOUND = re.compile(r"^((?:[A-Z0-9]+_)?(?:[A-Z0-9]*COMPOU0?ND[A-Z0-9]*|CMPND[A-Z0-9]*|[A-Z0-9]+COM))(?:_|$)")
SMALL_WORDS = {"a", "an", "the", "at", "to", "in", "on", "of", "up", "go", "no", "is", "it", "my", "and", "for", "off", "out",
               "end", "car", "bus", "van", "run", "get", "all", "low", "hit", "new", "see", "you", "can", "day", "dog", "bar",
               "cop", "gun", "air", "sea", "ride", "raid", "boat", "talk", "map", "one", "two"}


class ScoreError(Exception):
    pass


def _u32(d, o):
    return struct.unpack_from("<I", d, o)[0]


def _f32(d, o):
    return struct.unpack_from("<f", d, o)[0]


def _cancelled(cancel):
    if cancel and cancel():
        raise InterruptedError()


# ------------------------------------------------------------------------------------------------ config
class _Config:
    """The music file and the sound file of the audio config."""

    def __init__(self, path):
        metas = []
        for data in gamedata.config_files(path):
            try:
                metas.append(gamedata.MetaFile(data))
            except (ValueError, struct.error):
                pass
        best, music = 0, None
        for m in metas:
            n = 0
            for h in m.index:
                d = m.get(h)
                if len(d) >= 20 and d[0] == T_PROJECT:
                    target = m.follow(_u32(d, 16))
                    n += bool(target) and target[0] == T_NAMES
            if n > best:
                best, music = n, m
        if music is None:
            raise ScoreError("no interactive music in the config of this file")
        self.music = music
        clips = [music.get(h) for h in music.index]
        wanted = {music.hash_at(_u32(d, 5)) for d in clips if d and d[0] == T_CLIP and len(d) >= 29}
        self.sounds = max((m for m in metas if m is not music), key=lambda m: sum(h in m.index for h in wanted))
        self._waves = {}

    def obj(self, ref, kind):
        """The music object a reference points at, if it is of that type."""
        d = self.music.follow(ref) if ref != NONE else None
        return d if d and d[0] == kind else None

    def _children(self, d):
        refs = self.sounds.refs
        if d[0] == S_WRAPPER:                                    # <child> <ffffffff> <reference> at the end
            for back in (12, 8, 16, 20):
                if len(d) >= back + 2:
                    v = _u32(d, len(d) - back)
                    if v < len(refs) and refs[v] in self.sounds.index:
                        return [refs[v]]
            return []
        for count in range(255, 0, -1):                          # <count> <count x reference> at the end
            p = len(d) - 1 - 4 * count
            if p < 1 or d[p] != count:
                continue
            found = [_u32(d, p + 1 + 4 * i) for i in range(count)]
            if all(v < len(refs) for v in found):
                kids = [refs[v] for v in found]
                if sum(k in self.sounds.index for k in kids) >= max(1, count * 3 // 4):
                    return [k for k in dict.fromkeys(kids) if k in self.sounds.index]
        return []

    def waves(self, name_hash):
        """[(bank hash, stream hash)] the sound plays."""
        got = self._waves.get(name_hash)
        if got is not None:
            return got
        out, todo, seen = [], [name_hash], set()
        while todo:
            h = todo.pop(0)
            d = self.sounds.get(h)
            if h in seen or not d:
                continue
            seen.add(h)
            if d[0] == gamedata.SOUND_SIMPLE:
                for count in range(8, -1, -1):                   # ends with: bank, stream, 2 bytes, count, references
                    p = len(d) - 1 - 4 * count
                    if p >= 10 and d[p] == count and all(_u32(d, p + 1 + 4 * i) < len(self.sounds.refs) for i in range(count)):
                        out.append((_u32(d, p - 10), _u32(d, p - 6)))
                        break
            else:
                todo.extend(self._children(d))
        self._waves[name_hash] = out
        return out


def _clip_chain(cfg, ref):
    """What a stem plays in a section: [(from beat, wave, loop, until beat or None)]. A clip can hand over to the next one."""
    m, out, at, seen = cfg.music, [], 0.0, set()
    name, d = m.hash_at(ref), cfg.obj(ref, T_CLIP)
    while d and len(d) >= 29 and name not in seen and len(out) < 8:
        seen.add(name)
        waves = cfg.waves(m.hash_at(_u32(d, 5)))
        until, following, target = None, None, None
        f = cfg.obj(_u32(d, 21), T_FOLLOW)
        if f and len(f) >= 28 and _u32(f, 12) >= 1 and 0 < _f32(f, 8) <= 100000 and _u32(f, 16) != name:
            until, target = at + _f32(f, 8), _u32(f, 16)          # then the next clip, or silence
            following = m.get(target)
        if len(waves) == 1:
            out.append((at, waves[0], bool(d[1] & 4), until))
        if not following or following[0] != T_CLIP:
            break
        at, name, d = until, target, following
    return out


def parse_sections(cfg):
    """{object hash: section} of every named section; a section is a dict with name, project, bpm, follow, clips."""
    m = cfg.music
    names = {}                                                   # project hash -> {number: name}
    for h in m.index:
        d = m.get(h)
        if len(d) >= 20 and d[0] == T_PROJECT:
            lst = cfg.obj(_u32(d, 16), T_NAMES)
            if lst:
                count = min(struct.unpack_from("<H", lst, 6)[0], (len(lst) - 8) // 68)
                names[h] = {_u32(lst, 72 + 68 * k): lst[8 + 68 * k:72 + 68 * k].split(b"\0")[0].decode("latin-1")
                            for k in range(count)}
    out = {}
    for h in m.index:
        d = m.get(h)
        if len(d) < 44 or d[0] != T_SECTION:
            continue
        bpm, number, project, count = _f32(d, 12), _u32(d, 24), m.hash_at(_u32(d, 32)), _u32(d, 36)
        name = names.get(project, {}).get(number)
        if not name or not 20.0 <= bpm <= 400.0 or 40 + 12 * count > len(d):
            continue
        clips, listed = [], []
        for k in range(count):
            stem = cfg.obj(_u32(d, 40 + 12 * k), T_STEM)
            if not stem:
                continue
            layer = stem[5:69].split(b"\0")[0].decode("latin-1")
            listed.append(layer)                                 # listed without a clip: the layer stops here
            for begin, wave, loop, until in _clip_chain(cfg, _u32(d, 48 + 12 * k)):
                clips.append({"stem": layer, "wave": wave, "loop": loop, "from": begin, "until": until})
        follow = None
        f = cfg.obj(_u32(d, 20), T_FOLLOW)
        if f and len(f) >= 28 and _u32(f, 12) >= 1 and 0 < _f32(f, 8) <= 100000:
            follow = (_f32(f, 8), _u32(f, 16))                   # beats, the section that comes next
        out[h] = {"name": name, "project": project, "bpm": bpm, "follow": follow, "clips": clips, "listed": listed}
    return out


# ------------------------------------------------------------------------------------------------ banks
def _bank(head, flags, n, end):
    """[(stream ID, {chunk tag: (offset, size)})] of a sound bank header, or None."""
    base = 16 + (4 if flags & 0x40 else 0)
    table = base + 2 * n + 4 * (n + 1) + 4 * n
    if table > end or end > len(head) or (end - table) % 8:
        return None
    starts = struct.unpack_from("<%dH" % n, head, base)
    ids = struct.unpack_from("<%dI" % n, head, base + 2 * n + 4 * (n + 1))
    total = (end - table) // 8
    out = []
    for k in range(n):
        chunks = {}
        for i in range(starts[k], min(starts[k + 1] if k + 1 < n else total, total)):
            v = struct.unpack_from("<Q", head, table + 8 * i)[0]
            chunks[v >> 56] = (v & 0xFFFFFFF, (v >> 28) & 0xFFFFFFF)
        out.append((ids[k], chunks))
    return out


def find_banks(path, wanted, progress=None, cancel=None):
    """Search the archive for sound banks holding streams of `wanted` (masked stream IDs).

    -> [(position, [(stream ID, data position, data size, frames, channels)])], only the wanted Opus streams."""
    size = os.path.getsize(path)
    out, pos, last = [], 0, -1
    with open(path, "rb") as f, open(path, "rb") as g:
        while pos < size:
            _cancelled(cancel)
            f.seek(pos)
            buf = f.read(core.SCAN_CHUNK)
            if not buf:
                break
            i = buf.find(b"ADAT")
            while i >= 0:
                at = pos + i
                if at > last:
                    last = at
                    g.seek(at)
                    head = g.read(16)
                    if len(head) == 16:
                        version, flags, n, end = struct.unpack_from("<HHII", head, 4)
                        if version == 1 and flags in BANK_FLAGS and 1 <= n <= 20000 and 32 <= end <= 4 << 20:
                            head += g.read(end - 16)
                            streams = []
                            for sid, chunks in _bank(head, flags, n, end) or ():
                                fmt, data = chunks.get(0x76), chunks.get(0x55)
                                if sid & STREAM_ID_MASK not in wanted or not fmt or not data or fmt[1] < 30 \
                                        or at + data[0] + data[1] > size:
                                    continue
                                g.seek(at + fmt[0])
                                form = g.read(30)
                                samples, rate, codec, channels = _u32(form, 0), struct.unpack_from("<H", form, 8)[0], form[28], form[29]
                                if codec == 0x0B and rate == RATE and channels in (1, 2):
                                    streams.append((sid, at + data[0], data[1], samples // channels, channels))
                            if streams:
                                out.append((at, streams))
                i = buf.find(b"ADAT", i + 1)
            pos += max(1, len(buf) - 3)
            if progress:
                progress(min(1.0, pos / float(size)))
    return out


def locate(waves, banks):
    """{(bank hash, stream hash): (data position, size, frames, channels)}: which container each bank hash means is told
    by the streams it holds (several banks can hold a stream of the same ID)."""
    holders = collections.defaultdict(list)
    for at, streams in banks:
        for sid, data, size, frames, channels in streams:
            holders[sid & STREAM_ID_MASK].append((at, (data, size, frames, channels)))
    votes = collections.defaultdict(collections.Counter)
    for bank, stream in waves:
        for at, _src in holders.get(stream & STREAM_ID_MASK, ()):
            votes[bank][at] += 1
    out = {}
    for bank, stream in waves:
        found = holders.get(stream & STREAM_ID_MASK)
        if found:
            out[(bank, stream)] = max(found, key=lambda x: (votes[bank][x[0]], -x[0]))[1]
    return out


# ------------------------------------------------------------------------------------------------ pieces
def _word(token):
    low = token.lower()
    if low in SMALL_WORDS:
        return token.capitalize()
    if len(token) <= 3 or any(c.isdigit() for c in token):
        return token.upper()
    return token.capitalize()


def pretty(tokens):
    return " ".join(_word(t) for t in tokens if t)


def classify(name, open_world):
    """(group, sub group, title) of a section name; open_world: its project is one of the open world."""
    tokens = [t for t in name.split("_") if t]
    found = None
    if open_world:
        if tokens[0] != "OWM":                                   # KILLINGSPREE..., HITSTOP: shared by the open world
            return "openworld", "Events", pretty(tokens)
        tokens = ["OM" if t == "0M" else t for t in tokens]      # one name has a zero for the O
        group, n = "openworld", 2 if len(tokens) <= 3 or tokens[1] == "AA" else 3
    else:
        activity = next((a for a in ACTIVITY if a[0].match(name)), None)
        m = COMPOUND.match(name)
        if activity:
            group, found, n = "activity", activity[1], activity[2]
        elif m:
            group, n = "compound", len([t for t in m.group(1).split("_") if t])
        else:
            group = "mission"                # short codes need two words ("ROB_BANK"), a long first word is a mission itself
            n = 1 if len(tokens[0]) >= 6 else min(2, max(1, len(tokens) - 1))
    rest = tokens[n:]
    if len(rest) > 1 and rest[-1] == "A":                        # the first of the variants A, B, C ...
        rest = rest[:-1]
    if rest == ["A"]:
        rest = []
    first = 1 if group == "openworld" and n > 1 else 0           # "OWM" says nothing inside the open world
    return group, found or pretty(tokens[first:n]), pretty(rest) or "Theme"


def _adopt(sections, open_world, found):
    """Names that stand for themselves ("ALERT", "ESCAPE2") join the largest group of their project."""
    keys = collections.defaultdict(collections.Counter)          # project -> {mission sub group: names}
    for s in sections.values():
        if s["project"] not in open_world:
            group, sub, _t = classify(s["name"].replace("FA_", "", 1), False)
            if group == "mission":
                keys[s["project"]][sub] += 1
    project = {s["name"]: s["project"] for s in sections.values()}
    for _rank, piece in found.values():
        counts = keys.get(project[piece["name"]])
        if piece["group"] == "mission" and counts and counts[piece["sub"]] < 4:
            home, n = counts.most_common(1)[0]
            if n >= 4:
                piece["sub"], piece["title"] = home, pretty(piece["name"].split("_"))


def _section_data(sec, places):
    """The section with its audio as a part of a piece, or None if none of it is there.

    A clip entry is [data position, data size, frames, channels, loop, layer, from second, until second (0: open)]."""
    beat = 60.0 / sec["bpm"]
    clips = []
    for c in sec["clips"]:
        src = places.get(c["wave"])
        if src:
            clips.append([src[0], src[1], src[2], src[3], int(c["loop"]), c["stem"], round(c["from"] * beat, 4),
                          round(c["until"] * beat, 4) if c["until"] else 0])
    if sec["follow"]:
        seconds = sec["follow"][0] * beat
        clips = [c for c in clips if c[6] < seconds]
    elif clips:
        seconds = max(min(c[6] + c[2] / float(RATE), c[7] or 1e9) for c in clips)
    if not clips:
        return None
    return {"sec": round(seconds, 4), "clips": clips, "listed": sorted(set(sec["listed"]))}


def timeline(parts):
    """([(start second, length in seconds, clip entry)], length of the piece) of the parts one after the other.

    The piece ends with its last clip: a section that only waits for the next event adds nothing."""
    starts = [0.0]
    for part in parts:
        starts.append(starts[-1] + part["sec"])
    out = []
    for i, part in enumerate(parts):
        for c in part["clips"]:
            begin = starts[i] + c[6]
            if c[4]:                                             # a loop fills its time in the section
                length = starts[i] + min(c[7] or part["sec"], part["sec"]) - begin
            else:                                                # played once: until its end, or the layer gets news
                length = c[2] / float(RATE)
                if c[7]:
                    length = min(length, c[7] - c[6])
                later = next((starts[j] for j in range(i + 1, len(parts)) if c[5] in parts[j]["listed"]), None)
                if later is not None:
                    length = min(length, later - begin)
            if length > 0.001 and c[2]:
                out.append((begin, length, c))
    return out, max([0.0] + [b + n for b, n, _c in out])


def _order(piece):
    return GROUPS.index(piece["group"]), piece["sub"].lower(), piece["title"].lower(), piece["name"]


def build_pieces(sections, places):
    """The pieces of the score, sorted by group, sub group and title."""
    mostly = collections.defaultdict(collections.Counter)
    for s in sections.values():
        mostly[s["project"]][s["name"].replace("FA_", "", 1).startswith("OWM_")] += 1
    open_world = {p for p, c in mostly.items() if c[True] > c[False] // 2}
    data = {h: _section_data(s, places) for h, s in sections.items()}
    found = {}
    for h, s in sections.items():
        if s["name"].startswith("FA_"):                          # "follow action": only ever comes after another one
            continue
        chain, cur, seen = [], h, set()
        while cur in sections and cur not in seen and data[cur]:
            if chain and not sections[cur]["name"].startswith("FA_"):
                break
            seen.add(cur)
            chain.append(cur)
            cur = sections[cur]["follow"][1] if sections[cur]["follow"] else None
        if not chain:
            continue
        parts = [data[c] for c in chain]
        key = [[p["sec"], sorted((c[0], c[4], c[6], c[7]) for c in p["clips"]), p["listed"]] for p in parts]
        sig = hashlib.sha1(json.dumps(key).encode()).hexdigest()[:20]
        rank = (bool(re.search(r"_(RST|RT|SKIP|RESTART)", s["name"])), len(s["name"]), s["name"])
        if sig in found and found[sig][0] <= rank:
            continue
        total = timeline(parts)[1]
        group, sub, title = classify(s["name"], s["project"] in open_world)
        found[sig] = (rank, {"id": sig, "name": s["name"], "group": group, "sub": sub, "title": title,
                             "bpm": round(s["bpm"], 2), "sec": round(total, 2), "parts": parts})
    _adopt(sections, open_world, found)
    pieces = sorted((v[1] for v in found.values()), key=_order)
    seen = collections.Counter()
    for p in pieces:                                             # the same title twice: number them
        k = (p["group"], p["sub"], p["title"])
        seen[k] += 1
        if seen[k] > 1:
            p["title"] = "%s (%d)" % (p["title"], seen[k])
    return pieces


# ------------------------------------------------------------------------------------------------ catalog
def cache_file():
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, "score.json")


def cached(path):
    """The stored catalog of this archive, or None."""
    try:
        st = os.stat(path)
        with open(cache_file(), "r", encoding="utf-8") as fh:
            c = json.load(fh)
        if c.get("v") == VERSION and c.get("size") == st.st_size and c.get("mtime") == int(st.st_mtime):
            return c["pieces"]
    except (OSError, ValueError, KeyError):
        pass
    return None


def load(path, progress=None, cancel=None, use_cache=True):
    """The pieces of the score of an audio.rpf (read once, then from the cache). progress(fraction)."""
    if use_cache:
        got = cached(path)
        if got is not None:
            return got
    st = os.stat(path)
    cfg = _Config(path)
    sections = parse_sections(cfg)
    waves = {c["wave"] for s in sections.values() for c in s["clips"]}
    banks = find_banks(path, {w[1] & STREAM_ID_MASK for w in waves}, progress, cancel)
    pieces = build_pieces(sections, locate(waves, banks))
    if not pieces:
        raise ScoreError("the sound banks of the score were not found in this file")
    try:
        with open(cache_file(), "w", encoding="utf-8") as fh:
            json.dump({"v": VERSION, "path": path, "size": st.st_size, "mtime": int(st.st_mtime), "pieces": pieces}, fh,
                      separators=(",", ":"))
    except OSError:
        pass
    return pieces


# ------------------------------------------------------------------------------------------------ rendering
def find_ffmpeg():
    """ffmpeg next to the program (or the script), else in the PATH."""
    dirs = []
    if getattr(sys, "frozen", False):
        dirs += [os.path.dirname(sys.executable), getattr(sys, "_MEIPASS", "")]
    dirs.append(os.path.dirname(os.path.abspath(__file__)))
    for d in filter(None, dirs):
        for name in ("ffmpeg.exe", "ffmpeg"):
            if os.path.isfile(os.path.join(d, name)):
                return os.path.join(d, name)
    return shutil.which("ffmpeg")


def _write_stream(rpf, position, size, out):
    """One bank stream as an Ogg Opus file (the packets 1:1)."""
    rpf.seek(position)
    data = rpf.read(size)
    writer = core.OggOpusWriter(out, 0x5343, RATE)
    try:
        for unit in range(0, len(data), UNIT):
            block = data[unit:unit + UNIT]
            p = 0
            while p + 2 <= len(block):
                n = struct.unpack_from("<H", block, p)[0]
                if n == 0 or p + 2 + n > len(block):
                    break
                writer.add(block[p + 2:p + 2 + n])
                p += 2 + n
    finally:
        writer.close()


def _run(cmd, cancel=None):
    p = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                         creationflags=NO_WINDOW)
    while True:
        try:
            _out, err = p.communicate(timeout=0.3)
            break
        except subprocess.TimeoutExpired:
            if cancel and cancel():
                p.kill()
                p.communicate()
                raise InterruptedError() from None
    if p.returncode != 0:
        raise ScoreError("FFmpeg: " + err.decode("utf-8", "replace").strip()[-600:])


def _fade_out(data, frames=240):
    """Soften the end of a loop that is cut off (16 bit stereo)."""
    n = min(frames, len(data) // FRAME)
    if not n:
        return data
    tail = array.array("h")
    tail.frombytes(data[-FRAME * n:])
    for i in range(n):
        g = 1.0 - (i + 1) / float(n)
        tail[2 * i] = int(tail[2 * i] * g)
        tail[2 * i + 1] = int(tail[2 * i + 1] * g)
    return data[:-FRAME * n] + tail.tobytes()


def _last_sound(path):
    """The frame behind the last one that is not silent, in a file of 16 bit stereo."""
    with open(path, "rb") as fh:
        end = fh.seek(0, os.SEEK_END) // FRAME
        while end > 0:
            begin = max(0, end - RATE // 10)
            fh.seek(FRAME * begin)
            block = array.array("h")
            block.frombytes(fh.read(FRAME * (end - begin)))
            if block and (max(block) > QUIET or min(block) < -QUIET):
                return end
            end = begin
    return 0


def _need_space(folder, bytes_):
    if shutil.disk_usage(folder).free < bytes_ + 50_000_000:
        raise ScoreError("not enough free disk space in %s (about %d MB needed for mixing)" % (folder, bytes_ // 1000000 + 50))


def render(path, piece, out, ffmpeg=None, cancel=None, tags=None, bitrate=BITRATE):
    """Mix the piece into the Ogg Opus file `out`. Returns its length in seconds (silence at its end is left out,
    so it can be shorter than piece["sec"])."""
    ffmpeg = ffmpeg or find_ffmpeg()
    if not ffmpeg:
        raise ScoreError("FFmpeg is needed to mix the score (see the FFmpeg note of the program)")
    folder = os.path.dirname(os.path.abspath(out))
    os.makedirs(folder, exist_ok=True)
    work = tempfile.mkdtemp(prefix=".score_", dir=folder)
    try:
        sources = {}                                             # data position -> (number, size, frames)
        for part in piece["parts"]:
            for c in part["clips"]:
                sources.setdefault(c[0], (len(sources), c[1], c[2]))
        _need_space(work, FRAME * sum(v[2] for v in sources.values()))
        with open(path, "rb") as rpf:
            for position, (i, size, _frames) in sources.items():
                _cancelled(cancel)
                _write_stream(rpf, position, size, os.path.join(work, "w%d.opus" % i))
        for first in range(0, len(sources), 40):                  # in batches: a command line has a limit
            batch = range(first, min(first + 40, len(sources)))
            decode = [ffmpeg, "-v", "error", "-y"]
            for i in batch:
                decode += ["-i", os.path.join(work, "w%d.opus" % i)]
            for n, i in enumerate(batch):
                decode += ["-map", "%d:a" % n, "-f", "s16le", "-ac", "2", "-ar", str(RATE), os.path.join(work, "w%d.pcm" % i)]
            _run(decode, cancel)
        @functools.lru_cache(maxsize=48)                          # some pieces use hundreds of recordings
        def audio(position):
            i, _size, frames = sources[position]
            with open(os.path.join(work, "w%d.pcm" % i), "rb") as fh:
                data = fh.read(FRAME * frames)                   # the exact length: the packets cover a little more
            return data + bytes(FRAME * frames - len(data))
        placed = sorted(((int(round(begin * RATE)), int(round(length * RATE)), c) for begin, length, c in
                         timeline(piece["parts"])[0]), key=lambda x: x[0])
        ends, lane_of = [], []                                   # clips that do not overlap share one lane
        for first, length, _c in placed:
            k = next((k for k, e in enumerate(ends) if e <= first), len(ends))
            if k == len(ends):
                ends.append(0)
            ends[k] = first + length
            lane_of.append(k)
        _need_space(work, FRAME * sum(ends))
        lanes = [[0, open(os.path.join(work, "l%d.pcm" % k), "wb")] for k in range(len(ends))]
        try:
            for (first, length, c), k in zip(placed, lane_of):
                _cancelled(cancel)
                data = audio(c[0])
                want, one = FRAME * length, len(data)
                if c[4]:                                         # repeat the loop as often as it takes
                    cut = min(want % one, one - want % one) > FRAME * 480        # in the middle (not just rounding)
                    data = (data * (want // one + 1))[:want]
                else:
                    cut = one - want > FRAME * 480
                    data = data[:want]
                if cut:
                    data = _fade_out(data)
                lane = lanes[k]
                gap = first - lane[0]
                while gap > 0:
                    step = min(gap, RATE)
                    lane[1].write(bytes(FRAME * step))
                    gap -= step
                lane[1].write(data)
                lane[0] = first + len(data) // FRAME
        finally:
            for lane in lanes:
                lane[1].close()
        total = max(ends) / float(RATE)
        sound = max(_last_sound(os.path.join(work, "l%d.pcm" % i)) for i in range(len(lanes))) / float(RATE)
        seconds = min(total, max(1.0, sound + TAIL))             # no long silence at the end
        mix = [ffmpeg, "-v", "error", "-y"]
        for i in range(len(lanes)):
            mix += ["-f", "s16le", "-ar", str(RATE), "-ac", "2", "-i", os.path.join(work, "l%d.pcm" % i)]
        chain = "volume=-3dB,alimiter=limit=0.8:level=disabled"      # headroom: the encoder overshoots a little
        if len(lanes) > 1:
            chain = "".join("[%d:a]" % i for i in range(len(lanes))) + "amix=inputs=%d:normalize=0:duration=longest," % len(lanes) + chain
        mix += ["-filter_complex", chain, "-t", "%.4f" % seconds, "-c:a", "libopus", "-b:a", bitrate, "-ar", str(RATE)]
        for k, v in (tags or {}).items():
            if v:
                mix += ["-metadata", "%s=%s" % (k, v)]
        done = os.path.join(work, "out.opus")
        _run(mix + ["-f", "ogg", done], cancel)
        os.replace(done, out)
        return seconds
    finally:
        shutil.rmtree(work, ignore_errors=True)
