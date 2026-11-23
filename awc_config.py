"""Read track and station names from the game's own audio config.

The start of audio.rpf (everything before the first AWC bank) is a run of plain zlib streams: the game's audio
config. It contains a list of internal names (as text) next to the 29-bit hashes that the AWC streams use as IDs.
Nothing is decrypted: the streams are inflated and every printable string is hashed with joaat() and compared with
the IDs of the streams that are currently loaded. The result is only kept on the user's PC (names.NameStore); no
names are shipped with the tool.

Also finds the station names ("Core.RadioStation.<slug>") so they can be offered when a group is renamed.
"""
import re
import struct
import zlib

from awc_names import STREAM_ID_MASK, joaat, normalized_name

HDR = re.compile(rb"\x78[\x01\x5e\x9c\xda]")
STRINGS = re.compile(rb"[\x20-\x7e]{4,120}")
STATION = re.compile(rb"Core\.RadioStation\.([A-Za-z0-9_]+)")
MAX_ZLIB = 1 << 20           # a single config stream is much smaller (64 KiB inflated)
MAX_SCAN = 64 << 20          # never read more than this from the start of an archive (config: ~19 MiB)
UPPER_WORDS = {"fm", "dj", "tv", "ig", "ii", "iii", "usa", "uk"}


def inflate_streams(data):
    """Yield the inflated content of every zlib stream that decodes cleanly to its end in `data`."""
    pos = 0
    while True:
        m = HDR.search(data, pos)
        if not m:
            return
        start = m.start()
        chunk = data[start:start + MAX_ZLIB]
        d = zlib.decompressobj()
        try:
            out = d.decompress(chunk)
        except zlib.error:
            pos = start + 1
            continue
        if d.eof and len(out) >= 16:
            yield out
            pos = start + (len(chunk) - len(d.unused_data))
        else:
            pos = start + 1


def first_adat(data):
    """Offset of the first AWC bank (an 'ADAT' whose header parses as one) in `data`, or len(data)."""
    pos = data.find(b"ADAT")
    while pos >= 0:
        if pos + 16 <= len(data):
            _m, ver, _fl, count, info = struct.unpack_from("<4sHHII", data, pos)
            if ver == 1 and 1 <= count <= 65536 and 0x10 <= info <= 0xFFF0:
                return pos
        pos = data.find(b"ADAT", pos + 1)
    return len(data)


def read_config_strings(path):
    """(printable strings, station slugs) from the config at the start of an archive."""
    with open(path, "rb") as f:
        data = f.read(MAX_SCAN)
    data = data[:first_adat(data)]
    strings, stations = set(), set()
    for blob in inflate_streams(data):
        for m in STRINGS.finditer(blob):
            strings.add(m.group().decode("ascii"))
        for m in STATION.finditer(blob):
            stations.add(m.group(1).decode("ascii"))
    return strings, stations


def resolve(strings, wanted):
    """{stream id: internal name} for the names whose 29-bit hash is in `wanted` (an iterable of stream IDs)."""
    want = {}
    for i in wanted:
        want.setdefault(i & STREAM_ID_MASK, i & 0xFFFFFFFF)
    found = {}
    for s in strings:
        variants = [s, normalized_name(s)]
        if "." in s:
            variants.append(s.rsplit(".", 1)[-1])
        if len(s) >= 14:                     # the config stores a length byte or two in front of a name: "d&SymphonyFM_Prokofiev_..."
            variants += [s[1:], s[2:]]
        for v in variants:
            if len(v) < 4:
                continue
            h = joaat(v) & STREAM_ID_MASK
            if h in want and want[h] not in found:
                found[want[h]] = v
    return found


def pretty(slug):
    """'FANTASTIC_MAN' -> 'Fantastic Man', 'flash_fm' -> 'Flash FM'."""
    words = [w for w in re.split(r"[_\s]+", slug.strip()) if w]
    return " ".join(w.upper() if w.lower() in UPPER_WORDS else w[:1].upper() + w[1:].lower() for w in words)


def read_names(path, wanted):
    """Names and the station list of one archive for the loaded stream IDs.

    Returns (names {stream id: pretty title}, stations [pretty names], n_strings)."""
    strings, stations = read_config_strings(path)
    found = resolve(strings, wanted)
    return ({i: pretty(n) for i, n in found.items()},
            sorted({pretty(s) for s in stations}), len(strings))
