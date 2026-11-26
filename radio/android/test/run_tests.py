"""Runs the JVM tests of the Android code (no Android needed):

- the programme must be identical to awc_radio.Schedule and Engine (the playback logic) is simulated with fake players and
  a fake clock,
- the rules of the alarm (next ring, snooze, restart, time zones) and the station colours,
- Pack and Catalog read station packs written by awc_android.py: merging, updating, damaged files. This needs org.json
  for the PC (json.jar): set AWC_JSON_JAR, default <Android SDK>/test/json.jar.

    python run_tests.py [folder with real pack files (.radiopak, .podpak) to check as well]

The screens and the alarm on Android itself are tested by run_robo.py.
"""
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(os.path.dirname(HERE), "java")
OUT = os.path.join(HERE, "build")


def find_python_modules():
    """awc_android.py lies two folders up (flat layout) or in radio/ (repository layout)."""
    up = os.path.dirname(os.path.dirname(HERE))
    for d in (up, os.path.join(os.path.dirname(up), "extractor")):
        if os.path.isdir(d):
            sys.path.insert(0, d)
    sys.path.insert(0, up)


def write_pack(a, path, name, created, title, talk=False):
    song = {"base": 10, "idx": 0, "title": title, "artist": "x", "sec": 100.0}
    if talk:
        song["parts"] = [[10, 0], [11, 0]]
    st = {"songs": [song, dict(song, base=20, parts=None)], "jingles": [{"base": 30, "idx": 0, "sec": 5.0}]}
    writer = a.PackWriter(path)
    entry = a.station_entry(name, st, True)
    for item in entry["songs"] + entry["jingles"]:
        writer.add_bytes("t/" + item["k"], b"OggS" + item["k"].encode())
    writer.add_bytes(entry["logo"], b"\x89PNG-logo")
    writer.finish({"v": 1, "created": created, "sig": name + created, "stations": [entry]})


def make_score_folder(a, root):
    """Two score packs (and an older one of the same group) as awc_android writes them, with stand-in audio."""
    folder = os.path.join(root, "score")
    os.makedirs(folder)
    piece = lambda i, sub, title: {"id": i, "group": "x", "sub": sub, "title": title, "sec": 40.0, "parts": []}
    for name, group, created, pieces in (
            ("score_missions.scorepak", "mission", "2026-02-01T00:00:00Z", [piece("m1", "ROB Bank", "new piece"), piece("m2", "ROB Boat", "b")]),
            ("score_old_missions.scorepak", "mission", "2026-01-01T00:00:00Z", [piece("m0", "ROB Bank", "old piece")]),
            ("score_open_world.scorepak", "openworld", "2026-01-01T00:00:00Z", [piece("o1", "Events", "a")])):
        entry = a.station_entry("Score: " + group, {"score": group, "pieces": pieces}, False)
        writer = a.PackWriter(os.path.join(folder, name))
        for item in entry["pieces"]:
            writer.add_bytes("t/" + item["k"], b"OggS" + item["k"].encode())
        writer.finish({"v": 1, "created": created, "sig": name, "stations": [], "scores": [entry]})
        assert a.read_signature(os.path.join(folder, name)) == name
    return folder


def make_folders():
    find_python_modules()
    import awc_android as a
    root = os.path.join(OUT, "packs%d" % int(time.time() * 1000))
    one, two = os.path.join(root, "a"), os.path.join(root, "b")
    os.makedirs(one)
    os.makedirs(two)
    old, new = "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z"
    p = lambda d, n: os.path.join(d, n)
    write_pack(a, p(one, "alpha.radiopak"), "Alpha", new, "new song")
    write_pack(a, p(one, "z_old_alpha.radiopak"), "Alpha", old, "old song")           # later name, but made earlier
    write_pack(a, p(one, "the_bravo.radiopak"), "The Bravo", old, "bravo")
    write_pack(a, p(one, "charlie_talk.radiopak"), "Charlie Talk", old, "show", talk=True)
    write_pack(a, p(one, "clubmixes.radiopak"), "Clubmixes", old, "mix")
    write_pack(a, p(one, "half.radiopak.part"), "Half", old, "x")
    write_pack(a, p(one, "old_style.dat"), "Old", old, "x")
    with open(p(one, "alpha.radiopak"), "rb") as f:
        data = f.read()
    with open(p(one, "broken.radiopak"), "wb") as f:                                  # still being copied
        f.write(data[:len(data) // 2])
    assert a.read_signature(p(one, "alpha.radiopak")) == "Alpha" + new and a.read_signature(p(one, "broken.radiopak")) == ""
    write_pack(a, p(two, "alpha.radiopak"), "Alpha", "2026-03-01T00:00:00Z", "updated song")
    shutil.copyfile(p(one, "charlie_talk.radiopak"), p(two, "charlie_talk.radiopak"))
    write_pack(a, p(two, "delta.radiopak"), "Delta", new, "delta")
    return one, two, make_score_folder(a, root)


def main():
    os.makedirs(OUT, exist_ok=True)
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    json_jar = os.environ.get("AWC_JSON_JAR") or os.path.join(sdk, "test", "json.jar")
    sources = lambda *n: [os.path.join(JAVA, "org", "awctool", "radio", x) for x in n]
    tests = lambda n: os.path.join(HERE, "org", "awctool", "radio", n)
    srcs = sources("Schedule.java", "Engine.java", "Episodes.java") + [tests("EngineTest.java"), tests("EpisodesTest.java")]
    catalog = os.path.isfile(json_jar)
    if catalog:
        srcs += sources("Library.java", "Pack.java", "Catalog.java", "Alarm.java", "StationColor.java")
        srcs += [tests("ProgramDump.java")]
        srcs += [tests("CatalogTest.java"), tests("AlarmTest.java")]
    cp = OUT + (os.pathsep + json_jar if catalog else "")
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", OUT] + srcs)
    if r.returncode:
        sys.exit(1)
    for name in ("EngineTest", "EpisodesTest"):
        r = subprocess.run(["java", "-cp", cp, "org.awctool.radio." + name])
        if r.returncode:
            sys.exit(r.returncode)
    if not catalog:
        print("Catalog and alarm tests skipped: no json.jar at " + json_jar)
        return
    r = subprocess.run(["java", "-cp", cp, "org.awctool.radio.AlarmTest"])
    if r.returncode:
        sys.exit(r.returncode)
    one, two, score = make_folders()
    runs = [["synthetic", one, two], ["score", score]] + [["real", d] for d in sys.argv[1:]]
    for args in runs:
        r = subprocess.run(["java", "-cp", cp, "org.awctool.radio.CatalogTest"] + args)
        if r.returncode:
            sys.exit(r.returncode)
    for folder in sys.argv[1:]:
        compare_programmes(cp, folder)


def compare_programmes(cp, folder):
    """The app and the PC must put the same item on air at the same moment, with and without adverts, news and talk, by
    day and by night."""
    import json
    import struct

    find_python_modules()
    for d in (os.path.join(os.path.dirname(os.path.dirname(HERE)), "radio"),):
        if os.path.isdir(d):
            sys.path.insert(0, d)
    import awc_radio as radio
    moments = [1795000000.0, 1795046399.5, 1795132800.0, time.time(), 1811111111.25]
    moments += [time.mktime((2026, m, d, h, 40, 0, 0, 0, -1)) for m, d in ((11, 26), (3, 28), (10, 31)) for h in (21, 1, 5)]
    out = os.path.join(OUT, "programme.txt")
    r = subprocess.run(["java", "-cp", cp, "org.awctool.radio.ProgramDump", folder, out] + [repr(m) for m in moments])
    if r.returncode:
        sys.exit(r.returncode)

    def track(entry):
        base, rest = entry["k"].split("_", 1)
        idx, _x, parts = rest.partition("x")
        tr = {"base": int(base), "idx": int(idx), "sec": entry["s"], "title": entry.get("t", "")}
        if parts:
            tr["parts"] = [0] * int(parts)
            tr["set"] = not talk                       # made of parts: a talk show, or a club's DJ set
        return tr

    def index_of(name):
        with open(os.path.join(folder, name), "rb") as f:
            offset, length = struct.unpack_from("<QQ", f.read(32), 16)
            f.seek(offset)
            return json.loads(f.read(length))

    news = []
    for name in sorted(f for f in os.listdir(folder) if f.endswith(".newspak")):
        news = [track(x) for x in index_of(name)["news"][0]["items"]]

    want = []
    spoken_radio = set()                          # talk radio: talk is what it plays, with or without the switch
    for name in sorted(f for f in os.listdir(folder) if f.endswith(".radiopak")):
        stations = index_of(name)["stations"]
        if not stations:
            continue                                   # a podcast pack has no programme
        st = stations[0]
        talk = st.get("kind") == "talk"
        if st.get("rules", {}).get("talkradio"):
            spoken_radio.add(st["name"])
        station = {"songs": [track(x) for x in st["songs"]], "jingles": [track(x) for x in st["jingles"]]}
        for key in ("ads", "talk"):
            station[key] = [track(x) for x in st.get(key, [])]
        station["news"] = st.get("news", False)
        if "rules" in st:
            station["rules"] = st["rules"]
            talk = False                               # no recording of a ruled station is a whole show
        spoken = {x["k"]: [int(x["k"].split("_")[0]), int(x["k"].split("_")[1]), x["s"]] for x in st.get("voices", [])}
        for entry, own in zip(st["songs"], station["songs"]):
            if "dj" in entry:
                own["dj"] = {w: [v[0], v[1], [spoken[k] for k in v[2]]] for w, v in entry["dj"].items()}
        station["toads"] = [spoken[k] for k in st.get("toads", [])]
        station["tonews"] = [spoken[k] for k in st.get("tonews", [])]
        programmes = [("%s%d" % (tag, fade), radio.Schedule(st["name"], station, tag == "ads", fade, news))
                      for tag in ("no", "ads") for fade in (0, 3, 8)]
        for i, now in enumerate(moments):
            for tag, schedule in programmes:
                for start, end, kind, tr, fin, fout in schedule.items_from(now, 40):
                    want.append("%s|%d|%s|%s|%s|%.6f|%.6f|%.6f|%.6f" % (st["name"], i, tag, kind, radio._tkey(tr), start, end, fin, fout))
                    for at, line in schedule.voices((start, end, kind, tr, fin, fout)):
                        want.append("%s|%d|%s|voice|%s_%s|%.6f|%.6f" % (st["name"], i, tag, line[0], line[1], start, at))
    with open(out, encoding="utf-8") as f:
        got = f.read().splitlines()
    got.sort()
    want.sort()
    ads = sum(1 for x in want if "|ad|" in x)
    kinds = {k: sum(1 for x in want if "|%s|" % k in x) for k in ("news", "talk")}
    said = sum(1 for x in want if "|voice|" in x)
    bad = [(a, b) for a, b in zip(got, want) if a != b]
    if len(got) != len(want) or bad:
        print("programme differs: %d lines from the app, %d from the PC; first: %s" % (len(got), len(want), bad[:3]))
        sys.exit(1)
    leaked = sum(1 for x in want if x.split("|")[2].startswith("no")
                 and x.split("|")[3] in (("ad", "news") if x.split("|")[0] in spoken_radio else ("ad", "news", "talk")))
    if leaked or not ads:
        print("adverts: %d in the full programme, %d adverts/news/talk in the plain one" % (ads, leaked))
        sys.exit(1)
    print("Programme: app and PC identical (%d lines compared: %d adverts, %d news, %d talk, %d DJ lines; none of the first "
          "three in the plain programme)" % (len(want), ads, kinds["news"], kinds["talk"], said))


if __name__ == "__main__":
    main()
