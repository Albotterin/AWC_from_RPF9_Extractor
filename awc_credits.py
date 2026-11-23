"""Song list from the publisher's public credits page (stations -> songs -> artists).

The credits page lists every radio station with all its songs and their artists, but nothing about the game
files. The game stores each track under the 29-bit hash of its lower-case title (words joined by '_'), so the
titles from the page can be hashed with joaat() and compared with the IDs of the loaded streams. A hit tells the
title, artist and station of that stream. Nothing is shipped with the tool: the page is downloaded when the user
asks for it, and the result stays on the user's PC (names.NameStore).
"""
import html
import re
import unicodedata
import urllib.request

from awc_names import STREAM_ID_MASK, joaat

URL = "https://www.rockstargames.com/VI/credits"
TOKEN = re.compile(r"<(h[1-6]|q|p)\b[^>]*>(.*?)</\1>", re.S)
TAGS = re.compile(r"<[^>]+>")
UPPER_WORDS = {"fm", "dj", "tv", "vc", "usa", "uk", "us"}


def download(url=URL, timeout=60):
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 AWC-Tool", "Accept": "text/html"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def _pretty_word(w):
    low = w.lower()
    if low in UPPER_WORDS or any(c.isdigit() for c in w) or not any(c in "aeiouy" for c in low):
        return w.upper()
    return w[:1].upper() + w[1:].lower()


def pretty_station(name):
    """'VICE CITY FM' -> 'Vice City FM', 'V-ROCK' -> 'V-Rock', 'WKTT TALK RADIO' -> 'WKTT Talk Radio'."""
    return " ".join("-".join(_pretty_word(p) for p in w.split("-")) for w in name.split())


def parse(text):
    """-> [{"station", "section", "title", "artist"}] from the credits page's HTML.

    Page structure: <h3> station, <h4> section ("SONGS"), then per song a <q> title followed by <p> lines of which
    the first is the artist."""
    start = text.find("MAIN SCORE")
    if start < 0:
        start = 0
    songs, station, section, cur = [], None, None, None
    for tag, raw in TOKEN.findall(text, max(0, start - 4000)):
        t = html.unescape(TAGS.sub("", raw)).strip()
        if tag == "h2" and songs:
            break
        if tag == "h3":
            station, section, cur = t, None, None
        elif tag == "h4":
            section, cur = t, None
        elif tag == "q" and station and t:
            cur = {"station": pretty_station(station), "section": section or "", "title": t, "artist": ""}
            songs.append(cur)
        elif tag == "p" and cur is not None and not cur["artist"]:
            cur["artist"] = t
    return songs


def _words(text):
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode().lower().replace("'", "")
    return re.findall(r"[a-z0-9]+", text)


def candidates(title):
    """Internal names the game could use for a title: with and without the parenthesised part."""
    plain = re.sub(r"\(.*?\)|\[.*?\]", " ", title)
    out = []
    for t in (plain, title):
        w = "_".join(_words(t))
        if w and w not in out:
            out.append(w)
    return out


def display_title(title):
    return re.sub(r"\s+", " ", title).strip()


def match(songs, wanted):
    """-> {stream id: (title, artist, station)} for the songs whose hashed title is one of the `wanted` IDs."""
    wanted = set(wanted)
    found = {}
    for s in songs:
        for cand in candidates(s["title"]):
            sid = joaat(cand) & STREAM_ID_MASK
            if sid in wanted and sid not in found:
                found[sid] = (display_title(s["title"]), s["artist"], s["station"])
    return found
