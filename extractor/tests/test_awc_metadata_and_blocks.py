"""Synthetic unit tests for AwcFile.extract() block budget/skip handling and awc_metadata.py.

No game data or external dependencies required. Run with:
    python3 -m unittest tests/test_awc_metadata_and_blocks.py -v
"""
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

import awc_core
import awc_metadata
from awc_names import joaat


def _build_synthetic_music_awc(block_specs, stream_id=0x1234567, declared_samples=0, indexed=False,
                               second_stream=None):
    """Build a streamed AWC (0x48 + 0x55) from [(skip_samples, block_samples, [packets])] per stream."""
    stream_count = 1 if second_stream is None else 2
    chunk_size = 2048 * (1 + stream_count)
    desc = struct.pack("<III", len(block_specs), chunk_size, stream_count)
    for c in range(stream_count):
        desc += struct.pack("<IIhHBBH", stream_id + c, declared_samples, 0, 48000, 0x0B, 2, 0xFFFF)

    data = bytearray(len(block_specs) * chunk_size)
    blocks = ((spec,) for spec in block_specs) if second_stream is None else zip(block_specs, second_stream, strict=True)
    for b_idx, streams in enumerate(blocks):
        base = b_idx * chunk_size
        for c, (skip_samples, block_samples, packets) in enumerate(streams):
            struct.pack_into("<iiiiII", data, base + 24 * c, -1, 1, skip_samples, block_samples, 0, 0)
            payload = b"".join(struct.pack("<H", len(p)) + p for p in packets)
            assert len(payload) <= 2048
            unit = base + 2048 * (1 + c)
            data[unit:unit + len(payload)] = payload

    flags = 0xFF25 if indexed else 0
    tables = struct.pack("<HII", 0, 0, 0) if indexed else b""
    info_off = 16 + len(tables) + 4 + 16
    tag_48 = (0x48 << 56) | (len(desc) << 28) | info_off
    tag_55 = (0x55 << 56) | (len(data) << 28) | (info_off + len(desc))
    hdr = b"ADAT" + struct.pack("<HHII", 1, flags, 1, info_off)
    hdr += tables
    hdr += struct.pack("<I", (0 & 0x1FFFFFFF) | (2 << 29))
    hdr += struct.pack("<QQ", tag_48, tag_55)
    return hdr + desc + bytes(data)


def _extract_ogg_audio_payload(path):
    with open(path, "rb") as fh:
        raw = fh.read()
    pos = 0
    pages = []
    while pos < len(raw):
        assert raw[pos:pos + 4] == b"OggS"
        nseg = raw[pos + 26]
        body_len = sum(raw[pos + 27:pos + 27 + nseg])
        body = raw[pos + 27 + nseg:pos + 27 + nseg + body_len]
        pages.append(body)
        pos += 27 + nseg + body_len
    return b"".join(pages[2:])


def _build_aud_metadata_blob(objects, refs=()):
    """Build a synthetic audMetadataFile (0x045A88EA) container.

    objects: list of (obj_hash, payload_bytes)
    refs: list of u32 target hashes
    """
    meta = bytearray()
    entries = []
    for obj_hash, payload in objects:
        entries.append((obj_hash, len(meta)))
        meta.extend(payload)
    entries.sort(key=lambda item: (item[0] & 0xFF, item[0]))

    # meta_size starts at offset 8 (immediately after version + meta_size); magic sits at meta[0..4].
    # Prepend 4-byte magic inside meta space so obj_offset >= 4 avoids overlapping magic.
    meta_with_magic = struct.pack("<I", awc_metadata.AUD_METADATA_MAGIC) + bytes(meta)
    entries = [(h, off + 4) for h, off in entries]

    out = bytearray()
    out += struct.pack("<II", 1, len(meta_with_magic))
    out += meta_with_magic
    out += struct.pack("<I", 0)  # section 1 size
    out += struct.pack("<I", 0)  # section 2 size
    out += struct.pack("<I", len(entries))
    for obj_hash, obj_off in entries:
        out += struct.pack("<II", obj_hash, obj_off)
    out += struct.pack("<I", 0)  # pre-reference count
    out += struct.pack("<III", 0, 0, len(refs))
    for ref_hash in refs:
        out += struct.pack("<II", ref_hash, 0)
    return bytes(out)


class AwcBlockSkipTests(unittest.TestCase):
    def test_join_streams_reconciles_each_parts_blocks(self):
        p1, p2, pad, p3, p4 = b"\xfcA", b"\xfcB", b"\xfcP", b"\xfcC", b"\xfcD"
        with tempfile.TemporaryDirectory() as tmp:
            first = Path(tmp) / "part1.awc"
            first.write_bytes(_build_synthetic_music_awc([
                (0, 3840, [p1, p2, pad]),
                (1920, 3840, [p2, p3]),
            ]))
            second = Path(tmp) / "part2.awc"
            second.write_bytes(_build_synthetic_music_awc([(0, 3840, [p3, p4])]))
            parts = [(awc_core.AwcFile(first), 0), (awc_core.AwcFile(second), 0)]
            output = Path(tmp) / "episode.opus"
            seconds = awc_core.join_streams(parts, output)
            self.assertEqual(_extract_ogg_audio_payload(output), p1 + p2 + p3 + p3 + p4)
            self.assertAlmostEqual(seconds, 0.1)

    def test_join_streams_does_not_reuse_skip_history_across_parts(self):
        p1, p2 = b"\xfcA", b"\xfcB"
        for budget, packet in ((1920, p1), (3840, p2)):
            with self.subTest(budget=budget), tempfile.TemporaryDirectory() as tmp:
                first = Path(tmp) / "part1.awc"
                first.write_bytes(_build_synthetic_music_awc([(0, budget, [p1])]))
                second = Path(tmp) / "part2.awc"
                second.write_bytes(_build_synthetic_music_awc([(1920, 1920, [packet])]))
                parts = [(awc_core.AwcFile(first), 0), (awc_core.AwcFile(second), 0)]
                output = Path(tmp) / "episode.opus"
                with self.assertRaisesRegex(awc_core.AwcError, "unverified skip"):
                    awc_core.join_streams(parts, output)
                self.assertFalse(output.exists())
                output.write_bytes(b"previous complete episode")
                with self.assertRaisesRegex(awc_core.AwcError, "unverified skip"):
                    awc_core.join_streams(parts, output)
                self.assertEqual(output.read_bytes(), b"previous complete episode")

    def test_scan_omits_bank_truncated_before_first_block(self):
        raw = _build_synthetic_music_awc([(0, 1920, [b"\xfcA"])], declared_samples=1920)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "truncated.rpf"
            path.write_bytes(raw[:-4096])
            self.assertEqual(awc_core.scan_container(str(path)), ([], 1))

    def test_opens_indexed_bank_with_unaligned_chunk_tags(self):
        raw = _build_synthetic_music_awc([(0, 1920, [b"\xfcA"])], indexed=True)
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "indexed.awc")
            with open(path, "wb") as f:
                f.write(raw)
            with open(path, "rb") as f:
                self.assertEqual(awc_core._probe_adat(f, 0, len(raw)), {"length": len(raw)})
            self.assertEqual(len(awc_core.AwcFile(path).streams), 1)

    def test_rebuilds_version_6_cache_with_indexed_bank(self):
        raw = _build_synthetic_music_awc([(0, 0, []), (0, 1920, [b"\xf8A", b"\xf8B"])], indexed=True)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "indexed.rpf"
            path.write_bytes(raw)
            cache = Path(tmp) / "cache.json"
            cache.write_text(json.dumps({
                "v": 6, "size": len(raw), "mtime": int(path.stat().st_mtime), "items": [], "total": 1,
            }), encoding="utf-8")
            with patch.object(awc_core, "_cache_file", return_value=cache):
                items, total, cached = awc_core.scan_container_cached(str(path))
                self.assertFalse(cached)
                self.assertEqual((len(items), total), (1, 1))
                self.assertEqual(items[0]["streams"][0]["ch"], 1)
                self.assertEqual(awc_core.scan_container_cached(str(path)), (items, total, True))

    def test_nonmatching_partial_skip_does_not_drop_a_whole_packet(self):
        raw = _build_synthetic_music_awc([
            (0, 1920, [b"\xfcA"]),
            (960, 1920, [b"\xfcB"]),
        ])
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "bad_skip.awc")
            output = os.path.join(tmp, "out.opus")
            with open(path, "wb") as f:
                f.write(raw)
            awc = awc_core.AwcFile(path)
            with self.assertRaises(awc_core.AwcError):
                awc.extract({0: output})
            self.assertFalse(os.path.exists(output))
            Path(output).write_bytes(b"previous complete output")
            with self.assertRaises(awc_core.AwcError):
                awc.extract({0: output})
            self.assertEqual(Path(output).read_bytes(), b"previous complete output")

    def test_empty_block_may_not_claim_a_skip(self):
        packet = b"\xfcA"
        for skip in (0, 1920):
            with self.subTest(skip=skip), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "empty.awc"
                path.write_bytes(_build_synthetic_music_awc([(0, 1920, [packet]), (skip, 0, [])]))
                output = Path(tmp) / "out.opus"
                awc = awc_core.AwcFile(path)
                if skip:
                    with self.assertRaisesRegex(awc_core.AwcError, "skip without packets"):
                        awc.extract({0: output})
                else:
                    awc.extract({0: output})
                    self.assertEqual(_extract_ogg_audio_payload(output), packet)

    def test_first_packet_after_empty_block_sets_mono_budget(self):
        p1, p2 = b"\xf8A", b"\xf8B"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "mono.awc"
            path.write_bytes(_build_synthetic_music_awc([(0, 0, []), (0, 1920, [p1, p2])]))
            awc = awc_core.AwcFile(path)
            output = Path(tmp) / "out.opus"
            awc.extract({0: output})
            self.assertEqual(_extract_ogg_audio_payload(output), p1 + p2)
            self.assertEqual(awc.streams[0].opus_channels, 1)
            self.assertAlmostEqual(awc.streams[0].est_seconds, 0.04)

    def test_channel_inference_finds_first_packet_in_each_stream(self):
        stereo = [(0, 1920, [b"\xfcA"]), (0, 1920, [b"\xfcB"])]
        mono = [(0, 0, []), (0, 1920, [b"\xf8A", b"\xf8B"])]
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "two-streams.awc"
            path.write_bytes(_build_synthetic_music_awc(stereo, second_stream=mono))
            awc = awc_core.AwcFile(path)
            self.assertEqual([stem.opus_channels for stem in awc.streams], [2, 1])
            output = Path(tmp) / "mono.opus"
            awc.extract({1: output})
            self.assertEqual(_extract_ogg_audio_payload(output), b"\xf8A\xf8B")

    def test_skip_cannot_use_overlap_or_deficit_from_before_empty_block(self):
        p1, p2 = b"\xfcA", b"\xfcB"
        for budget, packet in ((1920, p1), (3840, p2)):
            with self.subTest(budget=budget), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "stale.awc"
                path.write_bytes(_build_synthetic_music_awc([(0, budget, [p1]), (0, 0, []),
                                                            (1920, 1920, [packet])]))
                with self.assertRaisesRegex(awc_core.AwcError, "unverified skip"):
                    awc_core.AwcFile(path).extract({0: Path(tmp) / "out.opus"})

    def test_empty_block_with_positive_budget_records_deficit(self):
        p1, p2 = b"\xfcA", b"\xfcB"
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "deficit.awc"
            path.write_bytes(_build_synthetic_music_awc([(0, 1920, [p1]), (0, 1920, []),
                                                        (1920, 1920, [p2])]))
            output = Path(tmp) / "out.opus"
            awc_core.AwcFile(path).extract({0: output})
            self.assertEqual(_extract_ogg_audio_payload(output), p1 + p2)

    def test_trims_block_budget_padding_and_deduplicates_interleaved_skip(self):
        # Stereo 20 ms Opus packets (TOC 0xFC) = 960 frames = 1920 interleaved samples each.
        p1, p2, pad, p3 = b"\xfcA", b"\xfcB", b"\xfcP", b"\xfcC"
        raw = _build_synthetic_music_awc(
            [
                (0, 3840, [p1, p2, pad]),     # budget=2 stereo packets -> trims `pad`
                (1920, 3840, [p2, p3]),       # interleaved skip=1920 -> drops repeated `p2`
            ],
            declared_samples=5760,
        )
        with tempfile.TemporaryDirectory() as tmp:
            awc_path = os.path.join(tmp, "test.awc")
            out_path = os.path.join(tmp, "out.opus")
            with open(awc_path, "wb") as f:
                f.write(raw)
            awc = awc_core.AwcFile(awc_path)
            awc.extract({0: out_path})
            self.assertEqual(_extract_ogg_audio_payload(out_path), p1 + p2 + p3)

    def test_short_block_deficit_carries_into_next_skip(self):
        p1, p2, p3, p4 = b"\xfc1", b"\xfc2", b"\xfc3", b"\xfc4"
        raw = _build_synthetic_music_awc(
            [
                (0, 5760, [p1, p2]),          # 2880 target frames vs 1920 actual -> 960-frame deficit
                (1920, 3840, [p3, p4]),       # skip_f=960 == prev_deficit -> net_skip=0, keep all
            ],
            declared_samples=7680,
        )
        with tempfile.TemporaryDirectory() as tmp:
            awc_path = os.path.join(tmp, "deficit.awc")
            out_path = os.path.join(tmp, "out.opus")
            with open(awc_path, "wb") as f:
                f.write(raw)
            awc = awc_core.AwcFile(awc_path)
            awc.extract({0: out_path})
            self.assertEqual(_extract_ogg_audio_payload(out_path), p1 + p2 + p3 + p4)

    def test_positive_deficit_remainder_must_end_on_a_packet_boundary(self):
        p1, p2, p3 = b"\xfcA", b"\xfcB", b"\xfcC"
        for skip in (3840, 2880, 7680):
            with self.subTest(skip=skip), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "deficit.awc"
                path.write_bytes(_build_synthetic_music_awc([(0, 3840, [p1]), (skip, 3840, [p2, p3])]))
                output = Path(tmp) / "out.opus"
                awc = awc_core.AwcFile(path)
                if skip == 3840:  # net 960 frames after subtracting the 960-frame deficit
                    awc.extract({0: output})
                    self.assertEqual(_extract_ogg_audio_payload(output), p1 + p3)
                else:
                    with self.assertRaisesRegex(awc_core.AwcError, "skip cuts through or exceeds"):
                        awc.extract({0: output})


class AudMetadataTests(unittest.TestCase):
    def test_external_tracklist_ends_after_its_list_reference(self):
        tracklist, object_list, track, sound = 1, 2, 3, 4
        blob = _build_aud_metadata_blob([
            (tracklist, b"\x11" + bytes(67) + struct.pack("<I", 0)),
            (object_list, b"\x02" + bytes(7) + struct.pack("<II", 1, 1)),
            (track, b"\x0f" + bytes(11) + struct.pack("<I", 2)),
        ], refs=[object_list, track, sound])
        meta = awc_metadata.parse_aud_metadata(blob)
        refs = awc_metadata.parse_aud_refs(meta)
        self.assertEqual(awc_metadata.walk_tracklist_tracks(tracklist, meta, refs), [(track, sound)])

    def test_reference_arrays_cannot_run_into_next_object(self):
        station_list = joaat("RADIO_STATIONS_01")
        station, tracklist, object_list, following = 1, 2, 3, 4
        station_ref = b"\x0e\0\0\0\0\x01\0\0" + struct.pack("<I", 0)
        station_header = b"\x10" + bytes(59)
        tracklist_header = b"\x11" + bytes(67)
        sounds_meta = awc_metadata.parse_aud_metadata(_build_aud_metadata_blob([]))
        cases = [
            ([(station_list, station_ref[:8])], "station ref list truncated"),
            ([(station_list, station_ref), (station, station_header + struct.pack("<I", 1))],
             "tracklist array truncated"),
            ([(tracklist, tracklist_header + struct.pack("<IHH", 0xFFFFFFFF, 1, 0))],
             "inline reference array truncated"),
            ([(tracklist, tracklist_header + struct.pack("<IHH", 1, 0, 0)),
              (object_list, b"\x02" + bytes(7) + struct.pack("<I", 1))],
             "object list .* truncated"),
        ]
        for objects, error in cases:
            with self.subTest(error=error):
                game_blob = _build_aud_metadata_blob(objects + [(following, bytes(12))],
                                                     refs=[station, object_list])
                with self.assertRaisesRegex(ValueError, error):
                    awc_metadata.parse_radio_metadata(awc_metadata.parse_aud_metadata(game_blob), sounds_meta)

    def test_omits_streaming_sound_with_incomplete_adjacent_children(self):
        sound_hash = joaat("incomplete")
        sound = b"\x08\0\0\0" + struct.pack("<I", 1000) + b"\x0c\x02" + struct.pack("<II", 1, 2)
        child = b"\x0c\0" + struct.pack("<II", 0x11111111, 0x123)
        blob = _build_aud_metadata_blob([(sound_hash, sound), (joaat("child"), child)])
        self.assertEqual(awc_metadata.parse_sounds_streaming_index(awc_metadata.parse_aud_metadata(blob)), {})

    def test_parses_wheel_stations_hidden_static_emitters_multichannel_and_script_tracklists(self):
        # Build SOUNDS.DAT: 1 multi-channel StreamingSound (2 SimpleSounds) + 1 single-channel StreamingSound
        snd_multi_h = joaat("snd_multi_mix")
        snd_single_h = joaat("snd_single_track")
        snd_script_h = joaat("snd_script_track")
        ch1_h, ch2_h = joaat("ch_a"), joaat("ch_b")
        ch3_h, ch4_h = joaat("ch_c"), joaat("ch_d")

        def streaming_obj(dur_ms, child_count):
            # type=0x08, filler, duration_ms, 0x0C, child_count, child_count * u32 child_ids
            return (bytes([awc_metadata.TYPE_STREAMING_SOUND, 0, 0, 0])
                    + struct.pack("<I", dur_ms)
                    + bytes([awc_metadata.TYPE_SIMPLE_SOUND, child_count])
                    + struct.pack(f"<{child_count}I", *range(1, child_count + 1)))

        def simple_obj(bank_hash, wave_hash):
            return bytes([awc_metadata.TYPE_SIMPLE_SOUND, 0x00]) + struct.pack("<II", bank_hash, wave_hash)

        sounds_blob = _build_aud_metadata_blob([
            (snd_multi_h, streaming_obj(240000, 2)),
            (ch1_h, simple_obj(0x11111111, 0x80001001)),
            (ch2_h, simple_obj(0x11111111, 0x80001002)),
            (snd_single_h, streaming_obj(180000, 1)),
            (ch3_h, simple_obj(0x22222222, 0xA0002001)),
            (snd_script_h, streaming_obj(120000, 1)),
            (ch4_h, simple_obj(0x33333333, 0xC0003001)),
        ])

        # Build GAME.DAT: RADIO_STATIONS_01 -> 3 stations (normal wheel, hidden wheel, static emitter)
        # + 1 unattached script tracklist
        rs01_h = joaat("RADIO_STATIONS_01")
        st_wheel_h = joaat("st_wheel")
        st_hidden_h = joaat("st_hidden")
        st_static_h = joaat("st_static")
        tl_wheel_h = joaat("tl_wheel")
        tl_static_h = joaat("tl_static")
        tl_script_h = joaat("tl_script")
        ol_wheel_h = joaat("ol_wheel")
        tr_multi_h = joaat("tr_multi")
        tr_single_h = joaat("tr_single")
        tr_script_h = joaat("tr_script")

        refs = [
            st_wheel_h,   # 0
            st_hidden_h,  # 1
            st_static_h,  # 2
            tl_wheel_h,   # 3
            tl_static_h,  # 4
            ol_wheel_h,   # 5
            tr_multi_h,   # 6
            tr_single_h,  # 7
            tr_script_h,  # 8
            snd_multi_h,  # 9
            snd_single_h, # 10
            snd_script_h, # 11
        ]

        rs01_obj = bytes([awc_metadata.TYPE_RADIO_STATION_LIST, 0, 0, 0, 0, 3, 0, 0]) + struct.pack("<3I", 0, 1, 2)

        def station_obj(flags, name_hash, wheel_pos, tl_ref_indices):
            buf = bytearray(0x40)
            buf[0] = awc_metadata.TYPE_RADIO_STATION
            struct.pack_into("<I", buf, 1, flags)
            struct.pack_into("<II", buf, 8, name_hash, wheel_pos)
            struct.pack_into("<I", buf, 0x3C, len(tl_ref_indices))
            for r in tl_ref_indices:
                buf += struct.pack("<III", r, 0, 0)
            return bytes(buf)

        def tracklist_obj(ext_ol_ref, inline_refs):
            buf = bytearray(0x4C)
            buf[0] = awc_metadata.TYPE_RADIO_TRACKLIST
            struct.pack_into("<I", buf, 0x44, ext_ol_ref)
            struct.pack_into("<H", buf, 0x48, len(inline_refs))
            for r in inline_refs:
                buf += struct.pack("<I", r)
            return bytes(buf)

        ol_obj = (bytes([awc_metadata.TYPE_OBJECT_LIST]) + bytes(7)
                  + struct.pack("<II", 1, 6))  # count=1, ref=tr_multi_h

        def track_obj(sound_ref):
            buf = bytearray(0x10)
            buf[0] = awc_metadata.TYPE_RADIO_TRACK
            struct.pack_into("<I", buf, 0x0C, sound_ref)
            return bytes(buf)

        game_blob = _build_aud_metadata_blob(
            [
                (rs01_h, rs01_obj),
                (st_wheel_h, station_obj(0x00, 0xAAAA0001, 2, [3])),
                (st_hidden_h, station_obj(0x04, 0xAAAA0002, 1, [])),
                (st_static_h, station_obj(0x10, 0xAAAA0003, 99, [4])),
                (tl_wheel_h, tracklist_obj(5, [8])),  # external list takes precedence
                (tl_static_h, tracklist_obj(awc_metadata.OBJECT_REF_NONE, [7])),
                (tl_script_h, tracklist_obj(awc_metadata.OBJECT_REF_NONE, [8])),
                (ol_wheel_h, ol_obj),
                (tr_multi_h, track_obj(9)),
                (tr_single_h, track_obj(10)),
                (tr_script_h, track_obj(11)),
            ],
            refs=refs,
        )

        game_meta = awc_metadata.parse_aud_metadata(game_blob)
        sounds_meta = awc_metadata.parse_aud_metadata(sounds_blob)
        self.assertIsNotNone(game_meta)
        self.assertIsNotNone(sounds_meta)

        result = awc_metadata.parse_radio_metadata(game_meta, sounds_meta)
        self.assertEqual(len(result["stations"]), 2)
        self.assertEqual([s["wheel_position"] for s in result["stations"]], [1, 2])
        self.assertTrue(result["stations"][0]["hidden"])
        self.assertFalse(result["stations"][1]["hidden"])
        # Multi-channel StreamingSound resolves both 29-bit stream IDs on wheel station
        self.assertEqual(len(result["stations"][1]["tracks"]), 1)
        self.assertEqual(result["stations"][1]["tracks"][0]["stream_ids"], [0x00001001, 0x00001002])
        self.assertEqual(result["stream_to_stations"], {0x00001001: [st_wheel_h], 0x00001002: [st_wheel_h]})
        # Static emitter separated from wheel stations
        self.assertEqual(len(result["static_emitters"]), 1)
        self.assertEqual(result["static_emitters"][0]["tracks"][0]["stream_ids"], [0x00002001])
        # Unattached script tracklist classified separately
        self.assertEqual(len(result["script_tracklists"]), 1)
        self.assertEqual(result["script_tracklists"][0]["tracks"][0]["stream_ids"], [0x00003001])

    def test_ignores_non_audmetadata_and_rejects_truncated_blob(self):
        self.assertIsNone(awc_metadata.parse_aud_metadata(b"\x00" * 32))
        bad = struct.pack("<III", 1, 0x4000, awc_metadata.AUD_METADATA_MAGIC) + b"\x00" * 16
        with self.assertRaises(ValueError):
            awc_metadata.parse_aud_metadata(bad)


if __name__ == "__main__":
    unittest.main()
