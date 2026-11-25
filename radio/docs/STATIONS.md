# Radio stations in the game's audio config

[AWC Radio](../README.md) · [Station progress](RADIO-PROGRESS.md) · [AWC / RPF9 format notes](../../extractor/docs/FORMAT.md)

The part of `audio.rpf` before the first AWC holds the game's audio config: a few metadata files, each cut into blocks of
64 KiB that are stored as plain zlib streams aligned to 16 bytes (a block that does not shrink is stored as it is). Nothing
is encrypted. `awc_gamedata.py` reads it; the meanings below are inferred from the data.

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
The talk stations have one track list per show episode with its parts in order; the stream names follow the pattern
`<station>_<show>_ep<n>_pt<m>`.
