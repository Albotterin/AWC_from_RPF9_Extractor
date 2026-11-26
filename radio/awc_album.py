"""The game's soundtrack album, "Grand Theft Auto VI: The Album (Deluxe Edition)", as far as the game's own files hold
it: a playlist for the app, in the order of the album, with the album's cover.

The track list is the one the stores show. Most of its songs play on the radio stations and are found there by their
names. A few lie in the archive without a station and without a name; which recording is which was found out by
comparing the sound with the public 30 second previews of the album, and is noted here by the game's own stream
numbers. One song only lies inside the mix of The Vault, at a place the game marks. Songs the archive does not hold are
simply left out. Nothing is decrypted.

The cover is not part of this program: it is fetched from the publisher's web page when the playlist is written.
"""
import io
import os
import re
import unicodedata
import urllib.request

import awc_icons as icons

NAME = "Grand Theft Auto VI: The Album"
COVER_URL = ("https://media-rockstargames-com.akamaized.net/tina-uploads/posts/178ok8411a55o5/"
             "163945bc2aacd4b055f9aa59edeab3f6b1fd4c59.jpg")
COVER_SIZE = 512

# (disc, number, title, artist) of the deluxe edition
TRACKS = (
    (1, 1, "Starting From Zero (feat. Michael McDonald)", "Justice"), (1, 2, "That's It (feat. Future & Metro Boomin)", "Yung Lean"),
    (1, 3, "AHH YEA", "Playboi Carti"), (1, 4, "RHYNO", "Travis Scott"),
    (1, 5, "Sexy Magic", "CA7RIEL & Paco Amoroso, Fred again.., Etienne de Crécy & PinkPantheress"),
    (1, 6, "Flower In A Hurricane", "Justice, SZA, Tame Impala & King Gizzard & The Lizard Wizard"), (1, 7, "Go Easy", "Dua Lipa"),
    (1, 8, "Last Thing You Need", "Morgan Wallen"), (1, 9, "X", "JHAYCO"), (1, 10, "Suzuki", "Fuerza Regida"),
    (1, 11, "Macacoa 2000", "Rauw Alejandro"), (1, 12, "Dance With Me (feat. elkan)", "A Boogie wit da Hoodie"),
    (1, 13, "Bigger", "Burna Boy"), (1, 14, "COUNT UP", "Gunna"), (1, 15, "Let's Rock", "Kodak Black & Don Toliver"),
    (1, 16, "WAKIN UP FLEX", "Young Thug"), (1, 17, "IKYFL", "Tee Grizzley"), (1, 18, "TF?", "Don Toliver"),
    (1, 19, "Don't Chart", "Cardi B"), (1, 20, "GMA", "JT"), (1, 21, "Power Trip", "JENNIE"), (1, 22, "Blood In Your Eyes", "Tove Lo"),
    (1, 23, "Fast Emotion", "Zara Larsson"), (1, 24, "Sweet Like Candy", "Camila Cabello"), (1, 25, "Only One", "Fcukers"),
    (1, 26, "Sandunguea", "Arca"), (1, 27, "TRDWFE", "Rina Sawayama"), (1, 28, "All Again", "Rose Gray"),
    (1, 29, "Crazy For You", "Lana Del Rey"), (1, 30, "Largate", "Kali Uchis & Helado Negro"), (1, 31, "NO ME BUSQUES", "Omar Apollo"),
    (1, 32, "Bright Lights, Big City", "Keith Richards"), (1, 33, "Stone Kold Krazy Luuv", "Jesse Johnson & DāM FunK"),
    (1, 34, "Overtown Lights", "Stanley Clarke, Yussef Dayes & Robert Glasper"), (2, 1, "Burn This Shit Down", "Travis Scott"),
    (2, 2, "internet $tar", "Slayyyter"), (2, 3, "Interesting", "The Dare"), (2, 4, "Touch & Go", "Anitta"),
    (2, 5, "I Just Came To Pop It", "Offset"), (2, 6, "RCKSTRL!VING", "HXG, Tana & Quavo"), (2, 7, "Cookin", "21 Savage"),
    (2, 8, "Got Dat", "Pooh Shiesty"), (2, 9, "Don Da Da", "Young Nudy"), (2, 10, "CASHOUT", "SoFaygo"),
    (2, 11, "Cooties (Remix)", "Real Dimez & Sexyy Red"), (2, 12, "50 Bands (feat. Flo Milli)", "Latto"),
    (2, 13, "Tèt Kale Bobis", "Kodak Black & Michaël Brun"), (2, 14, "FLA Flow (feat. 454)", "Denzel Curry"),
    (2, 15, "You Move Me", "NEIL FRANCES"), (2, 16, "Don't Play Around", "CHRIS STASSY & KETTAMA"), (2, 17, "NIGHTMARE", "HEALTH"),
    (2, 18, "Mutual Death Warrant", "Power Trip"), (2, 19, "Granforma", "Overmono"), (2, 20, "Windsor Drop", "Overmono"),
    (2, 21, "Eighty Eight Comet", "Overmono"), (2, 22, "Banshee", "Overmono"), (2, 23, "Welcome to Leonida", "Overmono"),
)
# recordings no station plays: the game's stream numbers (several: layers that sound together). The game's "Windsor Drop"
# and "Banshee" are shorter than on the album.
BY_SOUND = {
    (2, 19): (0x1F314691,), (2, 20): (0x0F89E4A7,), (2, 21): (0x0CE4A454,), (2, 22): (0x1F43EB8A,),
    (2, 23): (0x0424C938, 0x0CC8DA81, 0x1A96F61D, 0x1FC6007C, 0x1F093F02, 0x1D5BDD88, 0x0C0A652D, 0x07D97F8C),
}
# only inside a mix of the game: (the station, the title of the part there)
FROM_MIX = {(2, 16): ("The Vault Radio", "Don't Play Around")}
MIX_NOTE = " (from The Vault's mix)"
# recordings a station plays without a name, recognised by their sound: stream number -> (title, artist)
KNOWN_NAMES = {0x010CD333: ("Ras Menilik Congo (Harp)", "Augustus Pablo")}


def name_known(stations):
    """Give the recordings recognised by their sound their names."""
    for st in stations.values():
        for song in st.get("songs", []):
            known = KNOWN_NAMES.get(song.get("sid"))
            if known and not song.get("parts") and song.get("title") in ("", "Unknown track"):
                song["title"], song["artist"] = known


def _plain(text):
    text = re.sub(r"[\(\[](?:feat|ft)\b[^\)\]]*[\)\]]", " ", text, flags=re.I)
    text = unicodedata.normalize("NFKD", text.replace("$", "s")).encode("ascii", "ignore").decode().lower()
    return re.sub(r"[^a-z0-9]+", "", text)


def _first(artist):
    """The first of the artists a line names."""
    return _plain(re.split(r"\s*(?:,|&|\bft\.?\s|\bfeat\.?\s|\bx\s)\s*", artist, flags=re.I)[0])


def _written(title, artist):
    """The store's "Song (feat. B)" by A as the game writes it: "Song" by "A ft. B"."""
    guest = re.search(r"\s*\((?:feat|ft)\.?\s+([^\)]*)\)", title, flags=re.I)
    if not guest:
        return title, artist
    return (title[:guest.start()] + title[guest.end():]).strip(), "%s ft. %s" % (artist, guest.group(1))


def find(items, stations):
    """The album's songs the game's files hold, in the album's order: [{"base", "idx", "sid", "title", "artist", "sec",
    "no": "1.02"}], one from several layers also with "stems": [stream indexes], one cut out of a mix with "cut":
    [second it starts, second it ends]. items: the scan items; stations: those of the radio index."""
    songs = {}
    for st in stations.values():
        if st.get("rules", {}).get("talkradio"):
            continue
        for song in st.get("songs", []):
            if not song.get("parts") and not song.get("set") and song.get("title"):
                songs.setdefault(_plain(song["title"]), []).append(song)
    streams = {}
    for item in items:
        for s in item["streams"]:
            streams.setdefault(s["id"], (item, s))
    out = []
    for disc, number, title, artist in TRACKS:
        entry = None
        if (disc, number) in BY_SOUND:
            found = [streams.get(sid) for sid in BY_SOUND[(disc, number)]]
            if all(found) and len({item["base"] for item, _s in found}) == 1:
                item, s = found[0]
                entry = dict(base=item["base"], idx=s["i"], sid=s["id"], sec=s["sec"], title=title, artist=artist)
                if len(found) > 1:
                    entry["stems"] = [s["i"] for _item, s in found]
        elif (disc, number) in FROM_MIX:
            station, part = FROM_MIX[(disc, number)]
            for mix in stations.get(station, {}).get("songs", []):
                marks = mix.get("m") or []
                for k, mark in enumerate(marks):
                    if _plain(mark[1]) == _plain(part):
                        end = marks[k + 1][0] if k + 1 < len(marks) else mix["sec"]
                        entry = dict(base=mix["base"], idx=mix["idx"], sid=mix["sid"], sec=round(end - mark[0], 2),
                                     cut=[mark[0], end], title=part + MIX_NOTE, artist=mark[2] or artist)
                        break
                if entry:
                    break
        else:
            first = _first(artist)
            same = [x for x in songs.get(_plain(title), []) if first and (first in _plain(x["artist"]) or _first(x["artist"]) in _plain(artist))]
            if same:
                best = max(same, key=lambda x: x["sec"])
                entry = {k: best[k] for k in ("base", "idx", "sid", "sec", "title", "artist")}
            else:
                title_, artist_ = _written(title, artist)
                same = [x for x in songs.get(_plain(title_), []) if first and first in _plain(x["artist"])]
                if same:
                    entry = {k: same[0][k] for k in ("base", "idx", "sid", "sec", "title", "artist")}
        if entry:
            if (disc, number) in BY_SOUND:
                entry["title"], entry["artist"] = _written(title, artist)
            entry["no"] = "%d.%02d" % (disc, number)
            out.append(entry)
    return out


def cover():
    """The path of the album's cover (fetched once from the publisher's page), or "" when it cannot be had."""
    path = icons.icon_path(NAME)
    if path:
        return path
    try:
        from PIL import Image  # noqa: PLC0415
        request = urllib.request.Request(COVER_URL, headers={"User-Agent": "AWC-Radio"})
        with urllib.request.urlopen(request, timeout=40) as answer:
            data = answer.read(20 << 20)
        with Image.open(io.BytesIO(data)) as picture:
            small = picture.convert("RGB").resize((COVER_SIZE, COVER_SIZE), Image.LANCZOS)
        path = icons.icon_path_for_write(NAME)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        small.save(path + ".part", "PNG", optimize=True)
        os.replace(path + ".part", path)
        return path
    except Exception:  # noqa: BLE001   (no connection, another picture format: the app draws its own)
        return ""
