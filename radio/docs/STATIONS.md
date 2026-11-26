# Radio stations in the game's audio config

[AWC Radio](../README.md) · [Station progress](RADIO-PROGRESS.md) · [AWC / RPF9 format notes](../../extractor/docs/FORMAT.md)

The part of `audio.rpf` before the first AWC holds the game's audio config: a few metadata files, each cut into blocks of
64 KiB that are stored as plain zlib streams aligned to 16 bytes (a block that does not shrink is stored as it is). Nothing
is encrypted. [`awc_gamedata.py`](../../extractor/awc_gamedata.py) reads it; the meanings below are inferred from the data.

A metadata file (all little endian):

```
+0x00  u32  version
+0x04  u32  data length
+0x08  u32  magic 0x045A88EA
data
u32 length + bytes                      (twice)
u32 count, count x (u32 name hash, u32 offset)          index: joaat of the object name -> offset in the data
3 x (u32 count, count x u32)
u32 count, count x (u32 name hash, u32 x)               reference table
```

Objects refer to each other by their **position in the reference table**; the hash found there is looked up in an index
(possibly of another file). The first byte of an object is its type. The ones used for the radio:

| File | Type | Content |
|---|---:|---|
| game | 16 | radio station (`radio_flash_fm`, ...): `+60` count, then `(track list, x, 0)` per entry |
| game | 17 | track list: `+8` category, `+68` optional list, `+72` count, references |
| game | 2 | list of lists: `+8` count, references |
| game | 15 | track: `+12` sound, `+16` title object or `0xFFFFFFFF` |
| game | 12 | title: `+12` title text, `+96` number of artists, `(reference, x)` from `+100` |
| game | 7 | artist: text from `+5` |
| sounds | 8 | wrapper: ends with `u8 1, u32 reference` |
| sounds | 12 | simple sound: ends with `u32 bank hash, u32 stream hash, 2 bytes, u8 count, count x reference` |

The stream hash, cut to 29 bits, is the stream ID in the AWC. The same ID can exist in several banks (every station has
its own DJ links); the banks of one station lie together in the archive, so the nearest one is taken. Track lists of the
category with name hash `0x2BE42A81` hold the music; short streams that only one station uses are idents and DJ talk.
The talk stations have track lists with the parts of their shows in order; the stream names follow the pattern
`<station>_<show>_ep<n>_pt<m>`.

## Track list categories

The category of a track list (`+8`) is `joaat("radio_track_category_<kind>")` with the kinds `music`, `music_night`,
`music_b`, `music_dj2`, `music_bed`, `advert`, `ident` (also `_night`, `_b`), `dj_banter` (also `_night`, `_to_ads`,
`_from_ads`, `_from_news`, `_story`), `showintro` and `showoutro` (also `_night`, `_dj2`), `talk` (also `_night`) and
`news`. Some stations and the talk shows use further categories whose names are not known. The extractor groups a
station's streams by these.

## Programme rules

A station object names, at `+24`, the root of its programme rules. Nothing of this is encrypted; the curves are part of the
same audio config. The meanings were inferred from the data (`awc_gamedata.parse_rules`).

| Type | Content |
|---:|---|
| 30 | rules root: `+8` the rule for the beginning, `+12` count, then `(category, rule)` pairs from `+16`: what may follow an item of that category |
| 31 | rule: from `+8`, per possible follower: `category, u32 n, n x factor`. The weight of a follower is the product of its factors |
| 33, 38 | factor: the `f32` at `+8` |
| 35 | factor by the number of items of a category in a row: `+8` category, `+12` curve |
| 36 | factor by the seconds since a category was last heard: `+8` category, `+12` curve |
| 39 | factor by the hour of the day (0-24): `+8` curve |
| 5 | switch on a game variable: `+8` condition, `+12` / `+16` the two factors. Taken: the second (variable not set) |
| 49 | choice depending on a game variable: `f32` at `+12` and `+16`. Taken: half their sum |

**Curves** lie in a metadata file of their own whose index is read directly: type 4 is a polyline (`u32` n at `+16`, then
n x `(f32 x, f32 y)` from `+20`), type 6 a table (`u32` n at `+16`, then n x `f32`), read here as the weights for the 1st,
2nd, ... item in a row.

Behind a station's track lists come pairs `(category, mixing rule)` and then `(category, n, mode)`: with mode 2 the last n
items of that category do not come back, with mode 1 the category is played in its order.

AWC Radio uses the rules of 20 stations (18 music stations and the two talk stations). The mixing rules (how items overlap
in the game) are not used.

## News

The news bulletins lie in track lists of the category `news` that belong to no station (a list of lists with 46 and 84
entries in the sample archive). A station plays them when its rules name the category.

## Mixing

How one item hands over to the next. Behind a station object's track lists stand a count and pairs of (category,
reference): the **category of the item that ends**. The reference leads to

| Object type | What it is |
|---|---|
| 22 | a mixing rule |
| 23 | a rule per **category of the item that follows**: count at +8, pairs (category, reference) from +12 |
| 3 | a weighted choice: count in byte +7 (0xFF: u32 at +12), then pairs (reference, f32 weight) |
| 31 | rows (reference, number of conditions, conditions); a condition of type 197 holds at +8 what counts while the game has set nothing |

A **mixing rule** (252 bytes), all times in milliseconds:

| Offset | Meaning |
|---|---|
| +12 | i32, 0 or negative: the ending item's end is moved earlier |
| +20 | i32, length of its fade-out |
| +24 | shape of the fade-out (a name: `linear_fall`, `equal_power_fall`) |
| +28 | i32, when the following item starts, relative to that end (negative: before it) |
| +32 | i32, length of its fade-in |
| +36 | shape of the fade-in (`linear_rise`, `equal_power_rise`) |
| +40, +144 | two blocks of 104 bytes with filter settings (low-pass sweeps in a few rules); not used here |

Song banks carry marker events: `dj` (the DJ's two windows), `intro` (about where the sound begins: the cue-in point) and
`outro` (a few seconds before the end).

This program counts the times from the **end of the recording**, lets the `intro` marker of a following song land on the
start time, and never lays more than half of either item over the other. The talk stations name no mixing rules.

## Music beds

16 music stations have a track list of the category `music_bed`: short pieces of music, made to repeat. No programme rule
asks for them. This program lays one under the DJ's talk (categories `dj_banter…`, `showintro…`, `showoutro…`) when the
recording's quiet moments lie below -40 dB, that is, when it brings no sound of its own; 12 dB below the talk. Which bed
is a hash of station and time, the same on PC and phone.

## Recordings recognised by their sound

A few recordings have neither a station nor a name in the game's files. `awc_album.py` notes by their stream numbers which
songs they are (four of Overmono for the soundtrack album, Augustus Pablo's *Ras Menilik Congo (Harp)* on Radio On-U);
this was found by comparing their sound with public 30 second previews.

## Places with music

Station objects that are no radio station give lifts, lobbies, a minigolf course and other places their music. Their
recordings without a track number are the group **Music in places** of the score (113 recordings in the sample archive);
the names known from the objects' name hashes (`hidden_radio_muzak`, ...) are used as sub groups, the others are numbered.

## Adverts

In the sample archive 18 stations list adverts, 239 different ones, each station its own selection (21 to 70).

* No advert runs on all of them. 199 run on two or more stations (at most 12), 40 on one only - 25 of those are Symphony
  FM's own short ones.
* 28 adverts run only on **Cocoteo FM** and **Radio La Ola** (23 on both, 3 only on Cocoteo FM, 2 only on La Ola). The two
  stations also play adverts they share with others (Cocoteo FM 44 of its 70, La Ola 10 of its 35).
* A comparison of the sound (spectral landmarks and half-second waveform windows) finds for 13 of those 28 another advert
  with partly the same soundtrack, usually stored right next to it; 12 of these 13 counterparts do not run on the two
  stations. That fits two language versions of one spot - listeners report the exclusive ones as Spanish. The programs
  do not recognise languages.

AWC Radio plays the adverts of a station only with the switch **Adverts**.

## Podcasts

The track list `joaat("core.radiostation.podcast_<name>_talk")` holds a podcast's episodes in their order, through a list
of lists (`<name>`: `drunk_kings`, `final_act`, `fmm`, `safetrap`, `vice_city_true_stories`). Besides, 20 track lists with
a single entry point at one episode each. Every episode is one stream in a music AWC of its own; in the sample archive the
20 AWCs lie one after the other.

| Podcast | Episodes | Stream names found (the ID is their hash) |
|---|---:|---|
| Drunk Kings | 5 | - |
| Final Act | 4 | `pod_finalact_01` ... `04` |
| Fuck Marry Murder | 6 | `podcast_fmm_ep01` ... `ep06` |
| The Safe Trap | 3 | `podcast_safetrap_ep1` ... `ep3` |
| Vice City True Stories | 2 | - |

A search of all music streams for further numbers of these name patterns found none. The publisher's credits page names
the same five podcasts. The game shows episode titles and numbers of its own (a player reported "#021" for The Safe Trap);
the audio config does not hold them.

## Places that play their own music

Some objects of the game file are named `joaat("core.radiostation.<name>_music")`: track lists of places of the game. For
`club_joh_bodega`, `club_vyb_stevie_j` and `dj_mixes_the_vault` they hold the DJ set of a club. No station object refers to
them. A set can have several tracks in order, and a track's sound wrapper (type 8) can end with `u8 count, count x reference`
instead of a single reference: layers that sound together, the set itself and the quiet rest of the room. AWC Radio plays the
loud layer (its stream IDs are listed in `awc_gamedata.CLUB_MAIN_LAYERS`) and joins the tracks of a set into one item.

The station object `radio_mudclub` is a playlist of 20 songs, most of them shared with two radio stations; AWC Radio lists it
as the Thrillbilly Mud Club (name and logo assigned by the matching internal name).

One long mix (about 61 minutes) is referenced by no track list; it lies in another part of the archive among short sounds
with the same property and is left in "Clubmixes".
