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
3. Locate the chunk table (see below) to compute the AWC's length (`max(offset + size)` over its chunks; used for grouping).
   Every `ADAT` hit is tested, also those inside the computed range of an earlier AWC: payload bytes fail the header
   check or open as nothing, but skipping the range wrongly skipped 284 real music containers (found by comparing with
   an unskipped scan; the cache version was raised to 5 for that).
4. Try to open the candidate as a **music container**. If that fails (other AWC kind, damaged) it is only counted.
5. Results (`offset`, `length`, streams, estimated durations) are cached as JSON in
   `%APPDATA%\AWC-Tool\cache\<sha1 of path>.json`, keyed by file size and mtime.

Example: a 28.5 GB archive → 6,636 valid AWC headers, 3,008 music containers, 3,116 streams, ~6 minutes
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

In music containers (low flag byte `0x25`) the header is: `u16` first chunk per stream, `u32` × (count + 1), `u32`
stream IDs, then the chunk table – at `20 + 10 × count`. With an **odd** stream count the table does not lie on a 4-byte
step, so the tool then reads it at exactly that place (40 containers of the sample archive).

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

Observed in the sample archive: the 2,968 music AWCs an earlier scan found lie in 47 groups between 0.08 GB and 26.4 GB (2,849 of 2,967
transitions have less than 4 KiB of padding); the boundaries between the groups are mostly one non-music AWC or a gap
of up to ~24 MB. Whether a group really is one directory (e.g. one radio station) can't be proven without the
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
| `0x08DBA0F8` | `dj` | 2,728 | in songs four per bank: the windows where the DJ may talk over the intro and the outro (start, end, start, end) |
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

## 8. Speech containers (DJ lines)

The DJ lines of the radio stations lie between the music banks in AWCs of another layout: one file holds many short
lines, each on its own. The music scan skips them (no `0x48` chunk); `awc_speech.py` reads them for the view by radio
station.

```
0x00  char[4]  "ADAT"
0x04  u16      version                  (1)
0x06  u16      flags                    (low byte 0x02)
0x08  u32      number of lines
0x0C  u32      end of the headers
0x10  u32[lines]                        bits 29-31: number of chunks of the line, bits 0-28: stream ID
...   u64[sum of the chunk numbers]     chunk entries as in section 2: those of line 0, then of line 1, ...
```

Chunks used per line:

| Tag | Meaning |
|---|---|
| `0x76` | format: `u32` samples at +0, `u16` sample rate at +8, `u8` codec at +28 (`0x0B` = Opus) |
| `0x55` | audio: units of 0x800 bytes with `[u16 length, Opus packet]*` as in section 4; mono |

Other chunks of a line (`0x36`, the seek table `0xA3`) are not needed. Only containers with exactly this layout and the
Opus codec are read. In the sample archive the radio stations have 3,417 such lines.

**Names.** The stream ID is the `joaat` hash of the line's name cut to 29 bits. Names found this way:
`dj_intro_<song>_NN`, `dj_outro_<song>_NN` (`<song>` as the audio config writes the title), `dj_intro_NN`,
`dj_outro_NN`, `dj_to_ads_NN`, `dj_to_news_NN`. A container belongs to the station whose songs its lines announce, else
to the station around it in the archive.

## 9. The score (interactive music)

The score is described in a metadata file of the audio config and played from **sound banks** (`awc_score.py`).

**Sound banks** are `ADAT` containers with flags `0xFF21` / `0xFF61`:

```
0x10  u32 (only with flag 0x40)
...   u16[count]       first chunk of every stream
...   u32[count + 1]
...   u32[count]       stream IDs
...   u64[]            chunk table (as in section 2); per stream 0x76 format and 0x55 data
```

The format chunk holds `u32` samples (all channels together) at +0, `u16` rate at +8, the codec at +28 and the channel
count at +29; the data is laid out like the speech lines of section 8.

**Music objects** (first byte = type): a *project* (95) points to a *name list* (103: `u16` count, entries of a 64-byte
name and a `u32` number). A *section* (104) holds tempo (`f32` at 12), beats per bar, its number (24), its project (32)
and per instrument layer (stem 105, with the layer's name) a *clip* (107) or nothing (the layer stops). A *follow action*
(109) says after how many beats the music goes on with which section; a clip can have one too and hand over to another
clip. A clip leads to a sound of the sound file, and that to a bank stream. Bit 2 of a clip's second byte marks a loop.

A piece starts at a section without the `FA_` prefix and follows the actions until the music would repeat or the next
event is needed. The mix lets a one-shot ring until a later section gives its layer something new, and cuts silence at
the end.

The group of the **places** is not interactive: its pieces are finished recordings of music AWCs, played by station
objects that are no radio station (see the [station notes](../../radio/docs/STATIONS.md#places-with-music)). They are
copied as they are.

## 10. Titles (text tables in `data.rpf`)

The texts of the game lie in `data.rpf`, next to the audio archive, as ordinary zlib streams (nothing is decrypted; the
table of contents of the archive is not read, the streams are found by scanning). A text table is a "resource" cut into
pages, one stream each, 16 byte aligned; pointers inside it are `0x50000000` + offset.

| Where | What |
|---|---|
| page 1, offset 0 / 8 / 16 | `u64` 0, `u64` pointer to the root, `u32` `0x50524430` |
| records near the root | `u32` flags, `u32` size, `u64` pointer: flags `0x101` the slots of a hash table, `0x10` the block of null terminated strings |
| slot (16 bytes) | `u32` key, `u32` 0, `u8` 1 (used), `u16` 0, `u32` number of the string (at byte 11), `u8` 0 |

The radio's table has the keys `joaat("<n>s")` (title) and `joaat("<n>a")` (artist), where `<n>` is the number of the
`trackid` marker (section 7) a song, a podcast episode or a part of a mix carries. There is one table per language; the
first one in the file is the English one. Two podcast episodes carry no marker; their numbers follow from the episodes
around them. Read by `awc_texts.py`.

## 11. Not covered

* Sound-effect AWCs and other non-music kinds (chunk `0xFA` "sfx header", Vorbis, MIDI `0x68`, …).
* Codecs other than `0x0B`: PCM, IMA/MS-ADPCM, XMA2, MPEG, Vorbis, ATRAC9, DSP, other Opus variants.
* The RPF9 table of contents (names, directory structure).
* Writing/repacking archives.

The radio station definitions in the game's audio config are described in
[the station notes of AWC Radio](../../radio/docs/STATIONS.md).
