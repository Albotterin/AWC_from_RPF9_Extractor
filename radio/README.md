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
the spelling of the names and for the DJ sets of Bodega The DJ and DJ Stevie J and the mixes, which are matched by their names.

The config also names what some **places of the game** play: **Jack of Hearts** (a 100-minute set), **VYB** (45 minutes in four
parts) and **The Vault (Club)** get the DJ sets of these clubs, and the **Thrillbilly Mud Club** its playlist of 20 songs. See
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
Long DJ mixes are played as stations too: **The Vault Radio**, extra "(Mix)" stations for Radio On-U, Worldwide FM and The Sniper Of La Perle, and **Clubmixes** (the one club mix that cannot be assigned to a place). While a mix plays, the title of the current part is shown (from the markers in the mix; the part is only named where the marker points to a known song).

All numbers per station: [station progress](docs/RADIO-PROGRESS.md).

The current list of all stations with their content and length: [all stations](docs/RADIO-PROGRESS.md#all-stations).

## AWC Radio for Android

The phone app plays the same live programme as the PC: same UTC clock, same order, same crossfades. It consists of the app
and the station packs:

* **`AWC-Radio.apk`** - the app (version 1.5), the same file for everybody, no game data inside. Android 7.0 or newer,
  English and German. Station grid, mini player and a large player in the colour of the station's logo; background playback
  with a media notification, lock-screen and headset buttons; sleep timer; **Android Auto** (switch on *Unknown sources* in
  its developer settings first, because the app does not come from the Play Store).
* **Radio alarm** - several alarms with weekdays, station, volume, gentle start, snooze and vibration. It rings on the phone's
  alarm volume with a screen over the lock screen. If nothing can be heard from the radio within 10 seconds (pack missing,
  player error, phone not yet unlocked after a restart), the phone's own alarm tone rings. It stops by itself after 30 minutes.
* **Station packs (`.radiopak`)** - one file per station, made on the PC: in AWC Radio click **Android packs…**, choose a
  folder and write them. Each holds the station's songs in the game's own quality (about 128 kbit/s, not re-encoded), its logo
  and its programme data; about 4.2 GB for all 34 stations. Copy them into one folder on the phone and choose that folder in
  the app. New or changed packs are picked up by themselves.

The packs contain game audio and pictures: for your own use only, do not share them. Without a pack the app is empty.
`python awc_radio.py --export-pack <folder> [--stations "Name" ...]` writes packs without opening a window.

The source and the build scripts of the app are in `android/`:
`python android\build_apk.py <Android SDK folder>` builds the APK (Android SDK and a JDK needed).
`python android\test\run_tests.py [pack folder]` tests the playback, alarm and pack logic on the PC (JDK, and `json.jar`, see
the script). `python android\test\run_robo.py <pack folder>` runs the screens and the alarm on the PC with
[Robolectric](https://robolectric.org/) and saves screenshots (the script says which libraries it needs).

## Building

`build_exe.bat` (in this folder) installs PyInstaller if needed and creates `dist\AWC-Radio.exe`.

## Project layout

```
radio/
  awc_radio.py          The radio program (window, schedule, playback)
  awc_gamedata.py       Reads the radio stations and their playlists from the game's audio config
  awc_android.py        Writes one station pack (.radiopak) per station for the Android app, and its dialog
  android/              The Android app: Java source, resources, build_apk.py, make_logo.py, tests
  Start AWC-Radio.bat   Launcher
  build_exe.bat         Builds dist\AWC-Radio.exe with PyInstaller
  docs/STATIONS.md      How the station definitions are stored in the audio config
  docs/RADIO-PROGRESS.md  Found songs and shows per station (also in German)
  docs/screenshots/     Images used in this README
```

The archive scan, the AWC reader, the credits list and the logos are the modules of the [extractor](../extractor/README.md).

## Legal notice

**Please read the full [disclaimer](../DISCLAIMER.md).** In short:

* This repository contains **only source code and documentation**. It contains **no game files, no archives
  and no extracted audio**, and it never will.
* You need to supply your own files, from a game you legally own. What you do with extracted audio is your
  responsibility – the audio is subject to the copyright of its owners. Personal, non-commercial analysis and
  archiving is one thing; redistributing it is generally not allowed.
* This project is **not affiliated with, endorsed by or connected to** Rockstar Games, Take-Two or any other
  publisher. All trademarks belong to their respective owners.

## Credits and license

The layout of the game's audio config follows the notes of the **Leonida** project (ItsNotRexed), whose texture reader
also helped to understand the pool the station logos come from (`awc_logos.py`). The Android app's launcher icon was drawn with the fan-made
typeface *GTA Art Deco* (not part of this repository). Headlines and clock digits in the app use
[Poiret One](https://github.com/alexeiva/poiretone) (SIL Open Font License, text in `android/assets/`). Playback uses [FFmpeg](https://ffmpeg.org/).

[MIT](../LICENSE) – applies to the source code in this repository only, not to any game data.
