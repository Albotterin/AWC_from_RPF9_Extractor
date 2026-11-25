"""Runs the JVM tests of the Android code (no Android needed): the programme must be identical to awc_radio.Schedule and
Engine (the playback logic) is simulated with fake players and a fake clock.

    python run_tests.py
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
JAVA = os.path.join(os.path.dirname(HERE), "java")
OUT = os.path.join(HERE, "build")


def main():
    os.makedirs(OUT, exist_ok=True)
    srcs = [os.path.join(JAVA, "org", "awctool", "radio", n) for n in ("Schedule.java", "Engine.java")]
    srcs.append(os.path.join(HERE, "org", "awctool", "radio", "EngineTest.java"))
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-d", OUT] + srcs)
    if r.returncode:
        sys.exit(1)
    r = subprocess.run(["java", "-cp", OUT, "org.awctool.radio.EngineTest"])
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
