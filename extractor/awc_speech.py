"""The speech containers of the radio stations: the DJs' intros, outros and announcements.

A speech AWC is not stored like a music one: it holds many short lines, each on its own. Layout (little endian), as read
from the game's files:

    "ADAT", u16 version 1, u16 flags (low byte 2), u32 number of lines, u32 end of the headers
    lines x u32       bits 29-31: number of chunks of the line, bits 0-28: stream ID
    chunks x u64      bits 56-63: tag, 28-55: size, 0-27: offset in the container (the chunks of the lines in order)
    chunk 0x76        format: u32 samples, i32 -1, u16 sample rate, ..., byte 28 = codec (0x0B Opus)
    chunk 0x55        audio: units of 2 KiB, each with Opus packets behind a u16 length (as in the music), mono

Nothing is decrypted: containers whose headers or codec are different are refused.
"""
import mmap
import os
import struct

import awc_core as core
from awc_i18n import tr
from awc_names import STREAM_ID_MASK, joaat

FLAGS = 2
FORMAT, DATA, OPUS = 0x76, 0x55, 0x0B
UNIT = 0x800
MAX_GAP = 40 << 20           # larger holes between music banks are not station speech


class SpeechFile:
    """The lines of one speech container, with the same interface as core.AwcFile (streams, extract)."""

    def __init__(self, path, base=0, length=None, name=None):
        self.path, self.base = path, base
        self.name = name or os.path.basename(path)
        total = os.path.getsize(path)
        self.size = (total - base) if length is None else min(length, total - base)
        self.streams, self.tid, self.cues = [], None, []
        self._data = []                                   # (offset, size) of every line's audio
        with open(path, "rb") as f:
            f.seek(base)
            head = f.read(16)
            if len(head) < 16 or head[:4] != b"ADAT":
                raise core.AwcError(tr("err_not_awc"))
            version, flags, count, end = struct.unpack_from("<HHII", head, 4)
            if version != 1 or flags & 0xFF != FLAGS or not 1 <= count <= 20000 or not 16 + 4 * count <= end <= min(self.size, 4 << 20):
                raise core.AwcError(tr("err_header"))
            f.seek(base)
            head = f.read(end)
            entries = struct.unpack_from("<%dI" % count, head, 16)
            at = 16 + 4 * count
            if at + 8 * sum(e >> 29 for e in entries) != end:
                raise core.AwcError(tr("err_header"))
            stop = end
            for n, entry in enumerate(entries):
                chunks = {}
                for _ in range(entry >> 29):
                    v = struct.unpack_from("<Q", head, at)[0]
                    offset, size = v & 0xFFFFFFF, (v >> 28) & 0xFFFFFFF
                    if offset + size > self.size:
                        raise core.AwcError(tr("err_header"))
                    chunks[v >> 56] = (offset, size)
                    stop = max(stop, offset + size)
                    at += 8
                if FORMAT not in chunks or DATA not in chunks or chunks[FORMAT][1] < 29:
                    raise core.AwcError(tr("err_not_music"))
                f.seek(base + chunks[FORMAT][0])
                form = f.read(chunks[FORMAT][1])
                samples, rate, codec = struct.unpack_from("<I", form, 0)[0], struct.unpack_from("<H", form, 8)[0], form[28]
                if codec != OPUS or rate < 8000:
                    raise core.AwcError(tr("err_codec", c="0x%02X" % codec))
                s = core.AwcStream(n, entry & 0x1FFFFFFF, rate, codec)
                s.opus_channels = 1
                s.est_seconds = samples / float(rate)
                self.streams.append(s)
                self._data.append(chunks[DATA])
            self.size = stop
        self.n_blocks, self.truncated = 0, False

    def feed(self, writers, progress=None, cancel=None):
        with open(self.path, "rb") as f:
            for done, (index, writer) in enumerate(sorted(writers.items())):
                if cancel and cancel():
                    raise InterruptedError()
                offset, size = self._data[index]
                f.seek(self.base + offset)
                data = f.read(size)
                for unit in range(0, len(data), UNIT):
                    block = data[unit:unit + UNIT]
                    p = 0
                    while p + 2 <= len(block):
                        n = struct.unpack_from("<H", block, p)[0]
                        if n == 0 or p + 2 + n > len(block):
                            break
                        writer.add(block[p + 2:p + 2 + n])
                        p += 2 + n
                if progress:
                    progress((done + 1) / float(len(writers)))

    extract = core.AwcFile.extract


def item(speech, number):
    """The scan item of a speech container (the shape of core.item_from_awc, marked "speech")."""
    return {"kind": "speech", "n": number, "name": speech.name, "path": speech.path, "base": speech.base, "length": speech.size,
            "blocks": 0, "streams": [{"i": s.index, "id": s.id, "codec": s.codec, "ch": 1, "sps": s.sample_rate,
                                      "sec": round(s.est_seconds, 2)} for s in speech.streams]}


def find(path, items, low, high, cancel=None):
    """The speech containers that lie between the music banks of `items`, from archive position `low` to `high`."""
    music = sorted((i for i in items if low <= i["base"] <= high), key=lambda i: i["base"])
    out = []
    with open(path, "rb") as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as data:
        for before, after in zip(music, music[1:]):
            start, end = before["base"] + (before["length"] or 0), after["base"]
            if not 16 <= end - start <= MAX_GAP:
                continue
            if cancel and cancel():
                raise InterruptedError()
            block = data[start:end]
            number, at = before["n"], block.find(b"ADAT")
            while at >= 0:
                number += 1
                try:
                    speech = SpeechFile(path, start + at, end - start - at,
                                        "%s_%d_at_%016X" % (os.path.splitext(os.path.basename(path))[0], number, start + at))
                except (core.AwcError, struct.error, OSError, ValueError):
                    at = block.find(b"ADAT", at + 4)
                    continue
                out.append(item(speech, number))
                at = block.find(b"ADAT", at + max(4, speech.size))
    return out


def line_names(ids, titles):
    """{stream id: (kind, song, number)} for the lines whose name can be told from its ID.

    The game names them dj_intro_<song>_01, dj_outro_<song>_01 (<song> as the config writes the title) and
    dj_intro_01, dj_outro_01, dj_to_ads_01, dj_to_news_01. kind is "intro", "outro", "to_ads" or "to_news"; the song
    is "" for the general ones."""
    want = {i & STREAM_ID_MASK: i for i in ids}
    found = {}
    for kind in ("intro", "outro", "to_ads", "to_news"):
        for n in range(1, 100):
            i = want.get(joaat("dj_%s_%02d" % (kind, n)) & STREAM_ID_MASK)
            if i is not None:
                found.setdefault(i, (kind, "", n))
    for song in {t.lower() for t in titles}:
        for kind in ("intro", "outro"):
            for n in range(1, 16):
                i = want.get(joaat("dj_%s_%s_%02d" % (kind, song, n)) & STREAM_ID_MASK)
                if i is not None:
                    found.setdefault(i, (kind, song, n))
    return found
