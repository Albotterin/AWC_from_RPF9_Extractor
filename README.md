# AWC from RPF9 Extractor

**Find, play and extract music streams from RAGE-engine `.rpf` archives (RPF9) and `.awc` files – with a simple GUI.**

🇩🇪 [Deutsche Version](README.de.md) · 📄 [Technical format notes](docs/FORMAT.md) · ⬇️ [**Download (Releases)**](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest)

![Main window](docs/screenshots/01-main.png)

---

## Table of contents

1. [What does it do?](#what-does-it-do)
2. [Legal notice – please read](#legal-notice--please-read)
3. [Features](#features)
4. [Screenshots](#screenshots)
5. [Requirements & installation](#requirements--installation)
6. [Usage](#usage)
7. [Output formats and file naming](#output-formats-and-file-naming)
8. [How it works (short version)](#how-it-works-short-version)
9. [Grouping: which files belong together?](#grouping-which-files-belong-together)
10. [Using the code as a library](#using-the-code-as-a-library)
11. [Building a standalone `.exe`](#building-a-standalone-exe)
12. [Languages](#languages)
13. [Limitations](#limitations)
14. [Troubleshooting](#troubleshooting)
15. [Project layout](#project-layout)
16. [Credits](#credits)
17. [License](#license)

---

## What does it do?

Some games built on Rockstar's RAGE engine store their audio in big `audio.rpf` archives. The music is kept in
**AWC** containers (`ADAT` header) inside those archives. The newer **RPF9** archives can't be opened by the usual
RPF7 tools, and their table of contents is not readable, so the AWC files can't simply be looked up by name.

This tool takes a different route:

1. **It scans the raw archive for AWC headers** (`ADAT`) – no table of contents needed. A 28 GB archive takes
   about 6 minutes on a normal disk; the result is cached, so the next scan is instant.
2. **It recognises the music containers** (multichannel AWC with a *stream-format* chunk) and reads their
   stream IDs, codec, channel layout and duration.
3. **It decodes the container layout** (blocks → per-channel entries → Opus packets) and **rewrites the original
   Opus packets 1:1 into standard Ogg Opus files** – there is *no re-encoding*, so the audio is bit-identical to what
   the game plays.
4. Optionally it **converts to FLAC, WAV or MP3** with ffmpeg, and it can **play any stream directly** from the
   archive, with seek and volume control.
5. Because the archive's table of contents (and with it every file name) is unreadable, it **groups the AWCs by their
   position in the archive** – files of one folder are stored back to back, so a group is typically one radio station
   or one folder. You can **name the groups yourself** (right-click), and the names become export folder names.

You can point it at a single `.awc`, a single `.rpf`, or a **whole folder** – it searches all subfolders for
every `.rpf` and `.awc` file and lists all music streams it finds.

> Example run on a 28.5 GB `audio.rpf`: 4,153 AWC headers found, 2,684 of them music containers with
> 2,700 streams (Opus, 48 kHz) ≈ 90 hours of audio ≈ 4.8 GB as `.opus` files.

## Legal notice – please read

* This repository contains **only source code and documentation**. It contains **no game files, no archives
  and no extracted audio**, and it never will.
* You need to supply your own files, from a game you legally own. What you do with extracted audio is your
  responsibility – the audio is subject to the copyright of its owners. Personal, non-commercial analysis and
  archiving is one thing; redistributing it is generally not allowed.
* This project is **not affiliated with, endorsed by or connected to** Rockstar Games, Take-Two or any other
  publisher. All trademarks belong to their respective owners.

## Features

| | |
|---|---|
| 🔍 **Archive scanning** | Header scan of `.rpf` files of any size (64 MiB read chunks, strict validation, skips over found AWCs). |
| 📂 **Folder mode** | Choose a folder – all subfolders are searched for `*.rpf` and `*.awc`. |
| 🌳 **Tree view** | Container → group → AWC → stream, with stream ID, codec, stereo/mono, duration and size. |
| 🗂 **Groups** | AWCs are clustered by their position in the archive (≈ one folder / radio station each). Right-click a group → **Rename group…**; the name is used for the export folder. |
| ▶️ **Player** | Play any stream straight from the archive (play/pause/stop, seek bar, volume). Uses `ffplay`. |
| 💾 **Extraction** | Opus (lossless remux), FLAC, WAV (16-bit PCM) or MP3 (320 kbit/s). Select streams, whole AWCs or whole containers. |
| ⏱ **Duration filter** | "Only streams longer than N seconds" – skips the many short snippets. |
| 🛡 **Safety checks** | Estimates output size, warns when the disk is too small, asks before big jobs. |
| ⚡ **Fast** | Pure-Python Ogg muxer with a zlib-based CRC: ~100 MB of Opus in about a second. |
| 🧠 **Scan cache** | Scan results are cached in `%APPDATA%\AWC-Tool\cache` (can be disabled). |
| 🌍 **Multilingual UI** | English and Deutsch, switchable at runtime; easy to add more. |
| ❌ **Cancelable** | Scans and extractions can be cancelled; partial files are removed. |

## Screenshots

**Browsing the music containers of an archive.** Multi-stream AWCs (here: 3 stereo streams) expand into their
streams. The selection can be extracted or played.

![Main window](docs/screenshots/01-main.png)

**Groups.** Without a readable table of contents the tool clusters the AWCs by where they sit in the archive.
Each *Group NN* is typically one folder / radio station. Right-click a group to give it a name (here:
"My station name" – the name is stored on your PC and used for the export folder).

![Groups](docs/screenshots/05-groups.png)

**Playing a stream** directly from the archive – here at 44:38 of a two-hour stream, with the seek bar.

![Player](docs/screenshots/02-player.png)

**Extracting** a multi-stream AWC to FLAC, with progress bar, status line and a *Cancel* button.

![Extraction in progress](docs/screenshots/03-extracting.png)

**Language switch** – the same window in German; the whole UI (including tree rows and status line) changes
instantly.

![German UI](docs/screenshots/04-german.png)

## Requirements & installation

* **Windows** (the GUI and the packaged `.exe` target Windows; the core modules are plain Python and portable).
* **Python 3 with tkinter** (included in the standard python.org installer; developed and tested with 3.14). No `pip` packages are needed.
* **ffmpeg + ffplay** – only needed for *playback* and for *FLAC/WAV/MP3* export.
  Opus extraction works without them. Get ffmpeg from <https://ffmpeg.org/download.html> (for example the
  "full build" from gyan.dev) and either put `ffmpeg.exe` and `ffplay.exe` **next to the program** or into your `PATH`.

```bash
git clone https://github.com/Albotterin/AWC_from_RPF9_Extractor.git
cd AWC_from_RPF9_Extractor
python awc_tool.py
```

On Windows you can also double-click `Start AWC-Tool.bat`.

## Usage

### GUI

1. Click **Scan folder…** and choose a folder containing `.rpf` / `.awc` files (subfolders are included), or use
   **Open files (AWC/RPF)…** for individual files. You can also pass paths on the command line:
   ```bash
   python awc_tool.py "D:\game\audio.rpf"
   python awc_tool.py "D:\game"            # whole folder
   python awc_tool.py --lang=de            # start in German
   ```
2. Wait for the scan. The progress bar shows how much of the archive has been read and how many music AWCs were
   found so far. Results are cached.
3. **Browse** the tree. Double-click a stream to **play** it (the first play of a stream takes a moment because the
   stream is extracted to a temporary cache first – about a second per 100 MB).
4. **Extract**: select streams, AWCs or a whole container (Ctrl/Shift for multiple), choose a **format** and an
   **output folder**, and click **Extract selected**. **Extract all streams** processes everything in the list.
   *Selected → FLAC* and *Selected → WAV* are one-click shortcuts.
5. Use **Only streams longer than (sec.)** to skip short snippets (e.g. `60` keeps only streams of a minute or more).
6. **Name the groups** (optional): right-click a *Group NN* row → **Rename group…** (e.g. after listening to a few
   tracks). Names are stored in `%APPDATA%\AWC-Tool\group_names.json` and become part of the export folder name.
7. **Names** (optional) – the AWC files only contain 32-bit hash IDs, no names:
   * **Read names from game files** hashes the internal names in the game's own audio config (the zlib part at the
     start of `audio.rpf`) and fills in titles for the streams it recognises (roughly a third of the music streams
     in the sample archive; internal titles only, no artists). It also remembers the station names found there and
     offers them in **Rename group…**.
   * **Import names…** reads a CSV/TXT/JSON list (by stream ID or by internal name), **Export name list…** writes
     a template to fill in, and right-click → **Set title / artist…** names a single stream.
   * Named streams get `Artist - Title` file names and Opus/FLAC/MP3 tags on export, plus optional `.m3u8`
     playlists. Names stay on your PC (`%APPDATA%\AWC-Tool\track_names.json`); nothing is shipped with the tool.

### Player controls

| Control | Action |
|---|---|
| ▶ Play / ❚❚ Pause | Start / pause (pausing remembers the position). |
| ■ Stop | Stop and rewind. |
| Seek bar | Drag and release to jump. |
| Volume | Applied when you release the slider. |

### Command line options

```
python awc_tool.py [--lang=en|de] [folder | file.awc | file.rpf ...]
python awc_tool.py --selftest <report.txt> <file.awc>     # diagnostics (used for the built exe)
```

## Output formats and file naming

| Format | Size (stereo, per hour) | Notes |
|---|---|---|
| **Opus** | ≈ 58 MB | Original packets in an Ogg container – **bit-identical audio, no re-encode**. Plays in VLC, foobar2000, ffmpeg, … |
| FLAC | ≈ 600 MB | Lossless *copy of the decoded* audio (the source is lossy Opus). |
| WAV | ≈ 700 MB | 16-bit PCM, 48 kHz. Very large. |
| MP3 | ≈ 140 MB | 320 kbit/s, re-encoded from the decoded audio. |

> The source audio is lossy (Opus, 128 kbit/s stereo). FLAC/WAV don't add quality – they are for compatibility
> with tools that can't read Opus.

Files are written to `<output folder>/<container name>/<group folder>/<awc name>_stream<N>_<STREAMID>.<ext>`,
for example:

```
AWC-Export/audio/group_04/audio_956_at_000000015034A000_stream0_1D7CA6CC.opus
AWC-Export/audio/group_04_My station name/audio_956_at_000000015034A000_stream0_1D7CA6CC.opus   (after naming the group)
```

* `audio` – name of the `.rpf` the AWC was found in (standalone `.awc` files are written directly into the output folder),
* `group_04` – the group (see [Grouping](#grouping-which-files-belong-together)), plus your own name if you gave one,
* `956` – running number of the AWC header in the archive,
* `000000015034A000` – byte offset of the AWC inside the archive,
* `stream0` / `1D7CA6CC` – stream index and the stream's 32-bit ID (a hash; real names are not stored in the AWC).

## How it works (short version)

```
audio.rpf (28 GB)
  └─ scan for "ADAT" ──► candidate offsets ──► strict header check ──► AWC at offset X, length L
        └─ read chunk table: 0x48 stream format · 0x55 data · 0xA3 seek table · 0xBD events
              └─ data chunk = blocks of 512 KiB
                    └─ block: channel table · seek tables · 0x800-byte units of [u16 length | Opus packet]*
                          └─ Opus packets ──► Ogg pages (OpusHead/OpusTags + CRC) ──► .opus
                                                  └─ ffmpeg ──► FLAC / WAV / MP3
```

The full byte-level description is in **[docs/FORMAT.md](docs/FORMAT.md)**.

## Grouping: which files belong together?

The RPF9 table of contents can't be read, so the archive doesn't tell us which AWC belongs to which radio station or
folder. The tool uses a **heuristic based on the physical layout**:

* Files of one folder are stored **back to back**; between two folders there is usually a larger gap (and a
  non-music AWC). A new group starts where the gap to the previous music AWC is **≥ 64 KiB** or where the running AWC
  number jumps (= a non-music AWC sits in between).
* In the sample archive this gave **29 groups** (one of them ~300 files, a few with only 4–7 long pieces).
* Typical group contents: songs (3–5 min), many short voice/jingle snippets, and sometimes a 1–2 hour continuous
  stream. Groups that contain only very short snippets are probably not radio at all.

**What it can't do:** it can't tell you which station a group is (the tool only suggests station names found in
the game config). A group is *probably* one folder, but that is an inference, not a fact. Listen to a few tracks, then name the groups in the tool.
Handy: `Only streams longer than (sec.)` with `90` keeps just the real tracks.

In code: `awc_core.add_groups(items)` sets `item["g"]` (1-based) on the items returned by the scanner.

## Using the code as a library

`awc_core.py` has no GUI dependency:

```python
import awc_core as core

# 1) find all music AWCs in an archive (cached on disk)
items, total_awc, from_cache = core.scan_container_cached(r"D:\game\audio.rpf")
print(len(items), "music AWCs of", total_awc, "AWC headers")

# 2) extract every stream of one AWC to .opus files
ref = core.AwcRef(items[0])
jobs = {s.index: f"out_{s.index}_{s.id:08X}.opus" for s in ref.streams if s.supported}
seconds = ref.extract(jobs, progress=lambda f: print(f"{f:.0%}"))   # -> {stream_index: seconds}
```

## Building a standalone `.exe`

```bat
build_exe.bat
```

This installs PyInstaller (if needed) and creates `dist\AWC-Tool.exe`. Copy `ffmpeg.exe` and `ffplay.exe` next to
it for playback and conversion. (The `.exe` and ffmpeg are intentionally **not** part of this repository.)

> Antivirus tools sometimes flag PyInstaller one-file executables as false positives. If that bothers you, run
> from source instead.

## Languages

The UI language can be switched with the **Language** box (top right) at any time; the choice is stored in
`%APPDATA%\AWC-Tool\settings.json`. Currently: **English** (default) and **Deutsch**.

To add a language, edit `awc_i18n.py`: add an entry to `LANGUAGES` and a table with the same keys to `STRINGS`
(missing keys fall back to English). Pull requests with translations are welcome.

## Limitations

* **Only music containers with the Opus codec (`0x0B`)** are supported. Other AWC kinds (sound effects, speech,
  Vorbis/ADPCM/XMA banks, …) are detected as "not a music container" and skipped.
* The **RPF9 table of contents is not parsed** (it appears to be encrypted/obfuscated), so there are **no real
  file names** – only offsets and stream IDs. The tool relies on the fact that the AWC payloads inside the archive
  are stored uncompressed and unencrypted.
* **Groups are a layout heuristic** (see above), not real folders; names have to be given by you.
* Developed and tested against the AWC files of **one** RPF9 archive. Other games/versions may use different
  codecs or layouts.
* Durations in the list are estimates (marked with `~`) from the block headers; the exact length is known after
  extraction/playback.
* Seeking while playing restarts `ffplay` at the new position (a short gap is normal).
* Playback and FLAC/WAV/MP3 need ffmpeg/ffplay.

## Troubleshooting

| Problem | Fix |
|---|---|
| "ffmpeg missing" warning at start | Put `ffmpeg.exe` and `ffplay.exe` next to the program or into `PATH`. Opus extraction still works. |
| Scanning a big archive is slow | It is disk-bound (≈ 80 MB/s on a hard disk, much faster on an SSD). The result is cached – the second run is instant. |
| I want to rescan | Untick **Use scan cache** (or delete `%APPDATA%\AWC-Tool\cache`). |
| A file says "Not a multichannel music container" | It is an AWC of another kind (SFX/speech) – not supported. |
| Playback has no sound | Check your default audio device; test `ffplay` on its own. |
| Not enough disk space warning | WAV/FLAC are big – use Opus, or filter by duration. |

## Project layout

```
awc_tool.py        GUI (tkinter): tree, player, extraction, language switch
awc_core.py        AWC parser, RPF scanner, Ogg Opus writer (no GUI dependencies)
awc_i18n.py        Translations (English, Deutsch) + settings
awc_names.py       Track/station name store, import/export, tags, playlists
awc_config.py      Reads internal names from the game's audio config
Start AWC-Tool.bat Launcher
build_exe.bat      Builds dist\AWC-Tool.exe with PyInstaller
docs/FORMAT.md     Technical notes on the AWC/RPF9 layout
docs/screenshots/  Images used in this README
```

## Credits

The block layout of AWC music streams was worked out by reading the open-source
[**vgmstream**](https://github.com/vgmstream/vgmstream) code (`awc.c`, `awc_streamfile.h`) and by analysing sample
files; general AWC/RPF knowledge comes from the community, notably
[**CodeWalker**](https://github.com/dexyfex/CodeWalker). Opus is by Xiph.Org / IETF (RFC 6716), Ogg by Xiph.Org
(RFC 3533, RFC 7845). Playback and conversion use [FFmpeg](https://ffmpeg.org/).

## License

[MIT](LICENSE) – applies to the source code in this repository only, not to any game data.
