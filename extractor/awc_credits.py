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
# headings of the page that are not a radio station (score, TV, games ...): never used to name a group
NOT_RADIO = {"main score", "additional score", "supplemental score", "additional music", "tv shows", "tv media",
             "video games", "podcasts", "tv news", "rusty anchor cover band", "radio idents & imaging",
             "radio ads", "clearance", "a very special thanks"}


def is_radio(station):
    return station.lower() not in NOT_RADIO


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


_FOLD = str.maketrans({"ł": "l", "Ł": "L", "ø": "o", "Ø": "O", "đ": "d", "Đ": "D", "ß": "ss", "æ": "ae", "Æ": "AE", "œ": "oe"})


def _fold(text):
    """Lower-case ASCII; letters without a decomposition (ł, ø ...) become their plain letter ('Lutosławski' -> 'lutoslawski')."""
    return unicodedata.normalize("NFKD", text.translate(_FOLD)).encode("ascii", "ignore").decode().lower().replace("'", "")


def _fwords(text):
    return re.findall(r"[a-z0-9]+", _fold(text))


def _name_tokens(name):
    return [t for t in re.split(r"[_\s]+", _fold(name)) if t]


def _compact(text):
    return "".join(_fwords(text))


# titles the game config writes differently from the credits list
ALIASES = {"htwoo": "h2o", "sixfourfive": "645", "threehundredblackout": "300blackout",
           "beethovensymphonyseven": "symphonyno7inamajorop92iiallegretto", "getmoneyanthem": "gma", "goshorty": "50bands",
           "walkingintherain": "chiataytrongmua"}


def lookup(songs, station, title, artists=(), own_only=False):
    """(title, artist) in the spelling of the credits list for a title and artists as the game config writes them
    ('ADVENTURE_OF_A_LIFETIME', ['COLDPLAY']: upper case, names run together), or None.

    A song of the same station wins: the config sometimes names another song of the same title as the artist. Other
    sections of the page (music that is no station of its own) only help with the spelling, when the artist fits."""
    import difflib
    want = _compact(title.replace("_", " "))
    if not want:
        return None
    who = [a for a in (_compact(x.replace("_", " ")) for x in artists) if len(a) >= 3 and a != "special"]
    wants = {want, ALIASES.get(want, want)}
    wants |= {want[len(a):] for a in who if want.startswith(a) and len(want) > len(a)}      # "FUSE_ONE_SILK"
    best, best_rank = None, 0.0
    for s in songs:
        own, radio = s["station"] == station, is_radio(s["station"])
        if own_only and not own:
            continue
        full = _compact(s["title"])
        plain = _compact(re.sub(r"\(.*?\)|\[.*?\]", " ", s["title"]))
        same_artist = any(a in _compact(s["artist"]) for a in who)
        if not radio and not same_artist:
            continue
        if full in wants or plain in wants:
            if who and not same_artist and not own:
                continue                            # same title by somebody else
            rank = 3.0 + 0.1 * (full in wants)
        elif same_artist and plain and (plain.startswith(want) or want.startswith(plain) or full.startswith(want)):
            rank = 2.0
        elif same_artist and difflib.SequenceMatcher(None, want, full).ratio() >= 0.8:
            rank = 1.0
        else:
            continue
        rank += 1.0 * own + 0.6 * same_artist + 0.2 * radio
        rank += 0.3 * bool(who and who[0] in _compact(s["artist"]))     # two versions: the first artist decides
        if rank > best_rank:
            best, best_rank = s, rank
    return (display_title(best["title"]), best["artist"]) if best else None


def artist_names(songs):
    """{compact name: spelling} of every single artist of the credits list ('tameimpala' -> 'Tame Impala')."""
    out = {}
    for s in songs:
        for part in re.split(r"\s+(?:ft\.?|feat\.?|featuring|with|x)\s+|\s*[,&/]\s*|\s+and\s+", s["artist"], flags=re.I):
            key = _compact(part)
            if key:
                out.setdefault(key, part.strip())
    return out


def _score(title_words, artist_words, name_words):
    """0..1: how well the internal name of a stream fits a title of the credits list."""
    import difflib
    a, b = " ".join(title_words), " ".join(name_words)
    if not a or not b:
        return 0.0
    r = difflib.SequenceMatcher(None, a, b).ratio()
    if (a.startswith(b) or b.startswith(a)) and min(len(title_words), len(name_words)) >= 2:
        r = max(r, 0.9)
    if len(name_words) >= 3:                                     # classical pieces: composer + work, words in any order
        pool = set(title_words) | set(artist_words)
        r = max(r, 0.95 * sum(1 for w in name_words if w in pool) / len(name_words))
    # names with words run together ("symphonyno5e_flat..."): how much of the name's letters do the credit words explain?
    comp = "".join(name_words)
    needed = 3
    if comp.startswith("symphonyfm"):                            # these names always hold the composer and the work
        comp, needed = comp[10:], 2
    if len(comp) >= 12:
        covered = [False] * len(comp)
        hits = 0
        for w in sorted(set(title_words) | set(artist_words), key=len, reverse=True):
            if len(w) < 3 and not w.isdigit():
                continue
            i, new = comp.find(w), False
            while i >= 0:                                        # every occurrence ("nocturnes...nocturnes") counts
                if not all(covered[i:i + len(w)]):
                    for k in range(i, i + len(w)):
                        covered[k] = True
                    new = True
                i = comp.find(w, i + len(w))
            hits += new and len(w) >= 4
        if hits >= needed:
            r = max(r, 0.97 * sum(covered) / len(comp))
    return r


def match_names(songs, found, names, min_score=0.75, margin=0.03):
    """-> {stream id: (title, artist, station)} for streams whose game name (from the audio config) is only SIMILAR to a title
    of the credits list (the exact hash match in match() failed: 'IM_IN_LUV' for \"I'm N Luv (Wit a Stripper)\" and the like).

    songs  the credits list, found  what match() already found, names  {stream id: name} of the streams without a match.
    Every title and every stream is used once; a pair is accepted when it is clearly the best for both sides."""
    taken = {(v[0], v[1], v[2]) for v in found.values()}
    cands = []
    for s in songs:
        if not is_radio(s["station"]) or (display_title(s["title"]), s["artist"], s["station"]) in taken:
            continue
        tw = _fwords(re.sub(r"\(.*?\)|\[.*?\]", " ", s["title"]))
        aw = _fwords(s["artist"])
        for sid, name in names.items():
            if sid in found:
                continue
            sc = _score(tw, aw, _name_tokens(name))
            if sc >= min_score:
                cands.append((sc, id(s), s, sid))
    best_for_stream, best_for_song = {}, {}
    for sc, k, s, sid in cands:
        best_for_stream.setdefault(sid, []).append(sc)
        best_for_song.setdefault(k, []).append(sc)
    out, used_song = {}, set()
    for sc, k, s, sid in sorted(cands, key=lambda c: -c[0]):
        if sid in out or k in used_song:
            continue
        others = [x for x in best_for_stream[sid] if x != sc] + [x for x in best_for_song[k] if x != sc]
        if others and max(others) > sc - margin and max(others) >= min_score and sc < 0.97:
            continue                                             # ambiguous: another candidate is almost as good
        out[sid] = (display_title(s["title"]), s["artist"], s["station"])
        used_song.add(k)
    return out
