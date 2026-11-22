"""Track and station names for AWC streams.

AWC files do not contain readable names, only 32-bit hash values (the stream IDs). This module does not
decrypt or extract anything. It offers three things:

* joaat()      - the standard Jenkins one-at-a-time hash that RAGE games use for names, so a list of candidate
                 names can be compared with the stream IDs found in the files
* NameStore    - titles/artists/stations the user knows (typed in, or imported from a text/CSV/JSON file)
* helpers for tags (Ogg/FLAC/MP3 metadata), safe file names and .m3u8 playlists

No names or game data are shipped with the tool; every name comes from the user's own lists.
"""
import csv
import io
import json
import os
import re

HEX8 = re.compile(r"^(?:0x)?([0-9A-Fa-f]{8})$")
BAD_CHARS = '<>:"/\\|?*'


# --------------------------------------------------------------------------- hashing
def joaat(text):
    """Jenkins one-at-a-time hash of the lower-cased text (as used by RAGE for names)."""
    h = 0
    for b in text.lower().encode("utf-8"):
        h = (h + b) & 0xFFFFFFFF
        h = (h + (h << 10)) & 0xFFFFFFFF
        h ^= h >> 6
    h = (h + (h << 3)) & 0xFFFFFFFF
    h ^= h >> 11
    h = (h + (h << 15)) & 0xFFFFFFFF
    return h


def parse_id(text):
    """'0x1A2B3C4D' / '1A2B3C4D' -> int, otherwise None."""
    m = HEX8.match((text or "").strip())
    return int(m.group(1), 16) if m else None


def safe_filename(s, fallback="track"):
    s = "".join("_" if c in BAD_CHARS or ord(c) < 32 else c for c in (s or "")).strip(" .")
    return s[:120] or fallback


def track_label(rec):
    """'Artist – Title' (or just the title) of a record; '' if there is nothing."""
    if not rec:
        return ""
    t, a = rec.get("title", ""), rec.get("artist", "")
    return ("%s – %s" % (a, t)) if (a and t) else (t or a)


def split_artist_title(text):
    """'Artist - Title' -> ('Artist', 'Title'); anything else -> ('', text)."""
    for sep in (" - ", " – ", " — "):
        if sep in text:
            a, t = text.split(sep, 1)
            return a.strip(), t.strip()
    return "", text.strip()


def _clean(v):
    return (v or "").strip() if isinstance(v, str) else ""


def _rec(title="", artist="", station=""):
    return {"title": _clean(title), "artist": _clean(artist), "station": _clean(station)}


# --------------------------------------------------------------------------- store
def default_path():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool", "track_names.json")


class NameStore:
    """{stream id: {"title", "artist", "station"}}, persisted as JSON."""

    def __init__(self, path=None):
        self.path = path or default_path()
        self.tracks = {}
        self.load()

    # -- persistence
    def load(self):
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                d = json.load(f).get("tracks", {})
            self.tracks = {k.upper(): _rec(v.get("title"), v.get("artist"), v.get("station"))
                           for k, v in d.items() if isinstance(v, dict) and HEX8.match(k)}
        except (OSError, ValueError, AttributeError):
            self.tracks = {}

    def save(self):
        try:
            os.makedirs(os.path.dirname(self.path), exist_ok=True)
            with open(self.path, "w", encoding="utf-8") as f:
                json.dump({"tracks": self.tracks}, f, ensure_ascii=False, indent=1, sort_keys=True)
        except OSError:
            pass

    # -- access
    @staticmethod
    def key(sid):
        return "%08X" % (sid & 0xFFFFFFFF)

    def get(self, sid):
        return self.tracks.get(self.key(sid))

    def set(self, sid, title="", artist="", station=""):
        r = _rec(title, artist, station)
        if r["title"] or r["artist"]:
            self.tracks[self.key(sid)] = r
        else:
            self.tracks.pop(self.key(sid), None)

    def clear(self):
        self.tracks.clear()

    # -- import
    def import_file(self, path, known_ids=()):
        """Read names from a .csv/.tsv/.txt/.json file. Returns (matched, skipped).

        Two kinds of entries are understood:
          * with a stream ID (column id/hash, or a JSON key like "1A2B3C4D")  -> stored as given
          * with an internal name (column name/key, a plain text line, a JSON key)  -> hashed with joaat() and
            kept only if the hash is the ID of a stream that is currently loaded (known_ids)
        """
        known = {i & 0xFFFFFFFF for i in known_ids}
        raw = open(path, "rb").read()
        try:
            text = raw.decode("utf-8-sig")
        except UnicodeDecodeError:
            text = raw.decode("cp1252", errors="replace")
        if path.lower().endswith(".json") or text.lstrip().startswith("{"):
            entries = self._entries_json(text)
        else:
            entries = self._entries_text(text)
        matched = skipped = 0
        for sid, name_key, rec in entries:
            if sid is None and name_key:
                for cand in {name_key, name_key.replace(" ", "_"), os.path.splitext(name_key)[0]}:
                    h = joaat(cand)
                    if h in known:
                        sid = h
                        break
            if sid is None or not (rec["title"] or rec["artist"]):
                skipped += 1
                continue
            self.tracks[self.key(sid)] = rec
            matched += 1
        if matched:
            self.save()
        return matched, skipped

    @staticmethod
    def _entries_json(text):
        out = []
        data = json.loads(text)
        if isinstance(data, dict) and isinstance(data.get("tracks"), dict):
            data = data["tracks"]
        if not isinstance(data, dict):
            return out
        for k, v in data.items():
            if isinstance(v, str):
                a, t = split_artist_title(v)
                rec = _rec(t, a)
            elif isinstance(v, dict):
                rec = _rec(v.get("title") or v.get("name"), v.get("artist"), v.get("station"))
            else:
                continue
            sid = parse_id(k)
            out.append((sid, None if sid is not None else k, rec))
        return out

    @staticmethod
    def _entries_text(text):
        lines = [ln for ln in text.splitlines() if ln.strip() and not ln.lstrip().startswith("#")]
        if not lines:
            return []
        first = lines[0]
        delim = max(",;\t|", key=first.count)
        head = [h.strip().lower() for h in next(csv.reader([first], delimiter=delim))]
        names = {"id", "hash", "name", "key", "title", "artist", "station", "album"}
        out = []
        if first.count(delim) >= 1 and len(names & set(head)) >= 2:
            col = {h: i for i, h in enumerate(head)}

            def cell(row, *keys):
                for k in keys:
                    i = col.get(k)
                    if i is not None and i < len(row):
                        return row[i].strip()
                return ""
            for row in csv.reader(lines[1:], delimiter=delim):
                sid = parse_id(cell(row, "id", "hash"))
                key = cell(row, "name", "key")
                title, artist = cell(row, "title"), cell(row, "artist")
                if not title and not artist and key:
                    artist, title = split_artist_title(key)
                out.append((sid, key or None, _rec(title, artist, cell(row, "station", "album"))))
        else:   # plain list: one candidate per line, e.g. "Artist - Title" or an internal name
            for ln in lines:
                a, t = split_artist_title(ln.strip())
                out.append((None, ln.strip(), _rec(t, a)))
        return out

    # -- template for filling in by hand
    @staticmethod
    def write_template(path, rows, store=None):
        """rows: [(sid, awc_name, group_label, station)]. Writes a ';' separated CSV that import_file() reads."""
        with io.open(path, "w", encoding="utf-8-sig", newline="") as f:
            w = csv.writer(f, delimiter=";")
            w.writerow(["id", "awc", "group", "station", "artist", "title"])
            for sid, awc, grp, station in rows:
                r = (store.get(sid) if store else None) or {}
                w.writerow(["%08X" % sid, awc, grp, r.get("station") or station, r.get("artist", ""),
                            r.get("title", "")])


# --------------------------------------------------------------------------- tags + playlists
def make_tags(rec, station="", number=0):
    """Metadata dict for export (Vorbis/ffmpeg names); empty values are left out."""
    rec = rec or {}
    tags = {"TITLE": rec.get("title", ""), "ARTIST": rec.get("artist", ""),
            "ALBUM": rec.get("station") or station or "", "TRACKNUMBER": str(number) if number else ""}
    return {k: v for k, v in tags.items() if v}


def ffmpeg_meta_args(tags):
    args = []
    for k, v in (tags or {}).items():
        args += ["-metadata", "%s=%s" % (k.lower(), v)]
    return args


def write_m3u8(folder, entries, name="playlist.m3u8"):
    """entries: [(file name relative to folder, seconds, label)] -> UTF-8 extended M3U."""
    if not entries:
        return None
    p = os.path.join(folder, name)
    with io.open(p, "w", encoding="utf-8", newline="\r\n") as f:
        f.write("#EXTM3U\n")
        for fn, sec, label in entries:
            f.write("#EXTINF:%d,%s\n%s\n" % (int(round(sec)), label or os.path.splitext(fn)[0], fn))
    return p
