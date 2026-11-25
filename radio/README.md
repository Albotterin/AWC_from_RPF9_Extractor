# AWC Radio

**The game's radio stations as a live web radio – on the PC and on Android.**

[← Project overview](../README.md) · 🇩🇪 [Deutsche Version](README.de.md) · 📊 [Station progress](docs/RADIO-PROGRESS.md) · 📄 [Station notes](docs/STATIONS.md) · ⬇️ [**Download (Releases)**](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) · 🎛 [Audio Extractor](../extractor/README.md)

![AWC Radio](docs/screenshots/06-radio.png)

---

## Table of contents

1. [Start](#start)
2. [Where the stations come from](#where-the-stations-come-from)
3. [Listening](#listening)
4. [Stations and progress](#stations-and-progress)
5. [AWC Radio for Android](#awc-radio-for-android)
6. [Building](#building)
7. [Project layout](#project-layout)
8. [Legal notice](#legal-notice)
9. [Credits and license](#credits-and-license)

## Start

`awc_radio.py` (or `Start AWC-Radio.bat` / `AWC-Radio.exe`) is a small web-radio style player on top of the
[extractor](../extractor/README.md)'s code. On the first start you pick the game's `audio.rpf` and its texture file (`data\ttdb\ui\ttdb_ui.ttpf`). The app
reads the archive once (the same scan cache as the extractor; several minutes the first time) and then lists every
station with its logo.

* **Windows program:** unzip the [release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest), put `ffplay.exe` next to `AWC-Radio.exe` (or into your `PATH`) and start it.
* **From source:** Python 3 with tkinter, Pillow for the logos (`pip install pillow`). Keep the folders `radio/` and
  `extractor/` next to each other, then run `python radio/awc_radio.py` or double-click `Start AWC-Radio.bat`.

![AWC Radio setup](docs/screenshots/07-radio-setup.png)

## Where the stations come from

The game's own audio config (the zlib part at the start of `audio.rpf`) defines each
radio station with its complete playlist, including titles and artists, so the music stations and the two **talk radio**
stations (**WKTT** and **The Wipe**) are read straight from there. The song list of the public credits page is used for
the spelling of the names and for the club DJ sets and mixes, which are still matched by their names. See
[docs/STATIONS.md](docs/STATIONS.md).

## Listening

Click a station to tune in: its songs play one after the other, and after every 2-4 songs a short jingle is played. On
talk radio an "item" is a whole show episode (its parts joined, 10-23 minutes). The programme is calculated from the
**UTC clock** (a new order every UTC day, jingles included), so every station keeps "running" in the background: switch
to another station and back and you land where the first one is by now. Everybody with the same game files and the same
clock hears the same song at the same second (accurate to about a second; the one song that would cross midnight UTC is
cut). Neighbouring songs and jingles are **crossfaded** for 3 seconds (shorter around short jingles), at the same moment
for everybody. There is no skip button - like on a real radio; after a pause, play resumes live. Later starts are
instant. **Jingles** are the short recordings (idents and DJ talk) that only this station uses; for the club DJ sets they
are an educated guess. The adverts that several stations share are not played. Needs `ffplay` like the extractor.

## Stations and progress

792 of 809 songs the credits list for the music stations (97.9 %) are found, and all 15 talk show episodes;
see [station progress](docs/RADIO-PROGRESS.md) for every station.
Long DJ mixes are played as stations too: **The Vault Radio**, extra "(Mix)" stations for Radio On-U, Worldwide FM and The Sniper Of La Perle, and **Clubmixes** (the club and DJ-venue mixes that cannot be assigned to a station yet). While a mix plays, the title of the current part is shown (from the markers in the mix; the part is only named where the marker points to a known song).

All numbers per station: [station progress](docs/RADIO-PROGRESS.md).

![Stations, songs and length](docs/screenshots/awc_radio_stations_table.png)

## AWC Radio for Android

The phone app plays the same live programme as the PC: same UTC clock, same order, same crossfades. It is two files:

* **`AWC-Radio.apk`** - the app, from the [release page](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest). The same file for everybody, no game data
  inside. Android 7.0 or newer, English and German. It keeps playing in the background with a media notification,
  lock-screen and headset buttons (previous / next station, pause) and has a sleep timer. **Android Auto** is supported: the stations show up as a grid with their logos, the player screen shows the song and station, and voice commands ("play Radio On-U on AWC Radio") work. Because the app does not come from the Play Store, switch on *Unknown sources* in Android Auto's developer settings first.
* **A station pack (`.dat`)** - made on the PC: in AWC Radio click **Android pack...**, tick the stations and save. It holds
  the songs of those stations in the game's own quality (about 128 kbit/s, not re-encoded; about 4 GB for all 30 stations),
  their logos and the programme. Copy it to the phone, start the app and pick the file once; the songs are played straight
  out of it.

The pack contains game audio: for your own use only, do not share it. Without a pack the app is empty.
`python awc_radio.py --export-pack out.dat [--stations "Name" ...]` writes a pack without opening a window.
The source and the build scripts of the app are in `android/`:
`python android\build_apk.py <Android SDK folder>` builds the APK (Android SDK and a JDK needed),
`python android\test\run_tests.py` runs the tests on the PC.

## Building

`build_exe.bat` (in this folder) installs PyInstaller if needed and creates `dist\AWC-Radio.exe`.

## Project layout

```
radio/
  awc_radio.py          The radio program (window, schedule, playback)
  awc_gamedata.py       Reads the radio stations and their playlists from the game's audio config
  awc_android.py        Station pack (.dat) writer for the Android app, and its dialog
  android/              The Android app: Java source, build_apk.py, make_logo.py, tests
  Start AWC-Radio.bat   Launcher
  build_exe.bat         Builds dist\AWC-Radio.exe with PyInstaller
  docs/STATIONS.md      How the station definitions are stored in the audio config
  docs/RADIO-PROGRESS.md  Found songs and shows per station (also in German)
  docs/screenshots/     Images used in this README
```

The archive scan, the AWC reader, the credits list and the logos are the modules of the [extractor](../extractor/README.md).

## Legal notice

* This repository contains **only source code and documentation**. It contains **no game files, no archives
  and no extracted audio**, and it never will.
* You need to supply your own files, from a game you legally own. What you do with extracted audio is your
  responsibility – the audio is subject to the copyright of its owners. Personal, non-commercial analysis and
  archiving is one thing; redistributing it is generally not allowed.
* This project is **not affiliated with, endorsed by or connected to** Rockstar Games, Take-Two or any other
  publisher. All trademarks belong to their respective owners.

## Credits and license

The layout of the game's audio config follows the notes of [**Leonida**](https://github.com/ItsNotRexed/Leonida); its
texture decoder is used for the optional station logos. The Android app's launcher icon was drawn with the fan-made
typeface *GTA Art Deco* (not part of this repository). Playback uses [FFmpeg](https://ffmpeg.org/).

[MIT](../LICENSE) – applies to the source code in this repository only, not to any game data.
