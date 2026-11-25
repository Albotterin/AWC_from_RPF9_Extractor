"""A small texture reader for exactly one job: the radio station logos in the game's UI texture pool (ttdb_ui.ttpf).

It handles only what these logos use: single-level 2D textures in BC1 or BC7, stored in zlib (or plain) chunks and
arranged in 4 KiB tiles. Nothing is decrypted, and other textures are refused. Pillow turns the BC blocks into pixels.

Layout (little endian), as observed in the pool. It was worked out with the help of the texture reader and notes of the
Leonida project (GPL-3, by ItsNotRexed); the code here is written anew.


    "RDS5", u32 n, u64 virtual size, 2 x u32, (n-1) x u64 chunk table
    chunk entry: bits 0-25 file offset / 256, 26-29 codec (0 plain, 8 zlib), 30-51 virtual address / 4096,
                 52-63 padding; a chunk ends where the next entry starts
    at the next multiple of 256: "ETPF", u32 version, u32 textures, u32 size of the records
    records: textures x 16 bytes (descriptor), then textures x u64 (asset key)
    descriptor a, b, c, d: width-1 = a bits 0-13, height-1 = a bits 14-27, DXGI format = b bits 3-9,
                           virtual address / 4096 = c bits 0-20

The order of the blocks inside a 4 KiB tile is the "standard swizzle" of the GPU (AMD GFX10 SW_4KB_S, see Mesa's
addrlib).
"""
import io
import mmap
import struct
import zlib

BLOCK_BYTES = {71: 8, 72: 8, 98: 16, 99: 16}             # BC1, BC1 sRGB, BC7, BC7 sRGB
TILE = 4096
# address bits of a block inside its tile, from the lowest bit above the block size upwards: ("x"|"y", coordinate bit)
SWIZZLE = {8: "x0 y0 y1 x1 x2 y2 x3 y3 x4", 16: "y0 y1 x0 x1 y2 x2 y3 x3"}
ETPF_VERSION = 634
MAX_SIDE = 1024


class LogoError(ValueError):
    pass


class TexturePool:
    def __init__(self, path):
        self._file = open(path, "rb")
        self.data = None
        try:
            self.data = mmap.mmap(self._file.fileno(), 0, access=mmap.ACCESS_READ)
            self._read_tables()
        except (struct.error, ValueError) as e:
            self.close()
            raise LogoError("not a texture pool this reader understands: %s" % e) from e

    def close(self):
        if self.data is not None:
            self.data.close()
        self._file.close()

    def __enter__(self):
        return self

    def __exit__(self, *_exc):
        self.close()

    def _read_tables(self):
        d = self.data
        if d[:4] != b"RDS5":
            raise ValueError("no RDS5 header")
        n = struct.unpack_from("<I", d, 4)[0]
        if not 3 <= n <= 1_000_002 or 24 + (n - 1) * 8 > len(d):
            raise ValueError("chunk table")
        table = struct.unpack_from("<%dQ" % (n - 1), d, 24)
        self.chunks = []                                  # (virtual address, size, file offset, stored size, codec)
        for here, nxt in zip(table, table[1:]):
            offset, end = (here & 0x3FFFFFF) * 256, (nxt & 0x3FFFFFF) * 256
            address, stop = ((here >> 30) & 0x3FFFFF) * 4096, ((nxt >> 30) & 0x3FFFFF) * 4096
            self.chunks.append((address, stop - address, offset, end - offset - (here >> 52), (here >> 26) & 15))
        head = (24 + (n - 1) * 8 + 255) & ~255
        magic, version, count, size = struct.unpack_from("<4sIII", d, head)
        if magic != b"ETPF" or version != ETPF_VERSION or not 1 <= count <= 250_000 or size < count * 24 \
                or head + 16 + size > len(d):
            raise ValueError("texture records")
        records = head + 16
        keys = struct.unpack_from("<%dQ" % count, d, records + count * 16)
        self.index = {key: records + 16 * i for i, key in enumerate(keys)}        # asset key -> descriptor offset

    def _read(self, address, size):
        """`size` bytes of the pool's virtual address space."""
        out = bytearray(size)
        done = 0
        for start, length, offset, stored, codec in self.chunks:      # a handful of reads: a linear search is enough
            if start + length <= address or start >= address + size:
                continue
            if stored < 1 or offset + stored > len(self.data) or length > 64 << 20:
                raise LogoError("chunk outside the file")
            raw = self.data[offset:offset + stored]
            if codec == 8:
                raw = zlib.decompressobj().decompress(raw, length)
            elif codec != 0:
                raise LogoError("compression %d is not supported" % codec)
            if len(raw) != length:
                raise LogoError("chunk has the wrong length")
            lo, hi = max(address, start), min(address + size, start + length)
            out[lo - address:hi - address] = raw[lo - start:hi - start]
            done += hi - lo
        if done != size:
            raise LogoError("texture is not completely stored")
        return bytes(out)

    def image(self, asset_key):
        """The texture with this asset key as a Pillow image (RGBA), or None if the pool does not have it."""
        at = self.index.get(asset_key)
        if at is None:
            return None
        a, b, c, _d = struct.unpack_from("<4I", self.data, at)
        width, height = (a & 0x3FFF) + 1, ((a >> 14) & 0x3FFF) + 1
        fmt, mips = (b >> 3) & 0x7F, (a >> 28) + (b & 7)
        size = BLOCK_BYTES.get(fmt)
        if size is None or mips != 1 or not (64 <= width <= MAX_SIDE and 64 <= height <= MAX_SIDE) \
                or width % 4 or height % 4:
            raise LogoError("not a logo texture (format %d, %dx%d, %d levels)" % (fmt, width, height, mips))
        across, down = width // 4, height // 4
        order = SWIZZLE[size].split()
        tile_w = 1 << sum(1 for bit in order if bit[0] == "x")
        tile_h = 1 << sum(1 for bit in order if bit[0] == "y")
        columns, rows = -(-across // tile_w), -(-down // tile_h)
        tiled = self._read((c & 0x1FFFFF) * TILE, columns * rows * TILE)
        linear = untile(tiled, across, down, size, order, tile_w, tile_h, columns)
        from PIL import Image  # noqa: PLC0415
        image = Image.open(io.BytesIO(dds(width, height, fmt, linear)))
        return image.convert("RGBA")


def untile(tiled, across, down, size, order, tile_w, tile_h, columns):
    """Blocks in tile order -> blocks row by row."""
    def spread(value, axis):
        return sum(((value >> int(bit[1])) & 1) << n for n, bit in enumerate(order) if bit[0] == axis)
    in_x = [spread(x, "x") * size for x in range(tile_w)]
    in_y = [spread(y, "y") * size for y in range(tile_h)]
    out = bytearray(across * down * size)
    for y in range(down):
        row = (y // tile_h) * columns
        for x in range(across):
            src = (row + x // tile_w) * TILE + in_x[x % tile_w] + in_y[y % tile_h]
            dst = (y * across + x) * size
            out[dst:dst + size] = tiled[src:src + size]
    return bytes(out)


def dds(width, height, fmt, blocks):
    """A DDS file (DX10 header) around the block data, so that Pillow can decode it."""
    fmt = {72: 71, 99: 98}.get(fmt, fmt)                  # sRGB variants hold the same bytes
    header = struct.pack("<4s7I44x", b"DDS ", 124, 0x81007, height, width, len(blocks), 0, 0)
    pixel_format = struct.pack("<II4s20x", 32, 4, b"DX10")
    caps = struct.pack("<I16x", 0x1000)
    dx10 = struct.pack("<5I", fmt, 3, 0, 1, 0)
    return header + pixel_format + caps + dx10 + blocks
