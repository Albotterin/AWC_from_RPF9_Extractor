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
OWN_LOGOS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logos")      # our own designs (make_station_logos.py)

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
    "The Chamber 106.6": 11926422907678209697, "The Sniper Of La Perle": 1200660029869278654,
    "The Wipe Talk Radio": 1649912805196772093, "V-Rock": 859321332381681553,
    "Vice City FM": 13940589714920136956, "Vice City Rap Radio": 15555537565538920778,
    "WKTT Talk Radio": 2259141511428128088, "Worldwide FM": 4853129926419308999,
    "Thrillbilly Mud Club": 5000657249313955474,
    "Jack of Hearts": 6811332632485254002, "VYB": 11378843460216095013,
    # the covers of the podcasts
    "Drunk Kings": 17313509892237327231, "Final Act": 2934041327297051089, "Fuck Marry Murder": 4824653680872706417,
    "The Safe Trap": 10616877626666773796, "Vice City True Stories": 15150795830351651194,
}
ROUND = ("Jack of Hearts", "VYB")          # badges drawn as a circle: the corners get the colour of the circle's edge
SAME_LOGO = {"The Vault (Club)": "The Vault Radio",
             "DJ Stevie J": "VYB"}                       # the club where this DJ is resident


def _appdata():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")


def icon_dir():
    return os.path.join(_appdata(), "icons")


def slug(station):
    return re.sub(r"[^a-z0-9]+", "_", station.lower()).strip("_")


def icon_path(station):
    """Path of the logo of a station (extracted from the game, else one of our own), or "" if there is none."""
    if not station:
        return ""
    station = re.sub(r"\s*\(Mix\)$", "", station)              # a mix station uses the logo of its station
    station = SAME_LOGO.get(station, station)
    for folder in (icon_dir(), OWN_LOGOS):
        p = os.path.join(folder, slug(station) + ".png")
        if os.path.isfile(p):
            return p
    return ""


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


def _squared(im):
    """A round badge on a square: its inside, faded into the colour found near its edge (without the outline ring)."""
    from PIL import Image, ImageDraw, ImageFilter  # noqa: PLC0415
    w, h = im.size
    colour = im.getpixel((w // 2, h * 9 // 100))[:3]
    inside = Image.new("L", im.size, 0)
    inner = (w * 6 // 100, h * 6 // 100, w - w * 6 // 100, h - h * 6 // 100)
    ImageDraw.Draw(inside).ellipse(inner, fill=255)
    inside = inside.filter(ImageFilter.GaussianBlur(w * 0.025))
    out = Image.new("RGBA", im.size, colour + (255,))
    out.paste(im.convert("RGB"), (0, 0), inside)
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
                if station in ROUND:
                    im = _squared(im)
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
