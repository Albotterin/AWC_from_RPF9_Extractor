"""Runs the Android tests that need Android itself (screens, alarm appointments, the ringing service) on the PC with
Robolectric, and writes screenshots of all screens.

Needs: a built APK (python android/build_apk.py first: its build/ folder is used), a folder with real .radiopak files,
and the test libraries in <SDK>/test/robolectric (org.robolectric:robolectric and the android-all-instrumented jar from
Maven Central; set AWC_ROBOLECTRIC to use another folder).

    python run_robo.py <folder with .radiopak files> [folder for the screenshots] [test class names ...]
"""
import glob
import os
import shutil
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ANDROID = os.path.dirname(HERE)
SDK = os.environ.get("ANDROID_SDK_ROOT") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
LIBS = os.environ.get("AWC_ROBOLECTRIC") or os.path.join(SDK, "test", "robolectric")
BUILD = os.path.join(ANDROID, "build")
OUT = os.path.join(HERE, "build", "robo")


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    packs = os.path.abspath(sys.argv[1])
    shots = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.join(OUT, "shots")
    for need in (os.path.join(BUILD, "base.apk"), os.path.join(BUILD, "classes"), LIBS):
        if not os.path.exists(need):
            raise SystemExit("missing: " + need)
    shutil.rmtree(OUT, ignore_errors=True)
    classes, res = os.path.join(OUT, "classes"), os.path.join(OUT, "res")
    tmp = os.path.join(OUT, "tmp%d" % int(time.time()))
    for d in (classes, os.path.join(res, "com", "android", "tools"), tmp, shots):
        os.makedirs(d, exist_ok=True)
    fwd = lambda p: p.replace("\\", "/")
    with open(os.path.join(res, "com", "android", "tools", "test_config.properties"), "w") as f:
        f.write("android_merged_manifest=%s\nandroid_resource_apk=%s\nandroid_merged_assets=%s\nandroid_custom_package=org.awctool.radio\n"
                % (fwd(os.path.join(ANDROID, "AndroidManifest.xml")), fwd(os.path.join(BUILD, "base.apk")),
                   fwd(os.path.join(ANDROID, "assets"))))
    jars = [j for j in glob.glob(os.path.join(LIBS, "*.jar"))
            if not os.path.basename(j).startswith("android-all")]
    # android.jar last: JUnit needs the names to load the test classes; Robolectric brings the real Android itself
    jars.append(os.path.join(SDK, "platforms", "android-34", "android.jar"))
    cp = os.pathsep.join([classes, os.path.join(BUILD, "classes"), res] + jars)
    sources = glob.glob(os.path.join(HERE, "robo", "**", "*.java"), recursive=True)
    r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", classes, "-cp", cp] + sources)
    if r.returncode:
        sys.exit(1)
    names = sys.argv[3:] or [os.path.basename(s)[:-5] for s in sources if s.endswith("Test.java")]
    opts = ["-Drobolectric.offline=true", "-Drobolectric.dependency.dir=" + LIBS, "-Djava.io.tmpdir=" + tmp,
            "-Duser.home=" + tmp, "-Drobolectric.logging.enabled=false", "-Drobolectric.pixelCopyRenderMode=hardware", "-Dawc.packs=" + packs, "-Dawc.shots=" + shots,
            "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED"]
    r = subprocess.run(["java"] + opts + ["-cp", cp, "org.junit.runner.JUnitCore"] + ["org.awctool.radio." + n for n in names])
    print("screenshots in", shots)
    sys.exit(r.returncode)


if __name__ == "__main__":
    main()
