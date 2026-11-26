"""The titles the game shows for its radio and podcasts, read from data.rpf (it lies next to audio.rpf).

The game's texts are stored there as ordinary zlib streams; nothing is decrypted. A text table is a "resource" that is cut
into pages, one stream each, 16 byte aligned; pointers inside it are 0x50000000 + offset. As read from the game's files:

    page 1     u64 0, u64 root, u32 "PRD0" (0x50524430) at offset 16, ...
    records    u32 flags, u32 size, u64 pointer: flags 0x101 = the slots of a hash table, flags 0x10 = the block of
               null terminated strings
    slot       16 bytes: u32 key, u32 0, u8 1 (used), u16 0, u32 number of the string (at byte 11), u8 0

The radio table's keys are joaat("<track number>s") for the title and joaat("<track number>a") for the artist; the track
number is the marker every song, podcast episode and part of a mix carries in its AWC (core.AwcFile.tid / cues). There is
one table per language; the first one in the file is the English one and is used here (names are the same in all).
"""
import difflib
import os
import re
import struct
import zlib

import awc_config as gamecfg
import awc_credits as credits
from awc_names import joaat

MAGIC = 0x50524430
BASE = 0x50000000
FILE = "data.rpf"
MAX_FILE = 2 << 30
MAX_TID = 60000              # the track numbers in use are far below this


def data_file(audio_rpf):
    """The data.rpf next to an audio.rpf, or "" when there is none."""
    path = os.path.join(os.path.dirname(os.path.abspath(audio_rpf)), FILE)
    return path if os.path.isfile(path) else ""


def _stream(view, start):
    """(content, end) of the zlib stream at `start`, or None."""
    d = zlib.decompressobj()
    try:
        out = [d.decompress(view[start:start + 512])]
        at = start + 512
        while not d.eof and at < len(view):
            out.append(d.decompress(view[at:at + 65536]))
            at += 65536
    except zlib.error:
        return None
    if not d.eof:
        return None
    return b"".join(out), min(at, len(view)) - len(d.unused_data)


def _is_head(c):
    return len(c) >= 32 and c[:8] == bytes(8) and struct.unpack_from("<I", c, 16)[0] == MAGIC


def resources(data):
    """Yield every resource of `data` (the pages joined), in the order of the file."""
    view = memoryview(data)
    pages, end, pos = [], 0, 0
    while True:
        m = gamecfg.HDR.search(data, pos)
        if not m:
            break
        start = m.start()
        pos = start + 1
        got = _stream(view, start)
        if got is None or len(got[0]) < 64:
            continue
        content, stop = got
        pos = max(pos, stop)
        if _is_head(content):
            if pages:
                yield b"".join(pages)
            pages, end = [content], stop
        elif pages and 0 <= start - end <= 16:
            pages.append(content)
            end = stop
    if pages:
        yield b"".join(pages)


def table(res):
    """{key: string} of a resource that holds a text table, or {}."""
    if len(res) < 64:
        return {}
    root = struct.unpack_from("<Q", res, 8)[0] - BASE
    if not 0 <= root < len(res):
        return {}
    slots = text = None
    for o in range(max(0, root - 0x100) & ~7, min(root + 0x80, len(res) - 15), 8):     # the records lie around the root
        flags, size, ptr, zero = struct.unpack_from("<4I", res, o)
        if zero or not BASE <= ptr < BASE + len(res) or not 16 <= size <= len(res):
            continue
        if flags == 0x101 and slots is None:
            slots = (ptr - BASE, size)
        elif flags == 0x10 and text is None:
            text = (ptr - BASE, size)
    if not slots or not text:
        return {}
    strings = res[text[0]:text[0] + text[1]].split(b"\0")
    out = {}
    for o in range(slots[0], min(slots[0] + slots[1], len(res)) - 15, 16):
        if res[o + 4:o + 11] != b"\0\0\0\0\x01\0\0" or res[o + 15]:
            continue
        number = struct.unpack_from("<I", res, o + 11)[0]
        if number < len(strings):
            out[struct.unpack_from("<I", res, o)[0]] = strings[number].decode("utf-8", "replace")
    return out


def track_titles(data, wanted, shows=None):
    """{track number: (title, artist)} for the numbers in `wanted`, from the table of `data` that names the most of them
    (of equal ones the first, which is the English one). A dictionary `shows` collects {number: name} of those that have
    no title, only a name in the artist column (a radio show)."""
    keys = {}
    for tid in wanted:
        if 0 < tid < MAX_TID:
            keys[joaat("%ds" % tid)] = (tid, 0)
            keys[joaat("%da" % tid)] = (tid, 1)
    best, count = {}, 0
    for res in resources(data):
        found = {}
        for key, string in table(res).items():
            hit = keys.get(key)
            if hit:
                found.setdefault(hit[0], ["", ""])[hit[1]] = string.strip()
        titled = sum(1 for v in found.values() if v[0])
        if titled > count:
            best, count = {tid: tuple(v) for tid, v in found.items() if v[0]}, titled
            if shows is not None:
                shows.clear()
                shows.update({tid: v[1] for tid, v in found.items() if v[1] and not v[0]})
    return best


def read_titles(audio_rpf, wanted, shows=None):
    """track_titles() for the data.rpf next to `audio_rpf`; {} when there is no such file or it cannot be read."""
    path = data_file(audio_rpf)
    if not path or os.path.getsize(path) > MAX_FILE:
        return {}
    try:
        with open(path, "rb") as f:
            data = f.read()
        return track_titles(data, wanted, shows)
    except (OSError, MemoryError, struct.error, ValueError):
        return {}


# ------------------------------------------------------------------------------------------------ numbers and spelling
def numbers(items):
    """The track numbers that occur in scan items: of single recordings and of the markers of the mixes."""
    out = set()
    for it in items:
        if it.get("tid") is not None:
            out.add(it["tid"])
        out.update(value for _at, value in it.get("cues") or ())
    return out


def fill_gaps(run):
    """The numbers of an ordered run of episodes, the missing ones filled in when at least two are known and all known ones
    fit one run (two episodes of a podcast carry no marker)."""
    bases = [n - i for i, n in enumerate(run) if n is not None]
    if len(bases) < 2 or len(set(bases)) != 1:
        return list(run)
    return [bases[0] + i for i in range(len(run))]


KEEP_CAPITALS = {"DJ", "MC", "TF", "II", "III", "IV", "FM", "UK", "USA", "TV", "NYC", "LA", "R&B"}


def pretty(text):
    """The game writes artists (and a few titles) in capitals: 'GLORILLA FT. SEXYY RED' -> 'Glorilla ft. Sexyy Red'."""
    if text != text.upper() or (len(text) <= 4 and " " not in text):          # "XTC", "ÂME": a name as it is
        return text

    def word(match):
        w = match.group()
        if w in KEEP_CAPITALS:
            return w
        low = w.lower()
        return low if low in ("ft", "feat", "vs") else low[0].upper() + low[1:]
    out = re.sub(r"[^\W\d_]+(?:['’][^\W\d_]+)?", word, text)
    return re.sub(r"\b([DOL]['’])([a-z])(?=[a-z])", lambda m: m.group(1) + m.group(2).upper(), out)      # D'Angelo


def artists(text):
    """The single artists of an artist line."""
    return [x for x in re.split(r"\s+(?:ft\.?|feat\.?|featuring|x|vs\.?)\s+|\s*[,&/]\s*", text, flags=re.I) if x]


def spell(title, artist, songs=(), station=""):
    """(title, artist) for display: as the credits list spells them, else the game's own words without the shouting."""
    hit = credits.lookup(songs, station, title, artists(artist)) if songs else None
    return hit or (pretty(title), pretty(artist))


def agrees(title, artist, game):
    """True when a name says what the game's table says (another spelling, a longer title): then it stays as it is."""
    if not title or title == "Unknown track" or not artist:
        return False
    a, b = (credits._compact(re.sub(r"\(.*?\)|\[.*?\]", " ", x)) for x in (title, game[0]))
    same = a == b or (min(len(a), len(b)) >= 5 and (a in b or b in a)) or difflib.SequenceMatcher(None, a, b).ratio() >= 0.8
    mine = credits._compact(artist)
    theirs = [credits._compact(x) for x in artists(game[1])]
    return same and (not any(theirs) or any(x and (x in mine or mine in x) for x in theirs))
