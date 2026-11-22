"""AWC parser and extractor for multichannel music containers with the Opus codec (0x0B).

The Opus packets are written unchanged (no re-encoding) into Ogg Opus files.
"""
import os
import struct
import zlib

from awc_i18n import tr

BLOCK_CHUNK_TAGS = (0x48, 0x55, 0xA3, 0xBD)
CODEC_NAMES = {
    0x00: "PCM", 0x01: "PCM", 0x04: "IMA-ADPCM", 0x05: "XMA2", 0x07: "MPEG",
    0x08: "Vorbis", 0x0B: "Opus", 0x0C: "DSP", 0x0D: "Opus (RDR)", 0x0F: "ATRAC9",
    0x10: "DSP", 0x11: "MS-ADPCM",
}
SUPPORTED_CODECS = (0x0B,)


class AwcError(Exception):
    pass


# --------------------------------------------------------------------------- Ogg
def _crc_table():
    t = []
    for i in range(256):
        r = i << 24
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) if (r & 0x80000000) else (r << 1)
            r &= 0xFFFFFFFF
        t.append(r)
    return t


_CRC = _crc_table()


_REV8 = bytes(int("{:08b}".format(i)[::-1], 2) for i in range(256))


def _ogg_crc_slow(data):
    c = 0
    tbl = _CRC
    for b in data:
        c = ((c << 8) & 0xFFFFFFFF) ^ tbl[(c >> 24) ^ b]
    return c


def _ogg_crc(data):
    """Ogg CRC (poly 0x04C11DB7, not reflected) computed via zlib.crc32 -> very fast."""
    raw = zlib.crc32(data.translate(_REV8), 0xFFFFFFFF) ^ 0xFFFFFFFF
    return int("{:032b}".format(raw)[::-1], 2)


def opus_packet_samples(pkt):
    """Anzahl Samples (48 kHz) die ein Opus-Paket enthaelt, aus dem TOC-Byte."""
    toc = pkt[0]
    cfg = toc >> 3
    if cfg < 12:
        ms = (10, 20, 40, 60)[cfg & 3]
    elif cfg < 16:
        ms = (10, 20)[cfg & 1]
    else:
        ms = (2.5, 5, 10, 20)[cfg & 3]
    code = toc & 3
    if code == 0:
        frames = 1
    elif code in (1, 2):
        frames = 2
    else:
        frames = (pkt[1] & 0x3F) if len(pkt) > 1 else 1
    return int(ms * 48 * frames)


class OggOpusWriter:
    """Writes Opus packets 1:1 into an Ogg file (headers are created on the first packet)."""

    def __init__(self, path, serial, sample_rate=48000, tags=None):
        self.tags = tags or {}
        self.fh = open(path, "wb")
        self.serial = serial
        self.rate = sample_rate
        self.seq = 0
        self.granule = 0
        self.pending = []
        self.packets = 0
        self.started = False

    def _page(self, packets, granule, flags):
        segs = []
        for p in packets:
            n = len(p)
            while n >= 255:
                segs.append(255)
                n -= 255
            segs.append(n)
        hdr = b"OggS" + struct.pack("<BBqIIIB", 0, flags, granule, self.serial,
                                    self.seq, 0, len(segs)) + bytes(segs)
        body = b"".join(packets)
        crc = _ogg_crc(hdr + body)
        self.fh.write(hdr[:22] + struct.pack("<I", crc) + hdr[26:] + body)
        self.seq += 1

    def add(self, pkt):
        if not self.started:
            channels = 2 if (pkt[0] & 4) else 1
            head = b"OpusHead" + struct.pack("<BBHIhB", 1, channels, 312, self.rate, 0, 0)
            items = [("%s=%s" % (k, v)).encode("utf-8") for k, v in self.tags.items() if v]
            tags = (b"OpusTags" + struct.pack("<I", 7) + b"AWCTool" + struct.pack("<I", len(items))
                    + b"".join(struct.pack("<I", len(i)) + i for i in items))
            self._page([head], 0, 0x02)
            self._page([tags], 0, 0)
            self.started = True
        self.pending.append(pkt)
        self.packets += 1
        self.granule += opus_packet_samples(pkt)
        if len(self.pending) >= 40:
            self.flush()

    def flush(self):
        if self.pending:
            self._page(self.pending, self.granule, 0)
            self.pending = []

    def close(self):
        if self.started:
            self.flush()
            self._page([], self.granule, 0x04)
        self.fh.close()

    @property
    def seconds(self):
        return self.granule / float(self.rate)


# --------------------------------------------------------------------------- AWC
class AwcStream:
    def __init__(self, index, sid, sample_rate, codec):
        self.index = index
        self.id = sid
        self.sample_rate = sample_rate
        self.codec = codec
        self.opus_channels = 2
        self.est_seconds = 0.0

    @property
    def codec_name(self):
        return CODEC_NAMES.get(self.codec, "0x%02X" % self.codec)

    @property
    def supported(self):
        return self.codec in SUPPORTED_CODECS

    @property
    def label(self):
        return tr("stream_label", i=self.index, id="%08X" % self.id)


class AwcFile:
    """An AWC file: either a standalone file or a slice (base/length) inside a larger
    file, e.g. an RPF archive."""

    def __init__(self, path, base=0, length=None, name=None):
        self.path = path
        self.base = base
        total = os.path.getsize(path)
        self.size = (total - base) if length is None else min(length, total - base)
        self.name = name or os.path.basename(path)
        self.streams = []
        self._parse()

    @property
    def key(self):
        return "%s@%d" % (self.path, self.base)

    # -- header ---------------------------------------------------------------
    def _parse(self):
        with open(self.path, "rb") as f:
            f.seek(self.base)
            hdr = f.read(0x10)
            if len(hdr) < 0x10 or hdr[:4] != b"ADAT":
                raise AwcError(tr("err_not_awc"))
            _magic, _ver, _flags, _n, info_off = struct.unpack("<4sHHII", hdr)
            if info_off < 0x10 or info_off > 0x10000:
                raise AwcError(tr("err_header"))
            f.seek(self.base)
            head = f.read(info_off)
            chunks = {}
            for i in range(0x10, info_off - 7, 4):
                v = struct.unpack_from("<Q", head, i)[0]
                tag = v >> 56
                off = v & 0xFFFFFFF
                size = (v >> 28) & 0xFFFFFFF
                if tag in BLOCK_CHUNK_TAGS and off >= info_off and off < self.size + 0x10000000:
                    chunks.setdefault(tag, (off, size))
            if 0x48 not in chunks or 0x55 not in chunks:
                raise AwcError(tr("err_not_music"))
            f.seek(self.base + chunks[0x48][0])
            fmt = f.read(chunks[0x48][1])
            self.block_count, self.block_size, nch = struct.unpack_from("<III", fmt, 0)
            if nch == 0 or nch > 32 or self.block_size == 0:
                raise AwcError(tr("err_format_chunk"))
            self.data_off = chunks[0x55][0]
            self.data_size = min(chunks[0x55][1], self.size - self.data_off)
            for c in range(nch):
                sid, _samples, _headroom, sps, codec = struct.unpack_from("<IIhHB", fmt, 12 + 16 * c)
                self.streams.append(AwcStream(c, sid, sps, codec))
            self.n_blocks = (self.data_size + self.block_size - 1) // self.block_size
            self.truncated = self.data_size < chunks[0x55][1]
            self._estimate(f)

    def _estimate(self, f):
        nch = len(self.streams)
        totals = [0] * nch
        for b in range(self.n_blocks):
            off = self.data_off + b * self.block_size
            f.seek(self.base + off)
            raw = f.read(0x18 * nch)
            if len(raw) < 0x18 * nch:
                break
            avail = min(self.block_size, self.data_off + self.data_size - off)
            frac = 1.0 if avail >= self.block_size else avail / float(self.block_size)
            for c in range(nch):
                totals[c] += struct.unpack_from("<i", raw, 0x18 * c + 0x0C)[0] * frac
        # Opus channel count from the first packet
        f.seek(self.base + self.data_off)
        blk = f.read(min(self.block_size, self.data_size))
        ents = [struct.unpack_from("<i", blk, 0x18 * c + 4)[0] for c in range(nch)]
        pos = ((0x18 * nch + 4 * sum(ents)) + 0x7FF) & ~0x7FF
        for c in range(nch):
            st = self.streams[c]
            if ents[c] > 0 and pos + 3 <= len(blk):
                n = struct.unpack_from("<H", blk, pos)[0]
                if 0 < n and pos + 3 <= len(blk):
                    st.opus_channels = 2 if (blk[pos + 2] & 4) else 1
            st.est_seconds = totals[c] / float(st.sample_rate * st.opus_channels)
            pos += ents[c] * 0x800

    # -- extraction -------------------------------------------------------------
    def extract(self, jobs, progress=None, cancel=None, tags=None):
        """jobs: {stream_index: out_path.opus}; tags: optional {stream_index: {"TITLE": ...}} written as
        Opus comments. Returns {stream_index: seconds}."""
        tags = tags or {}
        nch = len(self.streams)
        for idx in jobs:
            if not self.streams[idx].supported:
                raise AwcError(tr("err_codec", c=self.streams[idx].codec_name))
        writers = {i: OggOpusWriter(p, 0x4157 + i, self.streams[i].sample_rate, tags.get(i))
                   for i, p in jobs.items()}
        ok = False
        try:
            with open(self.path, "rb") as f:
                for b in range(self.n_blocks):
                    if cancel and cancel():
                        raise InterruptedError()
                    off = self.data_off + b * self.block_size
                    f.seek(self.base + off)
                    blk = f.read(min(self.block_size, self.data_off + self.data_size - off))
                    if len(blk) < 0x18 * nch:
                        break
                    ents = [struct.unpack_from("<i", blk, 0x18 * c + 4)[0] for c in range(nch)]
                    if any(e < 0 or e > 4096 for e in ents):
                        break  # corrupt block (e.g. truncated file)
                    pos = ((0x18 * nch + 4 * sum(ents)) + 0x7FF) & ~0x7FF
                    for c in range(nch):
                        if c in writers:
                            w = writers[c]
                            for e in range(ents[c]):
                                ent = blk[pos + e * 0x800: pos + (e + 1) * 0x800]
                                p = 0
                                while p + 2 <= len(ent):
                                    n = struct.unpack_from("<H", ent, p)[0]
                                    if n == 0 or p + 2 + n > len(ent):
                                        break
                                    w.add(ent[p + 2:p + 2 + n])
                                    p += 2 + n
                        pos += ents[c] * 0x800
                    if progress:
                        progress((b + 1) / float(self.n_blocks))
            ok = True
        finally:
            for w in writers.values():
                w.close()
            if not ok:
                for p in jobs.values():
                    try:
                        os.remove(p)
                    except OSError:
                        pass
        return {i: w.seconds for i, w in writers.items()}


# --------------------------------------------------------------------------- scanner
import hashlib
import json
import time

SCAN_CHUNK = 64 * 1024 * 1024


def _probe_adat(g, pos, container_size):
    """Check whether an AWC header starts at pos. Returns dict(length) or None."""
    g.seek(pos)
    head = g.read(0x10000)
    if len(head) < 0x20 or head[:4] != b"ADAT":
        return None
    ver, flags, n, info_off = struct.unpack_from("<HHII", head, 4)
    if ver != 1 or not (1 <= n <= 65536) or not (0x10 <= info_off <= 0xFFF0):
        return None
    end = 0
    for i in range(0x10, min(info_off, len(head)) - 7, 4):
        v = struct.unpack_from("<Q", head, i)[0]
        tag, off, size = v >> 56, v & 0xFFFFFFF, (v >> 28) & 0xFFFFFFF
        if tag in BLOCK_CHUNK_TAGS and off >= info_off and pos + off + size <= container_size:
            end = max(end, off + size)
    return {"length": end or None}


def item_from_awc(awc, ordinal):
    return {
        "n": ordinal, "name": awc.name, "path": awc.path, "base": awc.base, "length": awc.size,
        "blocks": awc.n_blocks,
        "streams": [{"i": s.index, "id": s.id, "codec": s.codec, "ch": s.opus_channels,
                     "sps": s.sample_rate, "sec": round(s.est_seconds, 2)} for s in awc.streams],
    }


def scan_container(path, progress=None, cancel=None):
    """Search a file of any size (e.g. an RPF) for AWC headers ('ADAT').
    Returns (items, total_awc); items = music AWCs only (with stream info)."""
    size = os.path.getsize(path)
    base_name = os.path.splitext(os.path.basename(path))[0]
    items, total, skip_until, last_hit = [], 0, 0, -1
    pos = 0
    with open(path, "rb") as f, open(path, "rb") as g:
        while pos < size:
            if cancel and cancel():
                raise InterruptedError()
            f.seek(pos)
            buf = f.read(SCAN_CHUNK)
            if not buf:
                break
            i = buf.find(b"ADAT")
            while i != -1:
                a = pos + i
                if a > last_hit and a >= skip_until:
                    last_hit = a
                    info = _probe_adat(g, a, size)
                    if info is not None:
                        total += 1
                        if info["length"]:
                            skip_until = a + info["length"]
                        try:
                            awc = AwcFile(path, a, info["length"], "%s_%d_at_%016X" % (base_name, total, a))
                            items.append(item_from_awc(awc, total))
                        except (AwcError, struct.error, OSError, ValueError):
                            pass  # SFX/speech AWC or corrupt -> not a music stream
                i = buf.find(b"ADAT", i + 1)
            pos += max(1, len(buf) - 3)
            if progress:
                progress(min(1.0, pos / float(size)), len(items), total)
    return items, total


GROUP_GAP = 0x10000   # byte gap between two music AWCs that starts a new group


def add_groups(items):
    """Cluster the AWCs of one archive into groups by their position in the archive.

    Files of one folder are stored back to back, so a new group starts where there is a larger gap
    (>= 64 KiB) or where the running AWC number jumps (a non-music AWC sits in between).
    Sets item["g"] (1-based group number) on every item and returns the number of groups.
    """
    prev = None
    g = 0
    for it in sorted(items, key=lambda x: x["base"]):
        if prev is None or it["base"] - (prev["base"] + (prev["length"] or 0)) >= GROUP_GAP                 or it.get("n", 0) - prev.get("n", 0) != 1:
            g += 1
        it["g"] = g
        prev = it
    return g


def _cache_file(path):
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool", "cache")
    os.makedirs(d, exist_ok=True)
    return os.path.join(d, hashlib.sha1(os.path.abspath(path).lower().encode("utf-8")).hexdigest() + ".json")


def scan_container_cached(path, progress=None, cancel=None, use_cache=True):
    st = os.stat(path)
    cf = _cache_file(path)
    if use_cache and os.path.isfile(cf):
        try:
            with open(cf, "r", encoding="utf-8") as fh:
                c = json.load(fh)
            if c.get("size") == st.st_size and c.get("mtime") == int(st.st_mtime) and c.get("v") == 2:
                for it in c["items"]:
                    it["path"] = path          # the archive may have been moved/renamed since the scan
                add_groups(c["items"])
                return c["items"], c["total"], True
        except (OSError, ValueError, KeyError):
            pass
    items, total = scan_container(path, progress, cancel)
    add_groups(items)
    try:
        with open(cf, "w", encoding="utf-8") as fh:
            json.dump({"v": 2, "path": path, "size": st.st_size, "mtime": int(st.st_mtime),
                       "total": total, "items": items}, fh)
    except OSError:
        pass
    return items, total, False


def collect_files(root):
    files = []
    for dp, _dn, fn in os.walk(root):
        for n in fn:
            if os.path.splitext(n)[1].lower() in (".rpf", ".awc"):
                files.append(os.path.join(dp, n))
    files.sort(key=lambda p: p.lower())
    return files


def scan_files(files, progress=None, cancel=None, status=None, use_cache=True):
    """Scan a list of *.rpf (header scan) and *.awc (direct) files.
    Returns a list of containers {"path","kind","items":[...]} (only those with music AWCs)."""
    weight = []
    for p in files:
        try:
            weight.append(max(1, os.path.getsize(p)))
        except OSError:
            weight.append(1)
    tot = float(sum(weight)) or 1.0
    done_w = 0.0
    out = []
    for p, w in zip(files, weight):
        if cancel and cancel():
            raise InterruptedError()
        ext = os.path.splitext(p)[1].lower()
        if status:
            status(tr("st_scanning", p=p))
        base_prog = done_w / tot
        sub = (lambda fr, nm, nt, bp=base_prog, w=w: progress(bp + fr * w / tot, nm, nt)) if progress else None
        items = []
        try:
            if ext == ".rpf":
                items, _total, _cached = scan_container_cached(p, sub, cancel, use_cache)
            else:
                try:
                    items = [item_from_awc(AwcFile(p), 1)]
                except (AwcError, struct.error, OSError, ValueError):
                    pass
        except InterruptedError:
            raise
        except OSError as e:
            if status:
                status(tr("st_scan_error_file", p=p, e=e))
        done_w += w
        if progress:
            progress(done_w / tot, sum(len(c["items"]) for c in out) + len(items), 0)
        if items:
            out.append({"path": p, "kind": ext[1:], "items": items})
    return out


def scan_folder(root, progress=None, cancel=None, status=None, use_cache=True):
    return scan_files(collect_files(root), progress, cancel, status, use_cache)


class AwcRef:
    """Lightweight reference to an AWC (standalone file or slice inside an RPF).
    The actual file is only opened on demand."""

    def __init__(self, item):
        self.item = item
        self.path = item["path"]
        self.base = item["base"]
        self.name = item["name"]
        self.size = item["length"] or 0
        self.n_blocks = item.get("blocks", 0)
        self.key = "%s@%d" % (self.path, self.base)
        self.in_rpf = self.path.lower().endswith(".rpf")
        self.group = item.get("g", 0)
        self.container = os.path.splitext(os.path.basename(self.path))[0]
        self.streams = []
        for si in item["streams"]:
            st = AwcStream(si["i"], si["id"], si["sps"], si["codec"])
            st.opus_channels = si["ch"]
            st.est_seconds = si["sec"]
            self.streams.append(st)
        self._awc = None

    def open(self):
        if self._awc is None:
            self._awc = AwcFile(self.path, self.base, self.item["length"], self.name)
        return self._awc

    def extract(self, jobs, progress=None, cancel=None, tags=None):
        return self.open().extract(jobs, progress, cancel, tags)
