# AWC from RPF9 Extractor & AWC Radio

**Music from RAGE-engine `audio.rpf` archives: extract the audio, or listen to the game's radio stations – on the PC and on Android.**

🇩🇪 [Deutsche Version](README.de.md) · ⬇️ [**Download (Releases)**](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) · 📱 [Android app](#-awc-radio-app-for-android) · 📊 [Radio progress](radio/docs/RADIO-PROGRESS.md)

| 🎛 AWC Audio Extractor | 📻 AWC Radio |
|:---:|:---:|
| ![AWC Audio Extractor](extractor/docs/screenshots/01-main.png) | ![AWC Radio](radio/docs/screenshots/06-radio.png) |

> ⚠️ **Please read the [disclaimer](DISCLAIMER.md).** No game data is included: you need your own copy of the game.
> Nothing is decrypted, no copy protection is circumvented and nothing in the game is changed.

## Two projects in one repository

| | 🎛 AWC Audio Extractor | 📻 AWC Radio |
|---|---|---|
| **What for** | Find, play and extract every music stream | Listen to the stations like on a real radio |
| **Folder** | [`extractor/`](extractor/README.md) | [`radio/`](radio/README.md) |
| **Programs** | `AWC-Tool.exe` | `AWC-Radio.exe` + `AWC-Radio.apk` (Android) |
| **Guide** | [Extractor guide](extractor/README.md) | [Radio guide](radio/README.md) |
| **Technical notes** | [AWC / RPF9 format](extractor/docs/FORMAT.md) | [Station definitions](radio/docs/STATIONS.md) |

The extractor is the base: it reads the archive and the AWC files. AWC Radio builds on its modules, so keep both
folders next to each other when you run from source.

## Quick start

> The newest [release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) is **v2.4.1**: its Windows programs and app 1.3 still work with
> the single `.dat` pack. For what is described here (v2.5.0: `.radiopak` packs, app 1.5) run and build from source until
> the next release.

1. Download `AWC-Tool-…-win64.zip` from the [latest release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) and unzip it. It holds both Windows programs.
2. Put `ffmpeg.exe` and `ffplay.exe` ([ffmpeg.org](https://ffmpeg.org/download.html)) next to them, or into your `PATH`.
3. Start **`AWC-Tool.exe`** to extract, or **`AWC-Radio.exe`** to listen, and pick the `audio.rpf` of your own game.
   The radio also asks for the texture file `data\ttdb\ui\ttdb_ui.ttpf` (station logos).
4. **On the phone:** install the Android app, write the station packs with **Android packs…** in AWC Radio, copy them
   into one folder on the phone and choose that folder in the app.

From source (Python 3 with tkinter; Pillow for the logos):

```bash
git clone https://github.com/Albotterin/AWC_from_RPF9_Extractor.git
cd AWC_from_RPF9_Extractor
python extractor/awc_tool.py     # the extractor
python radio/awc_radio.py        # the radio
```

## 🎛 AWC Audio Extractor

* Scans `.rpf` archives of any size for audio containers – no table of contents needed.
* Writes the original Opus packets 1:1 into `.opus` files (no re-encoding); optionally FLAC, WAV or MP3.
* Plays every stream straight from the archive.
* Names streams from the game's own config, from the public credits page or from your own lists; station logos optional.
* English and German interface.

➡️ [**Extractor guide**](extractor/README.md)

## 📻 AWC Radio

* **34 stations**, among them the talk stations **WKTT** and **The Wipe** and the game's venues (**Jack of Hearts**, **VYB**,
  **The Vault**, **Thrillbilly Mud Club**), read with their playlists from the game's own audio config.
* A live programme by the UTC clock: every station keeps running, everybody with the same time hears the same song.
* Jingles, DJ talk and crossfades between the songs; long DJ mixes show the title of the current part.
* An [**Android app**](#-awc-radio-app-for-android) plays the same programme on the phone and in the car, and wakes you with it.

➡️ [**Radio guide**](radio/README.md)

## 📱 AWC Radio app for Android

The radio for your pocket, your car and your bedside. **`AWC-Radio.apk`** (version 1.5) plays the same live programme as
the PC: same UTC clock, same order, same crossfades – with station packs from the same version, phone and PC play the same
song at the same time.

| | |
|---|---|
| 🎧 **Listening** | Station logos as a grid, a mini player and a large player in the colour of the station; swipe over the logo to change the station. Keeps playing in the background with a media notification, lock screen, headset and Bluetooth buttons |
| ⏰ **Radio alarm** | Several alarms with weekdays, station, volume, gentle start and snooze; a sunrise screen over the lock screen. If the radio cannot be heard, the phone's own alarm tone rings; alarms survive a restart |
| 🚗 **Android Auto** | The stations as a grid with their logos, song and station on the player screen, voice commands such as *"play Radio On-U on AWC Radio"* |
| 😴 **Sleep timer** | Stops the radio after the time you choose |
| 📦 **Station packs** | One file (`.radiopak`) per station in a folder on the phone: add or update single stations without copying everything again |
| 🔒 **Offline** | The app has no internet permission: everything comes from the packs on the phone |
| 🌍 **Languages** | English and German |
| 📋 **Needs** | Android 7.0 or newer |

**Set it up**

1. Install `AWC-Radio.apk` (from a [release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases), or build it from [`radio/android/`](radio/README.md#awc-radio-for-android)). Allow notifications: the alarm needs them.
2. On the PC, click **Android packs…** in AWC Radio, choose a folder and write the packs (original quality, about 4.2 GB for all 34 stations). Later only new or changed packs are written.
3. Copy the `.radiopak` files into one folder on the phone, start the app and choose that folder once.
4. For Android Auto: in the Android Auto settings tap *Version* about ten times, open the developer settings in the three-dot menu and switch on *Unknown sources*.

**Good to know**

* The APK contains **no game data** and is the same file for everybody. The station packs contain audio and pictures from your own game: for your own use only, do not share them.
* The single `.dat` pack of earlier versions is no longer read.
* Test an alarm on your phone before you rely on it: some manufacturers' battery savers stop apps in the background.
* A newer APK installs over the old one.

## Progress

Status **2026-11-25** (v2.5.0), measured with a 28.5 GB `audio.rpf`.

| Area | State | |
|---|---|---|
| Music containers found by the scan | 2,968, with 2,992 streams (about 96 hours), all of them Opus and extractable | ✅ |
| Songs the credits page lists for the music stations | **792 of 809** found (97.9 %) | 🟢 |
| Talk radio | all 5 shows the credits name, **15 episodes** | ✅ |
| Stations in AWC Radio | 34 (including 3 "(Mix)" stations, 4 venues and "Clubmixes") | ✅ |
| Venues | Jack of Hearts (100 min DJ set), VYB (45 min), The Vault (Club, 84 min), Thrillbilly Mud Club (20 songs) | ✅ |
| Android app | version 1.5: radio alarm, new design, one pack per station, Android Auto | ✅ |
| The Vault Radio | plays as one long mix; its 24 listed songs are not separate files | 🎚 |

![AWC Radio – station progress](radio/docs/radio-progress.svg)

Details for every station: [radio progress](radio/docs/RADIO-PROGRESS.md).

### Still open

- [ ] 17 listed songs without a file: the club DJ sets (Bodega The DJ, DJ Stevie J), two of Worldwide FM, one of Radio On-U
- [ ] The 8 original songs of "Honey FM – Liquid City"
- [ ] The five podcasts the game config names
- [ ] One 61-minute club mix belongs to no track list of the config; it stays in "Clubmixes"
- [ ] App 1.5 (new design, alarm) has been tested on the PC (Robolectric), still little on real phones
- [ ] Extractor: only music containers with the Opus codec; the RPF9 table of contents is not read, so there are no file names

### Milestones

| Version | Date | What was added |
|---|---|---|
| [v1.0.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.0.0) | 2026-11-22 | 🎛 Archive scan, player, lossless extraction |
| [v1.1.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.1.0) | 2026-11-22 | 🎛 Groups by position in the archive |
| [v1.7.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.7.0) | 2026-11-23 | 🎛 Song names and station logos · 📻 first AWC Radio |
| [v1.8.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.8.0) | 2026-11-23 | 📻 Programme by the UTC clock |
| [v1.9.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.9.0) | 2026-11-23 | 📻 Crossfades |
| [v2.0.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.0.0) | 2026-11-23 | 📻 Android app and station packs |
| [v2.1.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.1.0) | 2026-11-24 | 📻 More stations and songs, mix stations |
| [v2.2.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.2.0) | 2026-11-24 | 📻 The Vault Radio, Clubmixes, titles inside mixes |
| [v2.3.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.3.0) | 2026-11-24 | 📻 Android Auto, new app design |
| [v2.4.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.4.0) | 2026-11-25 | 📻 Talk radio and complete station playlists |
| [v2.4.1](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.4.1) | 2026-11-25 | 🎛 📻 Own station logo reader, no download needed |
| v2.5.0 | 2026-11-25 | 📻 One pack per station, app 1.5 with radio alarm and new design, venue stations |

## Repository layout

```
extractor/     AWC Audio Extractor: program, shared modules, format notes, screenshots
radio/         AWC Radio: program, station reader, progress, screenshots
  android/     the Android app (source, build script, tests)
DISCLAIMER.md  what the programs do and do not do, and your duties (DISCLAIMER.de.md in German)
LICENSE        MIT
README.md      this overview (README.de.md in German)
```

## Legal notice – please read

**The full text is in the [disclaimer](DISCLAIMER.md).** In short:

* This repository contains **only source code and documentation**. It contains **no game files, no archives
  and no extracted audio**, and it never will.
* You need to supply your own files, from a game you legally own. What you do with extracted audio is your
  responsibility – the audio is subject to the copyright of its owners. Personal, non-commercial analysis and
  archiving is one thing; redistributing it is generally not allowed. A station pack contains game audio: do not share it.
* The screenshots show station logos and song titles from the game only to illustrate what the programs read from your
  own files.
* This project is **not affiliated with, endorsed by or connected to** Rockstar Games, Take-Two or any other
  publisher. All trademarks belong to their respective owners.

## Credits and license

The AWC block layout was worked out with the help of [**vgmstream**](https://github.com/vgmstream/vgmstream); general
AWC/RPF knowledge comes from the community, notably [**CodeWalker**](https://github.com/dexyfex/CodeWalker). The layout
of the game's audio config follows the notes of the **Leonida** project (ItsNotRexed). Playback and
conversion use [FFmpeg](https://ffmpeg.org/).

[MIT](LICENSE) – applies to the source code in this repository only, not to any game data.
