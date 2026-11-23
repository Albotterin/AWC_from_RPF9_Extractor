# AWC Radio – station progress

[Deutsche Version](RADIO-PROGRESS.de.md)

How many of the songs that the public credits page lists for each station could be found as files in the game's `audio.rpf`.

**Status: 2026-11-23** · 754 / 837 (90.1 %)

🟢 90 % or more · 🟡 70–89 % · 🟠 30–69 % · 🔴 below 30 %

## Music stations and DJ sets

![AWC Radio – station progress](radio-progress.svg)

<details><summary>Table</summary>

| Station | Found / listed | Share |
|---|---:|---:|
| 🟢 Afrobank FM | 31 / 31 | 100.0 % |
| 🟢 Back Country Radio | 24 / 24 | 100.0 % |
| 🟢 Circoloco Records Radio | 27 / 27 | 100.0 % |
| 🟢 Radio La Ola | 25 / 25 | 100.0 % |
| 🟢 The Chamber 106.6 | 22 / 22 | 100.0 % |
| 🟢 V-Rock | 70 / 70 | 100.0 % |
| 🟢 Vice City FM | 70 / 71 | 98.6 % |
| 🟢 Cocoteo FM | 29 / 30 | 96.7 % |
| 🟢 Flash FM | 77 / 80 | 96.2 % |
| 🟢 Stockyard FM | 35 / 37 | 94.6 % |
| 🟢 Honey FM | 49 / 52 | 94.2 % |
| 🟢 Radio On-U | 15 / 16 | 93.8 % |
| 🟢 Dirty South Classics | 36 / 39 | 92.3 % |
| 🟢 Vice City Rap Radio | 23 / 25 | 92.0 % |
| 🟢 Gloriana Blues Radio | 21 / 23 | 91.3 % |
| 🟢 The Sniper Of La Perle | 20 / 22 | 90.9 % |
| 🟡 Emotion 98.3 | 34 / 38 | 89.5 % |
| 🟡 Kaleidoscope FM | 16 / 18 | 88.9 % |
| 🟡 Leonida Jazz Radio | 32 / 36 | 88.9 % |
| 🟡 Worldwide FM | 34 / 41 | 82.9 % |
| 🟡 Bodega The DJ | 36 / 45 | 80.0 % |
| 🟡 DJ Stevie J | 19 / 24 | 79.2 % |
| 🟠 Symphony FM | 9 / 17 | 52.9 % |
| 🔴 The Vault Radio | 0 / 24 | 0.0 % |
| **Total** | **754 / 837** | **90.1 %** |

</details>

## Talk radio and shows

The credits list speakers and shows here, not songs, so these numbers say little.

| Station | Found / listed | Share |
|---|---:|---:|
| 🔴 Honey FM - Liquid City | 0 / 8 | 0.0 % |
| 🔴 The Wipe Talk Radio | 0 / 40 | 0.0 % |
| 🔴 WKTT Talk Radio | 0 / 26 | 0.0 % |

## Reading the gaps

- **The Vault Radio** (0 %) is a single long DJ mix without per-song files, so none of its 24 listed songs exist as files. **Symphony FM** is 9 of 17: the other pieces have no name in the game's config, so they cannot be matched to the list.
- Titles are matched by their hash first, then by how similar the game's internal name is ("IM_IN_LUV" for "I'm N Luv (Wit a Stripper)"); 52 songs are found this way.
- Three long mixes are also played as extra stations (Radio On-U, Worldwide FM, The Sniper Of La Perle): the station of a mix is known from the song markers inside it. Three more long mixes have no such link and are not used yet.
- The other stations lack a few songs each (different spelling or no file of their own).

## How it is measured

- The credits page gives every station's songs. Each title is hashed (`joaat`, the way the game names its streams) and compared with the stream IDs found in the archive scan.
- A song counts as *found* when one stream has exactly that ID, or when the game's internal name of an otherwise unmatched stream is clearly the best match for it. Songs that are not stored as a file of their own count as missing.
- Only counts of public credits entries are shown; no game data and no song files are part of this repository.
