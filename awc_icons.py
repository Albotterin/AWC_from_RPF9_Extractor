"""Radio station logos from the game's texture pool (optional).

The logos are stored in data/ttdb/ui/ttdb_ui.ttpf next to audio.rpf, in a texture format (BCPack) that this tool
does not decode itself. The decoder of the Leonida project (github.com/ItsNotRexed/Leonida, GPL-3) can read it.
It is NOT part of this tool: on request the few files that are needed are downloaded from GitHub into
%APPDATA%\\AWC-Tool\\leonida\\ (together with that project's LICENSE) and run from there. The logos that are cut out
of the user's own game files stay on the user's PC (%APPDATA%\\AWC-Tool\\icons\\).

STATION_KEYS holds only identifiers (the 64-bit asset keys of the textures), no image data.
"""
import importlib
import io
import os
import re
import sys
import urllib.request

# The downloaded decoder is plain stdlib Python; listing its imports here makes the packaged .exe include them.
import argparse  # noqa: F401
import array  # noqa: F401
import bisect  # noqa: F401
import collections  # noqa: F401
import glob  # noqa: F401
import json  # noqa: F401
import math  # noqa: F401
import mmap  # noqa: F401
import secrets  # noqa: F401
import stat  # noqa: F401
import struct  # noqa: F401
import zlib  # noqa: F401

REPO = "ItsNotRexed/Leonida"
COMMIT = "de6713524633c63045583321291e6bad647dadf3"      # the version these keys and the decoder were checked with
RAW = "https://raw.githubusercontent.com/%s/%s/%s"
FILES = ["tools/ttpf_mips.py", "tools/bcpack.py", "tools/bcpack_tables.py", "tools/bcpack_tables_bptc.py",
         "tools/file_mode.py", "tools/tex_headers.py", "LICENSE"]
POOL = os.path.join("data", "ttdb", "ui", "ttdb_ui.ttpf")
ICON_SIZE = 128

# station name (as on the credits page) -> asset key of its logo in ttdb_ui.ttpf
STATION_KEYS = {
    "Afrobank FM": 12245507167093416674, "Back Country Radio": 16795714522819186833,
    "Bodega The DJ": 17039944992553854662, "Circoloco Records Radio": 2175413633023915155,
    "Cocoteo FM": 1173015381457134819, "Dirty South Classics": 3336880137511975214,
    "Emotion 98.3": 13942064450226070796, "Flash FM": 4461841684503721532,
    "Gloriana Blues Radio": 3193992257570992123, "Honey FM": 18260953674206409424,
    "Kaleidoscope FM": 16012335521546458767, "Leonida Jazz Radio": 13404308210168051815,
    "Radio La Ola": 1328110711360071785, "Radio On-U": 14262925714437913040,
    "Stockyard FM": 1178477634407948612, "Symphony FM": 11064473243466034039,
    "The Wipe Talk Radio": 1649912805196772093, "V-Rock": 859321332381681553,
    "Vice City FM": 13940589714920136956, "Vice City Rap Radio": 15555537565538920778,
    "WKTT Talk Radio": 2259141511428128088, "Worldwide FM": 4853129926419308999,
}

_mod = None


def _appdata():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")


def decoder_dir():
    return os.path.join(_appdata(), "leonida")


def icon_dir():
    return os.path.join(_appdata(), "icons")


def installed():
    return all(os.path.isfile(os.path.join(decoder_dir(), os.path.basename(f))) for f in FILES)


def download(progress=None, timeout=20):
    """Download the decoder files (about 230 KB). Raises OSError on a network problem."""
    os.makedirs(decoder_dir(), exist_ok=True)
    for n, f in enumerate(FILES):
        req = urllib.request.Request(RAW % (REPO, COMMIT, f), headers={"User-Agent": "AWC-Tool"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = r.read()
        dst = os.path.join(decoder_dir(), os.path.basename(f))
        with open(dst + ".part", "wb") as out:
            out.write(data)
        os.replace(dst + ".part", dst)
        if progress:
            progress((n + 1) / len(FILES))


def _decoder():
    """Import the downloaded ttpf_mips module (once)."""
    global _mod
    if _mod is None:
        if decoder_dir() not in sys.path:
            sys.path.insert(0, decoder_dir())
        _mod = importlib.import_module("ttpf_mips")
    return _mod


def slug(station):
    return re.sub(r"[^a-z0-9]+", "_", station.lower()).strip("_")


def icon_path(station):
    """Path of the extracted logo of a station, or "" if there is none."""
    if not station:
        return ""
    station = re.sub(r"\s*\(Mix\)$", "", station)              # a mix station uses the logo of its station
    p = os.path.join(icon_dir(), slug(station) + ".png")
    return p if os.path.isfile(p) else ""


def find_pool(rpf_path):
    """The UI texture pool that belongs to an audio.rpf (same game folder), or ""."""
    p = os.path.join(os.path.dirname(os.path.abspath(rpf_path)), POOL)
    return p if os.path.isfile(p) else ""


def missing_stations():
    return [s for s in STATION_KEYS if not icon_path(s)]


def extract(pool_path, progress=None):
    """Cut the station logos out of the texture pool. Returns the number of logos written.

    Needs the downloaded decoder and Pillow (to turn the decoded DDS into a PNG)."""
    from PIL import Image  # noqa: PLC0415
    t = _decoder()
    todo = {STATION_KEYS[s]: s for s in missing_stations()}
    if not todo:
        return 0
    os.makedirs(icon_dir(), exist_ok=True)
    pool = t.Pool(pool_path)
    written = 0
    try:
        found = {}
        for i in range(pool.textures):
            key = pool.descriptor(i)["asset_key_at"]
            if key in todo:
                found[key] = i
                if len(found) == len(todo):
                    break
        for n, (key, i) in enumerate(found.items()):
            blob = t.texture_dds(pool, t.analyze(pool, i), allow_provisional=True)
            im = Image.open(io.BytesIO(blob))
            im.load()
            im = im.convert("RGBA").resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS)
            dst = icon_path_for_write(todo[key])
            im.save(dst + ".part", "PNG")
            os.replace(dst + ".part", dst)
            written += 1
            if progress:
                progress((n + 1) / len(found))
    finally:
        pool.close()
    return written


def icon_path_for_write(station):
    return os.path.join(icon_dir(), slug(station) + ".png")
