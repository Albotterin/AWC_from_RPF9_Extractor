# Disclaimer

🇩🇪 [Deutsche Version](DISCLAIMER.de.md) · [← Project overview](README.md)

**Please read this before you download, build or use anything from this repository.**

## In short

* This is a **player and file tool** for game data that **you already have**.
* **No game data is shipped.** Without your own copy of the game files the programs do nothing.
* **Nothing is decrypted** and no copy protection is circumvented.
* **Nothing is modified:** not the game, not its files, not a console.
* Everything the programs create from your files stays **with you, for your private use**.

## 1. What this project is

The repository holds the source code and documentation of three programs:

| Program | What it does |
|---|---|
| AWC Audio Extractor (PC) | finds the audio containers in an archive you choose, plays them and can copy them into audio files |
| AWC Radio (PC) | plays the radio stations of your files as a live programme and writes station packs for your phone |
| AWC Radio app (Android) | plays those station packs, including a radio alarm |

It is a private, non-commercial hobby project. It is not a game, not a mod loader and not a tool to run, port or
recompile a game.

## 2. What is in this repository – and what is not

**Included:** source code, documentation, own artwork (app icon, the "Clubmixes" picture) and the font *Poiret One*
under the SIL Open Font License.

**Not included, and not to be added:** game files or archives, executables or libraries of the game or its platform,
audio, textures or logo files from the game, station packs, encryption keys.

Two things belong in the open here:

* The **screenshots** in the documentation show station logos and song titles as the programs display them from a user's
  own files. They are there only to illustrate what the software does.
* The source code and documentation contain **names and numeric identifiers** (for example station names, hash values
  and positions in file formats). They are needed to find data inside the user's own files and contain no audio or
  images.

## 3. No decryption, no circumvention

* The programs read only data that is stored **without encryption**: audio containers found by scanning, and
  configuration and texture data stored with ordinary, openly documented compression (zlib).
* Parts of the archive that appear to be encrypted or obfuscated, such as its table of contents, are **not read**.
* The repository contains **no keys** and no code to obtain, derive or apply them.
* The programs do not bypass copy protection, digital rights management or any security feature of a console or of a
  game.
* Data in formats the programs cannot read is skipped.

Requests for help with decryption, console modification or circumventing protection will not be answered.

## 4. Read only

The game files are opened **for reading only**. The programs do not change them, install nothing on a console, offer no
cheats, do not touch a running game and do not connect to game or platform services.

The only network access of the PC programs is the download of the **publisher's public credits web page**, used to name
songs. The Android app has no internet permission.

## 5. Your own files

* You must supply all game data yourself, from a **copy of the game that you lawfully own**.
* How you obtain files from your copy is your responsibility. This project does not provide game files, does not link to
  sources for them and does not help to obtain them.
* **Do not use leaked, stolen or otherwise unlawfully obtained data.** The project does not support this in any way.

## 6. Use of what the programs create

Audio files, logos and station packs made from your files remain protected by the **copyright of their owners** –
the game's publisher, the artists, composers, labels and other rights holders.

* Use them **only privately**, and only as far as the law where you live allows it.
* **Do not share, upload, sell, stream or publicly play** them. This includes the station packs.
* The Android app itself contains no game data; a station pack on your phone does.

## 7. Licence terms and local law

The licence agreement or terms of service of your game or platform may restrict extracting or analysing its files.
Copyright law, including rules on private copies and on reverse engineering, **differs between countries**. It is your
responsibility to check whether your use is allowed. Nothing in this repository is legal advice, and nothing here is a
promise that a particular use is lawful where you are.

## 8. No affiliation, trademarks

This project is **not affiliated with, authorised, sponsored or endorsed by** Rockstar Games, Take-Two Interactive
Software, Microsoft, Sony, Google, any radio station, artist, label or other rights holder. *Grand Theft Auto* and all
other names, logos and trademarks belong to their respective owners. They are mentioned only to describe what the
software works with.

## 9. No warranty, no liability

The software is provided **"as is"**, without warranty of any kind, as stated in the [MIT licence](LICENSE). To the
extent the law permits, the authors are not liable for any damage or claim arising from the software or its use. In
particular, do not rely on the radio alarm for anything important without testing it on your own device.

The MIT licence covers **only the code of this repository** (third-party parts keep their own licences). It grants no
rights to any game data, music, artwork or trademark.

## 10. For rights holders

If you believe that something in this repository infringes your rights, please
[open an issue](https://github.com/Albotterin/AWC_from_RPF9_Extractor/issues) and name the file and the right concerned.
The matter will be looked at promptly, and material that should not be here will be removed.
