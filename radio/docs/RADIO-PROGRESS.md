# AWC Radio – station progress

[AWC Radio](../README.md) · [Project overview](../../README.md) · [Deutsche Version](RADIO-PROGRESS.de.md)

How many of the songs that the public credits page lists for each station could be found as files in the game's `audio.rpf`.

**Status: 2026-11-25** · 792 / 809 (97.9 %)

Since v2.4.0 the playlists come straight from the game's audio config. Many stations therefore also play songs the credits do not list (column *In the radio*).

🟢 90 % or more · 🟡 70–89 % · 🟠 30–69 % · 🔴 below 30 %

## Music stations and DJ sets

![AWC Radio – station progress](radio-progress.svg)

<details><summary>Table</summary>

| Station | Found / listed | Share | In the radio |
|---|---:|---:|---:|
| 🟢 Afrobank FM | 31 / 31 | 100.0 % | 31 |
| 🟢 Back Country Radio | 24 / 24 | 100.0 % | 24 |
| 🟢 The Chamber 106.6 | 22 / 22 | 100.0 % | 22 |
| 🟢 Circoloco Records Radio | 27 / 27 | 100.0 % | 27 |
| 🟢 Cocoteo FM | 30 / 30 | 100.0 % | 30 |
| 🟢 Dirty South Classics | 39 / 39 | 100.0 % | 39 |
| 🟢 Emotion 98.3 | 35 / 35 | 100.0 % | 35 |
| 🟢 Flash FM | 80 / 80 | 100.0 % | 83 |
| 🟢 Gloriana Blues Radio | 23 / 23 | 100.0 % | 23 |
| 🟢 Honey FM | 52 / 52 | 100.0 % | 54 |
| 🟢 Kaleidoscope FM | 18 / 18 | 100.0 % | 18 |
| 🟢 Leonida Jazz Radio | 36 / 36 | 100.0 % | 36 |
| 🟢 Radio La Ola | 25 / 25 | 100.0 % | 25 |
| 🟢 The Sniper Of La Perle | 22 / 22 | 100.0 % | 22 |
| 🟢 Stockyard FM | 37 / 37 | 100.0 % | 37 |
| 🟢 Symphony FM | 16 / 16 | 100.0 % | 16 |
| 🟢 V-Rock | 70 / 70 | 100.0 % | 70 |
| 🟢 Vice City FM | 71 / 71 | 100.0 % | 72 |
| 🟢 Vice City Rap Radio | 25 / 25 | 100.0 % | 82 |
| 🟢 Worldwide FM | 39 / 41 | 95.1 % | 40 |
| 🟢 Radio On-U | 15 / 16 | 93.8 % | 16 |
| 🟡 Bodega The DJ | 36 / 45 | 80.0 % | 36 |
| 🟡 DJ Stevie J | 19 / 24 | 79.2 % | 19 |
| **Total** | **792 / 809** | **97.9 %** | **857** |

</details>

## Talk radio

The credits list speakers here, not songs. All shows named there are found; the parts of an episode play as one piece.

| Station | Show | Episodes | Length |
|---|---|---:|---:|
| 🎙 WKTT Talk Radio | Hit the Bricks | 2 | 28 min |
| 🎙 WKTT Talk Radio | The Richard Bastion Show | 2 | 44 min |
| 🎙 The Wipe Talk Radio | Ask Dr. Nita | 4 | 49 min |
| 🎙 The Wipe Talk Radio | El Otro Lado | 3 | 54 min |
| 🎙 The Wipe Talk Radio | She Got Assets | 4 | 52 min |

## Station that is one mix

One continuous DJ mix; its songs are not stored as separate files, so they are not counted above.

| Station | Found | Listed songs inside |
|---|---|---:|
| 🎚 The Vault Radio | 1 mix (84 min), plays as the station | 24 |

## Places of the game

What the audio config says these places play. Their recordings are not in the credits lists, so they are not counted above.

| Station | Plays | Length |
|---|---|---:|
| 🎚 Jack of Hearts | DJ set of the club (resident: Bodega The DJ) | 100 min |
| 🎚 VYB | DJ set of the club in four parts (resident: DJ Stevie J) | 45 min |
| 🎚 The Vault (Club) | the club's set: same 25 track markers as The Vault Radio, another mix | 84 min |
| 🎵 Thrillbilly Mud Club | 20 songs, mostly also on The Chamber 106.6 and Dirty South Classics | 84 min |

## Reading the gaps

- **Bodega The DJ** and **DJ Stevie J** are DJ sets in clubs. The missing titles have no single file assigned to them there; some of them play on other stations.
- **Worldwide FM**: two listed titles are not assigned, one recording of the playlist plays as "Unknown track". **Radio On-U**: one title is missing, very probably it is the station's "Unknown track".
- **Honey FM - Liquid City** (8 original songs, not counted above) is in none of the station lists.
- Long DJ mixes are also played as "(Mix)" stations: Radio On-U (10 of 24), The Sniper Of La Perle (20 of 22), Worldwide FM (31 of 41). The numbers are the parts that have a title. One further long club mix, which no place can be assigned to, plays as "Clubmixes".
- The five podcasts the game config names are not assigned yet.

## How it is measured

- Counted are the entries the credits page lists as *songs*. (Earlier versions of this page also counted composer and vocalist credits.)
- For most stations the game's audio config names every recording of the playlist with title and artist. A song counts as *found* when a recording of the station fits the credits entry (same title or a known different spelling).
- For the club DJs and mixes the titles are still matched by their hash (`joaat`) and by how similar the internal names are.
- Only counts and show names of public credits entries are shown; no game data and no song files are part of this repository.
