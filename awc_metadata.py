"""Deterministic parser for RAGE 9 GAME.DAT and SOUNDS.DAT audMetadataFile (0x045A88EA) containers.

The inflated zlib stream groups at the start of audio.rpf (before the first ADAT bank) hold
self-describing audMetadataFile tables:
  - GAME.DAT (identified by RADIO_STATIONS_01, type 0x0E): wheel radio stations, static world
    emitters ((flags & 0x30) == 0x10), hidden wheel stations ((flags & 0x0C) == 0x04),
    RadioStationTrackList (0x11) with external ObjectList (0x02) + inline refs, and
    RadioTrackSettings (0x0F) pointing to SOUNDS.DAT sound hashes.
  - SOUNDS.DAT: StreamingSound (0x08) duration and adjacent SimpleSound (0x0C) channel groups
    holding (bank_hash, wave_hash) -> 29-bit AWC stream IDs.

Pure Python standard library; ships no game data, keys, or station/song lists.
"""
from collections import defaultdict
import struct

from awc_names import STREAM_ID_MASK, joaat

AUD_METADATA_MAGIC = 0x045A88EA
AUD_METADATA_CHUNK_SIZE = 65536
OBJECT_REF_NONE = 0xFFFFFFFF

TYPE_OBJECT_LIST = 0x02
TYPE_STREAMING_SOUND = 0x08
TYPE_SIMPLE_SOUND = 0x0C
TYPE_RADIO_STATION_LIST = 0x0E
TYPE_RADIO_TRACK = 0x0F
TYPE_RADIO_STATION = 0x10
TYPE_RADIO_TRACKLIST = 0x11

RADIO_STATIONS_LIST_PREFIX = "RADIO_STATIONS_"
RADIO_STATIONS_MAX_LISTS = 16
WHEEL_STATION_STATIC_MASK = 0x30
WHEEL_STATION_STATIC_VALUE = 0x10
WHEEL_STATION_HIDDEN_MASK = 0x0C
WHEEL_STATION_HIDDEN_VALUE = 0x04


def group_inflated_streams(streams, chunk_size=AUD_METADATA_CHUNK_SIZE):
    """Group consecutive inflated zlib streams (ending on any stream whose size != chunk_size)."""
    groups = []
    current = []
    for blob in streams:
        current.append(blob)
        if len(blob) != chunk_size:
            groups.append(b"".join(current))
            current = []
    if current:
        groups.append(b"".join(current))
    return groups


def parse_aud_metadata(blob):
    """Parse one contiguous audMetadataFile (0x045A88EA) payload, or return None if magic differs."""
    if len(blob) < 16:
        return None
    _version, meta_size, magic = struct.unpack_from("<III", blob, 0)
    if magic != AUD_METADATA_MAGIC:
        return None

    s1_pos = 8 + meta_size
    if s1_pos + 4 > len(blob):
        raise ValueError(f"audMetadataFile metadata size 0x{meta_size:x} exceeds payload length 0x{len(blob):x}")
    s1_size, = struct.unpack_from("<I", blob, s1_pos)
    s2_pos = s1_pos + 4 + s1_size
    if s2_pos + 4 > len(blob):
        raise ValueError(f"audMetadataFile section 1 size 0x{s1_size:x} exceeds payload")
    s2_size, = struct.unpack_from("<I", blob, s2_pos)
    obj_pos = s2_pos + 4 + s2_size
    if obj_pos + 4 > len(blob):
        raise ValueError(f"audMetadataFile object table offset 0x{obj_pos:x} out of range")

    obj_count, = struct.unpack_from("<I", blob, obj_pos)
    obj_end = obj_pos + 4 + obj_count * 8
    if obj_end > len(blob):
        raise ValueError(f"audMetadataFile object table ({obj_count} entries) truncated")

    by_hash = {}
    by_offset = {}
    prev_bucket = -1
    for index in range(obj_count):
        obj_hash, obj_offset = struct.unpack_from("<II", blob, obj_pos + 4 + index * 8)
        bucket = obj_hash & 0xFF
        if bucket < prev_bucket or obj_offset >= meta_size:
            raise ValueError(f"corrupt object table entry {index}: hash=0x{obj_hash:x} off=0x{obj_offset:x}")
        prev_bucket = bucket
        by_hash[obj_hash] = obj_offset
        by_offset[obj_offset] = obj_hash

    return {
        "blob": blob,
        "meta_size": meta_size,
        "by_hash": by_hash,
        "by_offset": by_offset,
        "obj_end": obj_end,
    }


def parse_aud_refs(meta):
    """Parse the cross-object reference hash table that follows the object table."""
    blob = meta["blob"]
    p3 = meta["obj_end"]
    if p3 + 4 > len(blob):
        raise ValueError("truncated audMetadataFile pre-reference table")
    n_pre, = struct.unpack_from("<I", blob, p3)
    p4 = p3 + 4 + n_pre * 4
    if p4 + 12 > len(blob):
        raise ValueError("truncated audMetadataFile reference header")
    _, _, n_refs = struct.unpack_from("<III", blob, p4)
    refs_end = p4 + 12 + n_refs * 8
    if refs_end > len(blob):
        raise ValueError(f"audMetadataFile reference table ({n_refs} entries) truncated")
    return [struct.unpack_from("<I", blob, p4 + 12 + i * 8)[0] for i in range(n_refs)]


def parse_sounds_streaming_index(sounds_meta):
    """Index StreamingSound (0x08) objects and their adjacent SimpleSound (0x0C) channel groups."""
    blob = sounds_meta["blob"]
    meta_size = sounds_meta["meta_size"]
    sorted_offsets = sorted(sounds_meta["by_offset"])
    spans = []
    for i, off in enumerate(sorted_offsets):
        nxt = sorted_offsets[i + 1] if i + 1 < len(sorted_offsets) else meta_size
        spans.append((off, nxt, sounds_meta["by_offset"][off], blob[8 + off]))

    parsed = {}
    for i, (off, nxt, sound_hash, obj_type) in enumerate(spans):
        if obj_type != TYPE_STREAMING_SOUND:
            continue
        seg = blob[8 + off:8 + nxt]
        for cnt in range(1, 17):
            p = len(seg) - 2 - cnt * 4
            if p >= 4 and seg[p] == TYPE_SIMPLE_SOUND and seg[p + 1] == cnt:
                duration_ms, = struct.unpack_from("<I", seg, p - 4)
                channels = []
                for k in range(1, cnt + 1):
                    if i + k < len(spans) and spans[i + k][3] == TYPE_SIMPLE_SOUND:
                        co, cn, _, _ = spans[i + k]
                        cseg = blob[8 + co:8 + cn]
                        if len(cseg) >= 10 and cseg[1] == 0x00:
                            bank_hash, wave_hash = struct.unpack_from("<II", cseg, 2)
                            channels.append({
                                "bank_hash": bank_hash,
                                "wave_hash": wave_hash,
                                "stream_id": wave_hash & STREAM_ID_MASK,
                            })
                parsed[sound_hash] = {
                    "sound_hash": sound_hash,
                    "duration_ms": duration_ms,
                    "channel_count": cnt,
                    "channels": channels,
                }
                break
    return parsed


def walk_tracklist_tracks(tl_hash, game_meta, game_refs):
    """Return [(track_settings_hash, sound_hash)] for one RadioStationTrackList (0x11)."""
    blob = game_meta["blob"]
    meta_size = game_meta["meta_size"]
    by_hash = game_meta["by_hash"]

    tl_off = by_hash.get(tl_hash)
    if tl_off is None or tl_off + 0x4C > meta_size or blob[8 + tl_off] != TYPE_RADIO_TRACKLIST:
        return []
    tl_pos = 8 + tl_off
    obj_list_ref, = struct.unpack_from("<I", blob, tl_pos + 0x44)
    inline_count, = struct.unpack_from("<H", blob, tl_pos + 0x48)
    if tl_off + 0x4C + inline_count * 4 > meta_size:
        raise ValueError(f"tracklist 0x{tl_hash:08x} inline reference array truncated")

    track_refs = []
    if obj_list_ref != OBJECT_REF_NONE and obj_list_ref < len(game_refs):
        ol_hash = game_refs[obj_list_ref]
        ol_off = by_hash.get(ol_hash)
        if ol_off is not None and ol_off + 12 <= meta_size and blob[8 + ol_off] == TYPE_OBJECT_LIST:
            ol_count, = struct.unpack_from("<I", blob, 8 + ol_off + 8)
            if ol_off + 12 + ol_count * 4 > meta_size:
                raise ValueError(f"object list 0x{ol_hash:08x} truncated")
            track_refs.extend(struct.unpack_from(f"<{ol_count}I", blob, 8 + ol_off + 12))
    if inline_count:
        track_refs.extend(struct.unpack_from(f"<{inline_count}I", blob, tl_pos + 0x4C))

    out = []
    for tr_ref in track_refs:
        if tr_ref >= len(game_refs):
            continue
        tr_hash = game_refs[tr_ref]
        tr_off = by_hash.get(tr_hash)
        if tr_off is None or tr_off + 0x10 > meta_size or blob[8 + tr_off] != TYPE_RADIO_TRACK:
            continue
        sound_ref, = struct.unpack_from("<I", blob, 8 + tr_off + 0x0C)
        if sound_ref >= len(game_refs):
            continue
        out.append((tr_hash, game_refs[sound_ref]))
    return out


def parse_radio_metadata(game_meta, sounds_meta):
    """Resolve wheel stations, static world emitters, unattached tracklists, and stream links.

    Returns a dict with:
      - stations: list of player wheel stations sorted by wheel_position (with hidden flag and tracks)
      - static_emitters: list of static world emitters ((flags & 0x30) == 0x10)
      - script_tracklists: list of RadioStationTrackList (0x11) objects not attached to listed stations
      - stream_to_stations: {stream_id: [station_hash, ...]} for wheel stations
      - streaming_sounds: {sound_hash: {duration_ms, channel_count, channels}}
    """
    game_blob = game_meta["blob"]
    game_meta_size = game_meta["meta_size"]
    game_by_hash = game_meta["by_hash"]
    game_refs = parse_aud_refs(game_meta)
    streaming_sounds = parse_sounds_streaming_index(sounds_meta)

    stations = []
    static_emitters = []
    stream_to_stations = defaultdict(list)
    seen_stations = set()
    attached_tracklists = set()

    for list_index in range(1, RADIO_STATIONS_MAX_LISTS + 1):
        list_hash = joaat(f"{RADIO_STATIONS_LIST_PREFIX}{list_index:02d}")
        list_off = game_by_hash.get(list_hash)
        if list_off is None:
            continue
        if list_off + 8 > game_meta_size or game_blob[8 + list_off] != TYPE_RADIO_STATION_LIST:
            raise ValueError(f"{RADIO_STATIONS_LIST_PREFIX}{list_index:02d} has unexpected object layout")
        st_count = game_blob[8 + list_off + 5]
        if list_off + 8 + st_count * 4 > game_meta_size:
            raise ValueError(f"{RADIO_STATIONS_LIST_PREFIX}{list_index:02d} station ref list truncated")
        for i in range(st_count):
            st_ref, = struct.unpack_from("<I", game_blob, 8 + list_off + 8 + i * 4)
            if st_ref >= len(game_refs):
                raise ValueError(f"station reference {st_ref} out of range")
            st_hash = game_refs[st_ref]
            if st_hash in seen_stations:
                continue
            seen_stations.add(st_hash)
            st_off = game_by_hash.get(st_hash)
            if st_off is None or st_off + 0x40 > game_meta_size or game_blob[8 + st_off] != TYPE_RADIO_STATION:
                continue
            st_pos = 8 + st_off
            flags, = struct.unpack_from("<I", game_blob, st_pos + 1)
            name_hash, wheel_position = struct.unpack_from("<II", game_blob, st_pos + 8)
            tl_count, = struct.unpack_from("<I", game_blob, st_pos + 0x3C)
            if st_off + 0x40 + tl_count * 12 > game_meta_size:
                raise ValueError(f"station 0x{st_hash:08x} tracklist array truncated")

            tl_hashes = []
            for j in range(tl_count):
                tl_ref, = struct.unpack_from("<I", game_blob, st_pos + 0x40 + j * 12)
                if tl_ref < len(game_refs):
                    tl_hash = game_refs[tl_ref]
                    tl_hashes.append(tl_hash)
                    attached_tracklists.add(tl_hash)

            tracks = []
            for tl_hash in tl_hashes:
                for tr_hash, sound_hash in walk_tracklist_tracks(tl_hash, game_meta, game_refs):
                    sound = streaming_sounds.get(sound_hash)
                    tracks.append({
                        "tracklist_hash": tl_hash,
                        "track_settings_hash": tr_hash,
                        "sound_hash": sound_hash,
                        "duration_ms": sound["duration_ms"] if sound else None,
                        "stream_ids": [ch["stream_id"] for ch in sound["channels"]] if sound else [],
                    })

            is_static = (flags & WHEEL_STATION_STATIC_MASK) == WHEEL_STATION_STATIC_VALUE
            if is_static:
                static_emitters.append({
                    "emitter_hash": st_hash,
                    "label_hash": name_hash,
                    "flags": flags,
                    "tracks": tracks,
                })
                continue

            hidden = (flags & WHEEL_STATION_HIDDEN_MASK) == WHEEL_STATION_HIDDEN_VALUE
            stations.append({
                "station_hash": st_hash,
                "label_hash": name_hash,
                "wheel_position": wheel_position,
                "hidden": hidden,
                "flags": flags,
                "tracks": tracks,
            })
            for tr in tracks:
                for sid in tr["stream_ids"]:
                    if st_hash not in stream_to_stations[sid]:
                        stream_to_stations[sid].append(st_hash)

    script_tracklists = []
    for obj_hash, obj_off in sorted(game_by_hash.items()):
        if obj_hash in attached_tracklists or game_blob[8 + obj_off] != TYPE_RADIO_TRACKLIST:
            continue
        tracks = []
        for tr_hash, sound_hash in walk_tracklist_tracks(obj_hash, game_meta, game_refs):
            sound = streaming_sounds.get(sound_hash)
            tracks.append({
                "track_settings_hash": tr_hash,
                "sound_hash": sound_hash,
                "duration_ms": sound["duration_ms"] if sound else None,
                "stream_ids": [ch["stream_id"] for ch in sound["channels"]] if sound else [],
            })
        if tracks:
            script_tracklists.append({"tracklist_hash": obj_hash, "tracks": tracks})

    stations.sort(key=lambda s: (s["wheel_position"], s["station_hash"]))
    return {
        "stations": stations,
        "static_emitters": static_emitters,
        "script_tracklists": script_tracklists,
        "stream_to_stations": dict(stream_to_stations),
        "streaming_sounds": streaming_sounds,
    }
