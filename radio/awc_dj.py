"""The DJ's intros and outros over the songs, the way the game's files mark them.

Every song bank carries marker events; the four events of type "dj" are two windows: where the DJ may talk over the
beginning of the song and over its end. The DJ's lines lie in speech containers (awc_speech) and are named after the song
they announce ("dj_intro_<song>_01", "dj_outro_<song>_01"); some are general. collect() gives every song of a station the
lines that fit into its windows; which one is said, and whether at all, is decided by the programme (awc_radio.Schedule
and Schedule.java, from the UTC clock). mix() lays lines over a song for the PC (the app does it while playing).
"""
import collections
import os
import struct
import subprocess

import awc_core as core
import awc_gamedata as gamedata
import awc_speech as speech
from awc_names import STREAM_ID_MASK, joaat

MARKERS = 0xBD
DJ_EVENT = joaat("dj")
SLACK = 0.6                  # a line may be this much longer than its window
DUCK = 0.25                  # how loud the music stays while the DJ talks (Engine.java: the same)
RAMP = 0.35                  # seconds the music takes to step back and forward again
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def windows(f, base):
    """[(start, end)] where the DJ may talk over the song bank at `base` of the open archive: the intro, then the outro."""
    f.seek(base)
    head = f.read(16)
    if len(head) < 16 or head[:4] != b"ADAT":
        return []
    flags, count, end = struct.unpack_from("<HII", head, 6)
    if not 16 <= end <= 0x10000:
        return []
    f.seek(base)
    head = f.read(end)
    for places in core._chunk_places(head, flags, count, end):
        for i in places:
            v = struct.unpack_from("<Q", head, i)[0]
            offset, size = v & 0xFFFFFFF, (v >> 28) & 0xFFFFFFF
            if v >> 56 == MARKERS and 16 <= size <= 0x100000:
                f.seek(base + offset)
                data = f.read(size)
                times = sorted(struct.unpack_from("<I", data, k + 8)[0] / 48000.0 for k in range(0, len(data) - 15, 16)
                               if struct.unpack_from("<I", data, k)[0] == DJ_EVENT)
                return [(round(a, 2), round(b, 2)) for a, b in zip(times[0::2], times[1::2]) if b > a]
    return []


def song_names(rpf):
    """{stream ID: {names the config writes for that song, lower case}} - what the lines are named after."""
    files = gamedata.config_files(rpf)
    out = collections.defaultdict(set)
    for tracks in gamedata.catalog(files).values():
        for t in tracks:
            if t.title:
                out[t.wave[1] & STREAM_ID_MASK].add(t.title.lower())
    for lists in gamedata.parse(files).values():
        for found in lists:
            for t in found:
                if t.title:
                    out[t.wave[1] & STREAM_ID_MASK].add(t.title.lower())
    return out


def songs_of(station):
    """The single songs of a station (talk radio has none)."""
    if station.get("rules", {}).get("talkradio"):
        return []
    return [x for x in station.get("songs", []) if not x.get("parts")]


def assign(containers, lines, stations, names):
    """{station: [(scan item, stream)]}: whose DJ speaks in which container - the station whose songs the container's
    lines announce, else the one around it in the archive."""
    plays = collections.defaultdict(set)                     # song name -> stations
    where = []
    for station, st in stations.items():
        for song in songs_of(st):
            where.append((song["base"], station))
            for n in names.get(song["sid"] & STREAM_ID_MASK, ()):
                plays[n].add(station)
    where.sort()
    out = collections.defaultdict(list)
    for item in containers:
        votes = collections.Counter(st for s in item["streams"] for st in plays.get(lines.get(s["id"], ("", "", 0))[1], ()))
        if votes:
            station = votes.most_common(1)[0][0]
        else:
            before = [st for base, st in where if base < item["base"]]
            station = before[-1] if before else None
        if station:
            out[station].extend((item, s) for s in item["streams"])
    return out


def collect(rpf, items, stations):
    """Give the songs of the stations their DJ windows and lines (song["dj"], station["toads"], station["tonews"]).

    -> {position: scan item} of the speech containers that are used."""
    songs = [s for st in stations.values() for s in songs_of(st)]
    if not songs:
        return {}
    names = song_names(rpf)
    bases = sorted({s["base"] for s in songs})
    containers = speech.find(rpf, items, bases[0], bases[-1])
    words = set().union(*names.values()) if names else set()
    lines = speech.line_names([s["id"] for c in containers for s in c["streams"]], words)
    owned = assign(containers, lines, stations, names)
    used, marked = {}, {}
    with open(rpf, "rb") as f:
        for station, st in stations.items():
            mine = owned.get(station)
            if not mine:
                continue
            by = collections.defaultdict(list)              # (kind, song) -> [(item, stream)]
            for item, s in mine:
                kind, song, _n = lines.get(s["id"], ("", "", 0))
                if kind:
                    by[(kind, song)].append((item, s))

            def entry(item, s):
                used[item["base"]] = item
                return [item["base"], s["i"], s["sec"]]

            pools = st.get("rules", {}).get("pools", {})
            night = ("music_night", "music_night2") if "showintro_night" in pools else ()
            hosted = {key for category in night for key in pools.get(category, ())}
            for song in songs_of(st):
                if song["base"] not in marked:
                    marked[song["base"]] = windows(f, song["base"])
                dj = {}
                for key, kind, window in zip("io", ("intro", "outro"), marked[song["base"]]):
                    own = [x for n in names.get(song["sid"] & STREAM_ID_MASK, ()) for x in by.get((kind, n), ())]
                    room = window[1] - window[0] + SLACK
                    # the lines about this song; the general ones of the station where none of those fits
                    # (a night show has a host of its own: the day DJ's general lines are not said over its songs)
                    general = () if "%s_%s" % (song["base"], song["idx"]) in hosted else by.get((kind, ""), ())
                    fit = [x for x in own if x[1]["sec"] <= room] or [x for x in general if x[1]["sec"] <= room]
                    if fit:
                        fit.sort(key=lambda x: (x[0]["base"], x[1]["i"]))
                        dj[key] = [window[0], window[1], [entry(*x) for x in fit]]
                if dj:
                    song["dj"] = dj
            for key, kind, wanted in (("toads", "to_ads", st.get("ads")), ("tonews", "to_news", st.get("news"))):
                found = sorted(by.get((kind, ""), ()), key=lambda x: (x[0]["base"], x[1]["i"]))
                if wanted and found and any("dj" in s for s in songs_of(st)):
                    st[key] = [entry(*x) for x in found]
    return used


def line_track(line):
    """A line of the index ([bank position, stream, seconds]) as a track that can be extracted."""
    return {"base": line[0], "idx": line[1], "sec": line[2], "title": "", "artist": ""}


def station_lines(station):
    """Every line of a station as a track ([bank position, stream, seconds] once each)."""
    found = {}
    for line in ([x for s in songs_of(station) for w in s.get("dj", {}).values() for x in w[2]] + station.get("toads", [])
                 + station.get("tonews", [])):
        found[(line[0], line[1])] = line
    return [line_track(found[k]) for k in sorted(found)]


def duck_filter(spans):
    """FFmpeg volume expression: DUCK inside every (start, end), with ramps around it."""
    parts = ["clip((t-%.3f)/%.3f,0,1)*clip((%.3f-t)/%.3f,0,1)" % (a - RAMP, RAMP, b + RAMP, RAMP) for a, b in spans]
    return "1-%.3f*min(1,%s)" % (1.0 - DUCK, "+".join(parts))


def mix(song, voices, out, ffmpeg):
    """Lay the DJ's lines over a song: voices = [(second in the song, file of the line, its length)]. Raises OSError."""
    cmd = [ffmpeg, "-v", "error", "-y", "-i", song]
    for _at, path, _sec in voices:
        cmd += ["-i", path]
    graph = ["[0:a]aresample=48000,aformat=channel_layouts=stereo,volume=eval=frame:volume='%s'[m]"
             % duck_filter([(at, at + sec) for at, _p, sec in voices])]
    for n, (at, _path, _sec) in enumerate(voices, 1):
        graph.append("[%d:a]aresample=48000,aformat=channel_layouts=stereo,adelay=%d:all=1[v%d]" % (n, int(at * 1000), n))
    graph.append("[m]%samix=inputs=%d:duration=first:normalize=0,alimiter=limit=0.89:level=disabled[o]"
                 % ("".join("[v%d]" % n for n in range(1, len(voices) + 1)), len(voices) + 1))
    r = subprocess.run(cmd + ["-filter_complex", ";".join(graph), "-map", "[o]", "-c:a", "libopus", "-b:a", "128k", "-f", "ogg", out],
                       capture_output=True, stdin=subprocess.DEVNULL, creationflags=NO_WINDOW)
    if r.returncode != 0 or not os.path.isfile(out):
        raise OSError("ffmpeg: " + r.stderr.decode("utf-8", "replace")[-300:])
