# AWC / RPF9 format notes

Technical notes on what this tool relies on. Everything here was derived from sample files and from the
open-source [vgmstream](https://github.com/vgmstream/vgmstream) implementation (`src/meta/awc.c`,
`awc_streamfile.h`); it is **not** an official specification, and it only covers what the tool needs
(music containers with the Opus codec). All integers are **little-endian**.

## 1. RPF9 archive

The archives this tool was developed against start with the bytes `39 46 50 52` (`"9FPR"`, i.e. the 32-bit value
`0x52504639`), the same scheme as RPF7's `0x52504637` (`"7FPR"`), followed by a header whose table of contents does
not look like readable RPF7 data (it appears to be encrypted/obfuscated). **The table of contents is not parsed.**

What the tool relies on instead: the AWC files are stored **uncompressed and unencrypted** inside the archive, each
starting with the magic `ADAT`. So the archive can be scanned for AWC headers.

### Scanner (`awc_core.scan_container`)

1. Read the file in 64 MiB chunks (3 bytes of overlap so a magic at a chunk boundary isn't missed).
2. For every occurrence of `ADAT`, read up to 64 KiB at that offset and validate the header:
   * `version == 1`
   * `1 <= stream_count <= 65536`
   * `0x10 <= info_offset <= 0xFFF0`
3. Locate the chunk table (see below) to compute the AWC's length (`max(offset + size)` over its chunks), then
   skip every other `ADAT` hit inside that range (they are payload bytes, not new files).
4. Try to open the candidate as a **music container**. If that fails (other AWC kind, damaged) it is only counted.
5. Results (`offset`, `length`, streams, estimated durations) are cached as JSON in
   `%APPDATA%\AWC-Tool\cache\<sha1 of path>.json`, keyed by file size and mtime.

Example: a 28.5 GB archive → 4,153 valid AWC headers, 2,684 music containers, 2,700 streams, ~6 minutes
(disk-bound).

## 2. AWC header

```
0x00  char[4]  "ADAT"
0x04  u16      version            (1)
0x06  u16      flags              (high byte 0xFF = per-stream chunk indices are present)
0x08  u32      stream count
0x0C  u32      info offset        (size of the header; data/chunks start at or after this)
0x10  u16[stream count]           chunk start index per stream (when flags >> 8 == 0xFF)
...   u32[stream count]           stream info: bits 0-28 stream ID, bits 29-31 chunk count
...   u64[]                       chunk table
```

### Chunk table entries

Each entry is a `u64`:

| Bits | Meaning |
|---|---|
| 0–27 | **offset** of the chunk, relative to the start of the AWC |
| 28–55 | **size** of the chunk |
| 56–63 | **tag** (chunk type) |

Tags seen in music containers:

| Tag | Meaning |
|---|---|
| `0x48` | stream format ("music header") |
| `0x55` | data (the audio blocks) |
| `0xA3` | block-to-sample seek table (one `u32` per block) |
| `0xBD` | events / markers (4 × `u32` per event: type hash, params hash, timestamp, flags) |

The tool does not interpret the stream-info/chunk-index arrays exactly; it **scans the header (4-byte aligned) for
`u64` values with a known tag and an offset behind the header**. That is enough for the layouts seen so far, and it
is what makes the format detection cheap. A file without both `0x48` and `0x55` is not treated as a music container.

## 3. Stream format chunk (`0x48`)

```
0x00  u32   block count
0x04  u32   block size            (0x80000 = 512 KiB in all samples seen)
0x08  u32   channel count         (= number of independent streams)
0x0C  channel entries, 0x10 bytes each:
        +0x00  u32  stream ID     (hash; also appears in the stream-info array)
        +0x04  u32  sample count  (counts interleaved channels – about 2x the real length for stereo)
        +0x08  s16  headroom
        +0x0A  u16  sample rate   (48000)
        +0x0C  u8   codec         (0x0B = Opus)
        +0x0D  3 bytes (02 ff ff)
```

"Channels" here are **independent streams**: a 3-channel AWC holds three separate stereo Opus streams
(e.g. stems of one piece), not one 6-channel signal.

## 4. Data chunk (`0x55`) – blocks

The data chunk is a sequence of blocks of `block size` bytes (the last one may be shorter). Each block begins with a
header and is followed by the audio of every stream:

```
block header
  per stream, 0x18 bytes:
    +0x00  s32  start entry     (-1)
    +0x04  s32  entry count N    -> N units of 0x800 bytes for this stream in this block
    +0x08  s32  samples to skip  (0)
    +0x0C  s32  samples in this block for this stream (interleaved count)
    +0x10  8 bytes (zero)
  per stream: u32 x N           seek table – cumulative sample counts
  zero padding up to the next 0x800 boundary
audio, stream after stream:
  N units of 0x800 bytes each
```

### Units and Opus packets

Every 0x800-byte unit holds several Opus packets, each prefixed with its length:

```
repeat:  u16 length | <length> bytes Opus packet
until:   length == 0 (or end of unit) -> rest of the unit is zero padding
```

In the samples seen, each unit contains 6 packets of 320 bytes (CELT fullband, 20 ms, **stereo**, TOC byte `0xFC`,
CBR 128 kbit/s). The seek-table steps are therefore 11,520 = 6 × 960 samples × 2 (interleaved channel count).

The packets are **plain, standard Opus** – no custom bitstream – which is why they can be wrapped into Ogg
without re-encoding.

## 5. Writing Ogg Opus (`awc_core.OggOpusWriter`)

* Page 1: `OpusHead` – version 1, channel count from the stereo bit of the first packet's TOC, pre-skip 312,
  input rate 48000, gain 0, mapping family 0.
* Page 2: `OpusTags` (vendor string only).
* Audio pages: up to 40 packets each; the granule position is the cumulative number of 48 kHz samples,
  derived from each packet's TOC byte (config + frame count).
* The last page carries the EOS flag.
* Ogg CRC32 (polynomial `0x04C11DB7`, not reflected, init 0) is computed with `zlib.crc32` by bit-reversing the
  input bytes and the result – about 30× faster than a table loop in pure Python.

## 6. Grouping (a heuristic, not part of the format)

Names and directories live in the unreadable RPF9 table of contents, so `awc_core.add_groups()` clusters the music AWCs
by their position in the archive:

1. sort the AWCs by offset;
2. start a new group when `offset - previous_end >= 0x10000` **or** the running AWC number (ordinal among *all* valid
   `ADAT` headers found, music or not) is not `previous + 1`, i.e. a non-music AWC sits in between.

Observed in the sample archive: the 2,684 music AWCs form one almost gap-free region (2,587 of 2,683 transitions have
less than 4 KiB of padding); the 28 boundaries inside it are mostly exactly one non-music AWC plus a gap of up to
~24 MB, giving 29 groups. Whether a group really is one directory (e.g. one radio station) can't be proven without the
table of contents - it is an inference from the layout.

## 7. Markers (chunk `0xBD`)

Many music AWCs carry a `0xBD` chunk: a plain array of 16-byte events, no header.

```
+0x00  u32  type      (joaat of a lower-case name)
+0x04  f32  value
+0x08  u32  time      (samples at 48 kHz, from the start of the stream)
+0x0C  u32  flags     (0 in all samples)
```

Event types seen in the sample archive (the name resolves by hashing it with the same joaat as the stream IDs):

| Type | Name | Events | Meaning (inferred) |
|---|---|---:|---|
| `0xE89AE78C` | `beat` | 16,207 | beat grid (gaps of a few seconds) |
| `0xA6D93246` | `trackid` | 981 | a track starts here; `value` is a track number |
| `0x08DBA0F8` | `dj` | 2,728 | DJ talk |
| `0xDA8F5F53` / `0x5D40B28D` | `intro` / `outro` | 773 / 766 | intro / outro of a segment |
| `0x0C801D6C` / `0x3A423FE8` | `low` / `high` | 1,472 / 2,187 | energy level |

(the names are guesses that hash to the type; the meanings are inferred from where the events fall).

**`trackid`**: a standalone track (844 AWCs in the sample archive) carries exactly one, at time 0. The five long
station mixes (40-120 minutes, one stream each) carry 22-41 of them, one per track played, at the time the track
starts. In three mixes the markers fall on strong spectral changes of the audio (67-81 % of the markers have one of
the strongest 10 % changes within 3 s, against about 20 % at random times); `beat` events do not (28 %).
The `value` of a marker in a mix equals the `value` of the standalone track with the same number in the same group
(archive cluster, section 6): 31 of 40 markers in one mix, 21 of 21 in another. The segment length is not the length
of that standalone track, so a segment is the part of the track that the mix plays, or the marker is not the start.
The tool shows the markers as chapters under a mix and can split the exported mix at them (`ffmpeg -c copy`).

## 8. Not covered

* Non-music AWCs (chunk `0xFA` "sfx header", Vorbis `0x76`/`0x7F`, MIDI `0x68`, …).
* Codecs other than `0x0B`: PCM, IMA/MS-ADPCM, XMA2, MPEG, Vorbis, ATRAC9, DSP, other Opus variants.
* The RPF9 table of contents (names, directory structure).
* Writing/repacking archives.
