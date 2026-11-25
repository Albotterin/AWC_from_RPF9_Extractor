"""Builds android/AWC-Radio.apk, the Android app (the same file for everybody; it contains NO game data).

The music comes from station packs (.radiopak) that AWC Radio writes (awc_android.py); the app reads them from a folder
that it asks for on the first start. assets/ holds the display font Poiret One (SIL Open Font License, text beside it).
Needs the Android SDK (platform 34 + build-tools 36.1.0, or set ANDROID_BUILD_TOOLS) and a JDK; users only need the
finished APK. The APK is signed with a key that is created once in android/keys/ (never commit that folder; keep a
backup: only APKs signed with the same key can update an installed app).

    python build_apk.py [path to the Android SDK, default %ANDROID_SDK_ROOT%]
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SDK = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("ANDROID_SDK_ROOT")
if not SDK:
    raise SystemExit("Give the path of the Android SDK as an argument or set ANDROID_SDK_ROOT.")
BT = os.path.join(SDK, "build-tools", os.environ.get("ANDROID_BUILD_TOOLS", "36.1.0"))
JAR = os.path.join(SDK, "platforms", "android-34", "android.jar")
BUILD = os.path.join(HERE, "build")
OUT = os.path.join(HERE, "AWC-Radio.apk")
KEYS = os.path.join(HERE, "keys")


def run(cmd, **kw):
    print(">", " ".join('"%s"' % c if " " in c else c for c in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True, **kw)
    if r.stdout.strip():
        print(r.stdout.strip())
    if r.returncode:
        print(r.stderr.strip())
        raise SystemExit("failed (%d)" % r.returncode)


def make_icons():
    """Only the small white notification icon is drawn here; the launcher icon (res/mipmap-*, res/drawable/ic_launcher_*)
    is made by make_logo.py and committed as finished PNGs."""
    from PIL import Image, ImageDraw
    d = os.path.join(HERE, "res", "drawable-xxxhdpi")
    os.makedirs(d, exist_ok=True)
    n = 96
    im = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    dr = ImageDraw.Draw(im)
    c = n // 2
    dr.ellipse((c - 7, c - 7, c + 7, c + 7), fill="white")
    for r in (20, 34):
        dr.arc((c - r, c - r, c + r, c + r), 200, 340, fill="white", width=7)
        dr.arc((c - r, c - r, c + r, c + r), 20, 160, fill="white", width=7)
    im.save(os.path.join(d, "ic_notify.png"))


def main():
    for p in (BT, JAR):
        if not os.path.exists(p):
            raise SystemExit("Android SDK part missing: " + p)
    shutil.rmtree(BUILD, ignore_errors=True)
    for sub in ("gen", "classes", "dex"):
        os.makedirs(os.path.join(BUILD, sub))
    make_icons()
    aapt2 = os.path.join(BT, "aapt2.exe")
    run([aapt2, "compile", "--dir", os.path.join(HERE, "res"), "-o", os.path.join(BUILD, "res.zip")])
    run([aapt2, "link", "-o", os.path.join(BUILD, "base.apk"), "-I", JAR, "--manifest", os.path.join(HERE, "AndroidManifest.xml"),
         "--java", os.path.join(BUILD, "gen"), "-A", os.path.join(HERE, "assets"), "--min-sdk-version", "24",
         "--target-sdk-version", "34", os.path.join(BUILD, "res.zip")])
    sources = glob.glob(os.path.join(HERE, "java", "**", "*.java"), recursive=True) + \
        glob.glob(os.path.join(BUILD, "gen", "**", "*.java"), recursive=True)
    # --release 8: only Java 8 library APIs (also present on Android); android.jar supplies the android.* classes
    run(["javac", "-encoding", "UTF-8", "--release", "8", "-g:none", "-Xlint:-options", "-classpath", JAR,
         "-d", os.path.join(BUILD, "classes")] + sources)
    classes = glob.glob(os.path.join(BUILD, "classes", "**", "*.class"), recursive=True)
    run([os.path.join(BT, "d8.bat"), "--lib", JAR, "--min-api", "24", "--output", os.path.join(BUILD, "dex")] + classes)
    unsigned = os.path.join(BUILD, "unsigned.apk")
    with zipfile.ZipFile(os.path.join(BUILD, "base.apk")) as base, zipfile.ZipFile(unsigned, "w", zipfile.ZIP_DEFLATED) as out:
        for info in base.infolist():
            # Android 11+ (targetSdk 30+) needs resources.arsc stored uncompressed AND 4-byte aligned (zipalign below)
            ctype = zipfile.ZIP_STORED if info.filename == "resources.arsc" else zipfile.ZIP_DEFLATED
            out.writestr(info.filename, base.read(info.filename), compress_type=ctype)
        out.write(os.path.join(BUILD, "dex", "classes.dex"), "classes.dex")
    aligned = os.path.join(BUILD, "aligned.apk")
    run([os.path.join(BT, "zipalign.exe"), "-f", "-p", "4", unsigned, aligned])
    ks, pw = ensure_keystore()
    if os.path.exists(OUT):
        os.remove(OUT)
    run([os.path.join(BT, "apksigner.bat"), "sign", "--ks", ks, "--ks-pass", "file:" + pw, "--out", OUT, aligned])
    run([os.path.join(BT, "apksigner.bat"), "verify", "--verbose", OUT])
    print("OK:", OUT, os.path.getsize(OUT), "bytes")


def ensure_keystore():
    """Create the signing key once (PKCS12, RSA 2048, 40 years). Returns (keystore path, password file path)."""
    import secrets
    os.makedirs(KEYS, exist_ok=True)
    ks, pw = os.path.join(KEYS, "awc_radio.p12"), os.path.join(KEYS, "keystore.pass")
    if not os.path.exists(ks):
        password = secrets.token_urlsafe(18)
        with open(pw, "w", encoding="ascii") as f:
            f.write(password)
        run(["keytool", "-genkeypair", "-keystore", ks, "-storetype", "PKCS12", "-storepass", password, "-keypass", password,
             "-alias", "awcradio", "-keyalg", "RSA", "-keysize", "2048", "-validity", "14600", "-dname", "CN=AWC Radio"])
        print("Created a new signing key in", KEYS, "- keep a backup of this folder, never publish it.")
    return ks, pw


if __name__ == "__main__":
    main()
