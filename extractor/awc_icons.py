"""Radio station logos from the game's texture pool (optional).

The logos are stored in data/ttdb/ui/ttdb_ui.ttpf next to audio.rpf. awc_logos reads them; the PNGs that are cut out of
the user's own game files stay on the user's PC (%APPDATA%\\AWC-Tool\\icons\\).

STATION_KEYS holds only identifiers (the 64-bit asset keys of the textures), no image data.
"""
import os
import re

import awc_logos

POOL = os.path.join("data", "ttdb", "ui", "ttdb_ui.ttpf")
ICON_SIZE = 256          # the game's logos are 256 x 256: keep them at full size (earlier versions saved 128)

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
    "The Chamber 106.6": 11926422907678209697,
    "The Wipe Talk Radio": 1649912805196772093, "V-Rock": 859321332381681553,
    "Vice City FM": 13940589714920136956, "Vice City Rap Radio": 15555537565538920778,
    "WKTT Talk Radio": 2259141511428128088, "Worldwide FM": 4853129926419308999,
}


def _appdata():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")


def icon_dir():
    return os.path.join(_appdata(), "icons")


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


def _png_width(path):
    try:
        with open(path, "rb") as f:
            head = f.read(24)
        return int.from_bytes(head[16:20], "big") if head[1:4] == b"PNG" else 0
    except OSError:
        return 0


def missing_stations():
    """Stations without a logo, or with one that was saved smaller than ICON_SIZE by an earlier version (made again)."""
    out = []
    for s in STATION_KEYS:
        p = icon_path(s)
        if not p or _png_width(p) < ICON_SIZE:
            out.append(s)
    return out


def extract(pool_path, progress=None):
    """Cut the missing station logos out of the texture pool. Returns the number of logos written. Needs Pillow."""
    todo = missing_stations()
    if not todo:
        return 0
    from PIL import Image  # noqa: PLC0415
    os.makedirs(icon_dir(), exist_ok=True)
    written = 0
    with awc_logos.TexturePool(pool_path) as pool:
        for n, station in enumerate(todo):
            try:
                im = pool.image(STATION_KEYS[station])
            except awc_logos.LogoError:
                im = None                                       # this texture is stored differently: no logo
            if im is not None:
                if im.size != (ICON_SIZE, ICON_SIZE):
                    im = im.resize((ICON_SIZE, ICON_SIZE), Image.LANCZOS)
                dst = icon_path_for_write(station)
                im.save(dst + ".part", "PNG")
                os.replace(dst + ".part", dst)
                written += 1
            if progress:
                progress((n + 1) / len(todo))
    return written


def icon_path_for_write(station):
    return os.path.join(icon_dir(), slug(station) + ".png")
