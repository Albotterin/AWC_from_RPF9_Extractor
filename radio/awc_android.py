"""AWC Radio station packs for the Android app.

The Android app (AWC-Radio.apk, the same file for everybody, no game data in it) plays the stations live by the UTC
clock, exactly like AWC Radio on the PC. Its audio comes from packs in a folder on the phone, made here from the
user's own audio.rpf: one file per station (<station>.radiopak: its songs in the game's own quality, about 128 kbit/s,
not re-encoded, its logo and its programme data) and one per podcast (<podcast>.podpak: the episodes and the cover).
The app reads every pack of the folder, so a station or podcast is added or updated by copying just its file. Packs
that would come out the same are not written again.

Pack layout (little endian), read by android/java/org/awctool/radio/Pack.java:
    0   8 bytes  magic "AWCPACK1"
    8   u32      version (1)
    12  u32      reserved
    16  u64      offset of the index
    24  u64      length of the index
    32  ...      files back to back: Ogg Opus songs and the PNG logo
    idx          UTF-8 JSON {"v":1,"created":..,"sig":..,"stations":[one station],
                             "files":{"t/<key>":[offset,length],"logos/<name>.png":[..]}}
                 a talk station carries "kind":"talk"; a song may carry "m": [[start second, title, artist], ...], the
                 parts of a long DJ mix; "ads": [{"k","t","s"}] are the station's adverts (played only when switched on)
                 a podcast pack (.podpak, same layout) has "stations": [] and
                 "podcasts": [{"name","logo","episodes":[{"k","s"}]}] (the episodes in their order)
"sig" is a fingerprint of the content: it tells whether an existing file is still up to date.

A pack contains game audio: for personal use only, do not share it.
"""
import datetime
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path[:0] = [HERE, os.path.join(os.path.dirname(HERE), "extractor")]   # the shared modules live with the extractor
import awc_i18n  # noqa: E402
import awc_icons as icons  # noqa: E402

NO_WINDOW = 0x08000000 if os.name == "nt" else 0
MAGIC = b"AWCPACK1"
VERSION = 1
HEADER = 32
EXTENSION = ".radiopak"
PODCAST_EXTENSION = ".podpak"
BYTES_PER_SEC = 16200                       # the game's Opus streams are about 129 kbit/s


class Cancelled(Exception):
    pass


class ExportError(Exception):
    pass


STR = {
    "en": {
        "title": "AWC Radio packs for Android",
        "intro": "Makes one file per station (.radiopak) and per podcast (.podpak): a station's songs in the game's own quality (about 128 kbit/s), the logo and "
                 "the programme. Install AWC-Radio.apk on the phone, copy the packs into one folder there (for example "
                 "\"AWC-Radio\") and choose that folder in the app. Later you only copy the new or changed files. For your own use "
                 "only: the packs contain game audio.",
        "folder": "Folder for the packs on this PC", "browse": "Browse…", "stations": "Stations and podcasts (Ctrl/Shift for several)",
        "all": "All", "none": "None", "changed_only": "New and changed",
        "new": "new", "changed": "changed", "current": "up to date",
        "size": "{n} pack(s) to write, about {s}. Up to date and skipped: {c}",
        "create": "Write packs", "cancel": "Cancel", "close": "Close",
        "st_extract": "{station}: reading … {i}/{n}",
        "done": "{w} pack(s) written, {c} already up to date.\n\nFolder:\n{p}\n\nCopy the new files into the pack folder on the "
                "phone (same names replace the old ones). Please do not share them: they contain game audio.",
        "err_space": "Not enough free disk space in the target folder (about {s} needed).", "err_pick": "Pick at least one station or podcast.",
        "err_folder": "Choose an existing folder for the packs.",
        "err": "Something went wrong:\n{e}", "cancelled": "Cancelled.",
    },
    "de": {
        "title": "AWC Radio Packs für Android",
        "intro": "Erzeugt eine Datei pro Sender (.radiopak) und pro Podcast (.podpak): die Songs eines Senders in der Originalqualität des Spiels (ca. 128 kbit/s), das "
                 "Logo und das Programm. Auf dem Handy AWC-Radio.apk installieren, die Packs dort in einen Ordner kopieren (zum "
                 "Beispiel „AWC-Radio“) und diesen Ordner in der App wählen. Später kopierst du nur die neuen oder geänderten "
                 "Dateien. Nur für den eigenen Gebrauch: die Packs enthalten Spiel-Audio.",
        "folder": "Ordner für die Packs auf diesem PC", "browse": "Durchsuchen…", "stations": "Sender und Podcasts (Strg/Umschalt für mehrere)",
        "all": "Alle", "none": "Keine", "changed_only": "Neue und geänderte",
        "new": "neu", "changed": "geändert", "current": "aktuell",
        "size": "{n} Pack(s) zu schreiben, etwa {s}. Aktuell und übersprungen: {c}",
        "create": "Packs schreiben", "cancel": "Abbrechen", "close": "Schließen",
        "st_extract": "{station}: lese … {i}/{n}",
        "done": "{w} Pack(s) geschrieben, {c} waren schon aktuell.\n\nOrdner:\n{p}\n\nDie neuen Dateien in den Pack-Ordner auf dem "
                "Handy kopieren (gleiche Namen ersetzen die alten). Bitte nicht weitergeben: sie enthalten Spiel-Audio.",
        "err_space": "Im Zielordner ist nicht genug Platz frei (es werden etwa {s} gebraucht).", "err_pick": "Bitte mindestens einen Sender oder Podcast wählen.",
        "err_folder": "Bitte einen vorhandenen Ordner für die Packs wählen.",
        "err": "Es ist ein Fehler aufgetreten:\n{e}", "cancelled": "Abgebrochen.",
    },
}


def t(key, **kw):
    s = STR.get(awc_i18n.get_lang(), STR["en"]).get(key) or STR["en"].get(key, key)
    return s.format(**kw) if kw else s


def human(n):
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return ("%.0f %s" if unit in ("B", "KB") else "%.1f %s") % (n, unit)
        n /= 1024.0


def find_ffmpeg():
    dirs = []
    if getattr(sys, "frozen", False):
        dirs.append(os.path.dirname(sys.executable))
        if getattr(sys, "_MEIPASS", None):
            dirs.append(sys._MEIPASS)
    dirs.append(os.path.dirname(os.path.abspath(__file__)))
    for d in dirs:
        for cand in (os.path.join(d, "ffmpeg.exe"), os.path.join(d, "ffmpeg")):
            if os.path.isfile(cand):
                return cand
    return shutil.which("ffmpeg")


def track_key(tr):
    """The same as awc_radio._tkey: the programme of the app hashes it."""
    parts = tr.get("parts")
    return "%s_%s" % (tr["base"], tr["idx"]) + ("x%d" % len(parts) if parts else "")


def estimate_bytes(stations):
    """Rough size of the packs for the given stations ({name: station dict})."""
    total = 0
    for st in stations.values():
        total += 150_000
        for tr in st["songs"] + st["jingles"] + st.get("ads", []):
            total += tr["sec"] * BYTES_PER_SEC + 200
    return int(total)


# ------------------------------------------------------------------------------------------------ what a pack holds
def pack_sources(index):
    """Everything that gets a pack of its own: the stations and the podcasts of the radio index, {name: entry}."""
    return dict(index["stations"], **index.get("podcasts", {}))


def pack_file(name, podcast=False):
    """File name of a pack: 'The Chamber 106.6' -> 'the_chamber_106_6.radiopak', 'Final Act' -> 'final_act.podpak'."""
    return icons.slug(name) + (PODCAST_EXTENSION if podcast else EXTENSION)


def _logo(name):
    p = icons.icon_path(name)
    if p and os.path.isfile(p):
        with open(p, "rb") as fh:
            return fh.read()
    return b""


def station_entry(name, st, has_logo):
    """The station (or podcast) as it stands in the pack index."""
    logo = "logos/%s.png" % icons.slug(name) if has_logo else ""
    if st.get("podcast"):
        return {"name": name, "logo": logo, "episodes": [{"k": track_key(e), "s": e["sec"]} for e in st["songs"]]}
    entry = {"name": name, "logo": logo,
             "songs": [dict({"k": track_key(s), "t": s["title"], "a": s["artist"], "s": s["sec"]}, **({"m": s["m"]} if s.get("m") else {}))
                       for s in st["songs"]],
             "jingles": [{"k": track_key(j), "s": j["sec"]} for j in st["jingles"]]}
    if st.get("ads"):
        entry["ads"] = [{"k": track_key(a), "t": a["title"], "s": a["sec"]} for a in st["ads"]]
    if any(s.get("parts") and not s.get("set") for s in st["songs"]):      # talk radio, not a club's DJ set
        entry["kind"] = "talk"
    return entry


def signature(name, st, index):
    """Fingerprint of everything that goes into the pack of a station: programme, titles, source streams and logo."""
    logo = _logo(name)
    sources = [[(index["items"].get(str(base)) or {}).get("length"), base, i]
               for tr in st["songs"] + st["jingles"] + st.get("ads", []) for base, i in tr.get("parts") or [(tr["base"], tr["idx"])]]
    h = hashlib.sha1(json.dumps([VERSION, station_entry(name, st, bool(logo)), sources], sort_keys=True,
                                ensure_ascii=False).encode("utf-8"))
    h.update(logo)
    return h.hexdigest()


def read_signature(path):
    """The fingerprint stored in a complete pack file, or "" (no file, damaged, or only partly written)."""
    try:
        with open(path, "rb") as f:
            head = f.read(HEADER)
            if head[:8] != MAGIC:
                return ""
            off, length = struct.unpack_from("<QQ", head, 16)
            if off < HEADER or not 0 < length <= 64 << 20 or off + length != os.fstat(f.fileno()).st_size:
                return ""
            f.seek(off)
            meta = json.loads(f.read(length).decode("utf-8"))
            for st in meta.get("stations", []) + meta.get("podcasts", []):  # every item must be in the file
                for item in st.get("songs", []) + st.get("jingles", []) + st.get("ads", []) + st.get("episodes", []):
                    if "t/" + item["k"] not in meta["files"]:
                        return ""
            return str(meta.get("sig", ""))
    except (OSError, ValueError, struct.error, AttributeError, KeyError, TypeError):
        return ""


def plan(stations, index, folder):
    """-> {station: (path, signature, "new" | "changed" | "current")} for the packs in `folder`."""
    out = {}
    for name, st in stations.items():
        path = os.path.join(folder, pack_file(name, st.get("podcast", False)))
        sig = signature(name, st, index)
        state = "new" if not os.path.isfile(path) else "current" if read_signature(path) == sig else "changed"
        out[name] = (path, sig, state)
    return out


class PackWriter:
    """Writes the pack file: header, files back to back, JSON index at the end (the header is patched last)."""

    def __init__(self, path):
        self.f = open(path, "wb")
        self.f.write(b"\0" * HEADER)
        self.files = {}
        self.lock = threading.Lock()

    def add_file(self, name, src):
        with self.lock:
            off = self.f.tell()
            with open(src, "rb") as s:
                shutil.copyfileobj(s, self.f, 1 << 20)
            self.files[name] = [off, self.f.tell() - off]

    def add_bytes(self, name, data):
        with self.lock:
            off = self.f.tell()
            self.f.write(data)
            self.files[name] = [off, len(data)]

    def finish(self, meta):
        meta = dict(meta, files=self.files)
        idx = json.dumps(meta, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        off = self.f.tell()
        self.f.write(idx)
        self.f.seek(0)
        self.f.write(MAGIC + struct.pack("<IIQQ", VERSION, 0, off, len(idx)))
        self.f.close()

    def abort(self):
        try:
            self.f.close()
        except OSError:
            pass


def _remux(ffmpeg, src, dst):
    """Re-wrap the Opus packets into a clean Ogg file (the audio is untouched); falls back to a plain copy."""
    if ffmpeg:
        r = subprocess.run([ffmpeg, "-v", "error", "-y", "-i", src, "-vn", "-map_metadata", "-1", "-c:a", "copy", "-f", "ogg", dst],
                           capture_output=True, text=True, creationflags=NO_WINDOW)
        if r.returncode == 0 and os.path.isfile(dst) and os.path.getsize(dst) > 0:
            return
    shutil.copyfile(src, dst)


# ------------------------------------------------------------------------------------------------ writing
def _write_tracks(writer, tracks, index, rpf, work, pool, workers, cache_dirs, cancel, step):
    """Cut the tracks out of the archive and add them to the pack. step() is called before each one."""
    import awc_radio as radio  # noqa: PLC0415  (awc_radio imports this module only on demand)
    ffmpeg = find_ffmpeg()
    ahead = threading.Semaphore(workers * 2)           # extraction may run only a few files ahead of the writing
    futures = []

    def finish_one(key, src, own):
        try:
            dst = os.path.join(work, key + ".out")
            _remux(ffmpeg, src, dst)
            writer.add_file("t/" + key, dst)
            for p in (dst, src if own else None):
                if p:
                    try:
                        os.remove(p)
                    except OSError:
                        pass
        finally:
            ahead.release()

    errors = []
    try:
        for key in sorted(tracks):
            if cancel.is_set() or any(x.done() and x.exception() for x in futures):
                break
            while not ahead.acquire(timeout=0.5):
                if cancel.is_set():
                    raise Cancelled()
            src = next((os.path.join(d, "r_%s.opus" % key) for d in cache_dirs
                        if os.path.isfile(os.path.join(d, "r_%s.opus" % key))), None)
            own = src is None
            try:
                step()
                if own:
                    src = os.path.join(work, key + ".orig")
                    radio.extract_track(index, rpf, tracks[key], src + ".part")
                    os.replace(src + ".part", src)
            except BaseException:
                ahead.release()
                raise
            futures.append(pool.submit(finish_one, key, src, own))
    finally:
        for fu in futures:                                  # nothing may still be writing when the pack is closed
            try:
                fu.result()
            except Exception as e:  # noqa: BLE001
                errors.append(e)
    if cancel.is_set():
        raise Cancelled()
    if errors:
        raise ExportError(str(errors[0]))


def export_packs(stations, index, rpf, folder, progress=None, cancel=None, cache_dirs=()):
    """Write one pack per station into `folder`; packs that are up to date stay as they are.

    stations: {name: {"songs": [...], "jingles": [...]}} (the chosen ones); index: the radio index (it knows where each
    song lies in audio.rpf); rpf: path of audio.rpf. progress(fraction, text); cancel: Event.
    Returns {"written": [names], "current": [names], "bytes": size of the written files}."""
    progress = progress or (lambda f, s: None)
    cancel = cancel or threading.Event()
    if not stations:
        raise ExportError(t("err_pick"))
    if not os.path.isdir(folder):
        raise ExportError(t("err_folder"))
    todo = plan(stations, index, folder)
    write = [n for n in stations if todo[n][2] != "current"]
    result = {"written": [], "current": [n for n in stations if todo[n][2] == "current"], "bytes": 0}
    if not write:
        progress(1.0, "")
        return result
    sizes = [estimate_bytes({n: stations[n]}) for n in write]
    need = sum(sizes) * 1.05 + max(sizes)                        # an old pack stays until the new one is complete
    if shutil.disk_usage(folder).free < need + 50_000_000:
        raise ExportError(t("err_space", s=human(need)))
    jobs = {n: {track_key(tr): tr for tr in stations[n]["songs"] + stations[n]["jingles"] + stations[n].get("ads", [])} for n in write}
    total, done = sum(len(j) for j in jobs.values()), [0]
    work = os.path.join(folder, ".awc_pack_work")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    workers = max(1, min(8, os.cpu_count() or 2))
    try:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for name in write:
                path, sig, _state = todo[name]
                writer = PackWriter(path + ".part")
                count = [0]

                def step():
                    count[0] += 1
                    done[0] += 1
                    progress(0.99 * (done[0] - 1) / total, t("st_extract", station=name, i=count[0], n=len(jobs[name])))

                try:
                    _write_tracks(writer, jobs[name], index, rpf, work, pool, workers, cache_dirs, cancel, step)
                    logo = _logo(name)
                    entry = station_entry(name, stations[name], bool(logo))
                    if logo:
                        writer.add_bytes(entry["logo"], logo)
                    meta = {"v": VERSION, "created": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
                            "sig": sig, "stations": [entry]}
                    if stations[name].get("podcast"):
                        meta.update(stations=[], podcasts=[entry])
                    writer.finish(meta)
                    os.replace(path + ".part", path)
                except BaseException:
                    writer.abort()
                    try:
                        os.remove(path + ".part")
                    except OSError:
                        pass
                    raise
                result["written"].append(name)
                result["bytes"] += os.path.getsize(path)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    progress(1.0, "")
    return result


# ------------------------------------------------------------------------------------------------ dialog (used by awc_radio.py)
def open_dialog(app):
    """Dialog for AWC Radio: pick the folder and the stations, then write the station packs."""
    import queue
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    import awc_radio as radio
    sources = pack_sources(app.index)
    names = list(sources)
    win = tk.Toplevel(app)
    win.title(t("title"))
    win.configure(bg=radio.BG)
    win.geometry("680x660")
    win.transient(app)
    q = queue.Queue()
    cancel = threading.Event()
    busy = [False]
    state = {}                                      # station -> "new" | "changed" | "current" in the chosen folder
    var_folder = tk.StringVar(value=app.cfg.get("pack_dir") or "")

    tk.Label(win, text=t("title"), bg=radio.BG, fg=radio.ACCENT, font=("Segoe UI", 16, "bold")).pack(anchor="w", padx=16, pady=(14, 2))
    tk.Label(win, text=t("intro"), bg=radio.BG, fg=radio.DIM, wraplength=640, justify="left").pack(anchor="w", padx=16)
    tk.Label(win, text=t("folder"), bg=radio.BG, fg=radio.FG).pack(anchor="w", padx=16, pady=(10, 2))
    row = tk.Frame(win, bg=radio.BG)
    row.pack(fill="x", padx=16)
    entry = ttk.Entry(row, textvariable=var_folder)
    entry.pack(side="left", fill="x", expand=True)
    btn_browse = ttk.Button(row, text=t("browse"))
    btn_browse.pack(side="left", padx=(8, 0))
    top = tk.Frame(win, bg=radio.BG)
    top.pack(fill="x", padx=16, pady=(10, 2))
    tk.Label(top, text=t("stations"), bg=radio.BG, fg=radio.FG).pack(side="left")
    btns = tk.Frame(win, bg=radio.BG)                # the lower part is packed first: it stays visible in a small window
    btns.pack(side="bottom", fill="x", padx=16, pady=12)
    lbl_status = tk.Label(win, text="", bg=radio.BG, fg=radio.DIM, anchor="w", wraplength=640, justify="left")
    lbl_status.pack(side="bottom", fill="x", padx=16)
    bar = ttk.Progressbar(win, maximum=1.0)
    bar.pack(side="bottom", fill="x", padx=16, pady=(10, 2))
    lbl_size = tk.Label(win, text="", bg=radio.BG, fg=radio.FG, anchor="w", wraplength=640, justify="left")
    lbl_size.pack(side="bottom", fill="x", padx=16, pady=(8, 0))
    box = tk.Frame(win, bg=radio.BG)
    box.pack(fill="both", expand=True, padx=16)
    lb = tk.Listbox(box, selectmode="multiple", exportselection=False, bg=radio.PANEL, fg=radio.FG, selectbackground=radio.CARD_SEL,
                    selectforeground=radio.FG, highlightthickness=0, borderwidth=0, activestyle="none", font=("Segoe UI", 10))
    sb = ttk.Scrollbar(box, orient="vertical", command=lb.yview)
    lb.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    lb.pack(side="left", fill="both", expand=True)
    btn_make = ttk.Button(btns, text=t("create"), style="Accent.TButton")
    btn_make.pack(side="left")
    btn_cancel = ttk.Button(btns, text=t("close"))
    btn_cancel.pack(side="right")

    def chosen():
        return {names[i]: sources[names[i]] for i in lb.curselection()}

    def refresh(*_):
        sel = chosen()
        write = {n: s for n, s in sel.items() if state.get(n) != "current"}
        lbl_size.config(text=t("size", n=len(write), s=human(estimate_bytes(write)), c=len(sel) - len(write)))
        if not busy[0]:
            btn_make.config(state="normal" if sel else "disabled")

    def pick(which):
        if busy[0]:
            return
        lb.select_clear(0, "end")
        for i, nm in enumerate(names):
            if which == "all" or (which == "changed" and state.get(nm) != "current"):
                lb.select_set(i)
        refresh()

    def fill(select_all=False):
        """Show what the chosen folder holds already."""
        selected = set(range(len(names))) if select_all else set(lb.curselection())
        folder = var_folder.get().strip()
        state.clear()
        if os.path.isdir(folder):
            state.update({n: v[2] for n, v in plan(sources, app.index, folder).items()})
        top_row = lb.yview()[0]
        lb.delete(0, "end")
        for nm in names:
            st = sources[nm]
            line = "%s%s   –   %s   (%d)" % ("Podcast: " if st.get("podcast") else "", nm,
                                             human(estimate_bytes({nm: st})), len(st["songs"]))
            if nm in state:
                line += "   ·   %s" % t(state[nm])
            lb.insert("end", line)
        for i in selected:
            lb.select_set(i)
        lb.yview_moveto(top_row)
        refresh()

    ttk.Button(top, text=t("none"), command=lambda: pick("none")).pack(side="right")
    ttk.Button(top, text=t("all"), command=lambda: pick("all")).pack(side="right", padx=6)
    ttk.Button(top, text=t("changed_only"), command=lambda: pick("changed")).pack(side="right")

    def browse():
        p = filedialog.askdirectory(parent=win, title=t("folder"), initialdir=var_folder.get() or None)
        if p:
            var_folder.set(os.path.normpath(p))
            fill()

    def start():
        sel = chosen()
        folder = var_folder.get().strip()
        if not os.path.isdir(folder):
            browse()
            return
        if not sel:
            messagebox.showwarning(t("title"), t("err_pick"), parent=win)
            return
        app.cfg["pack_dir"] = folder
        radio.save_settings(app.cfg)
        busy[0] = True
        cancel.clear()
        for w in (btn_make, btn_browse, entry, lb):
            w.config(state="disabled")
        btn_cancel.config(text=t("cancel"))

        def work():
            try:
                res = export_packs(sel, app.index, app.cfg["rpf"], folder, lambda f, s: q.put(("prog", f, s)), cancel,
                                   cache_dirs=(radio.cache_dir(),))
                q.put(("done", folder, res))
            except Cancelled:
                q.put(("fail", t("cancelled")))
            except Exception as e:  # noqa: BLE001
                q.put(("fail", str(e) if isinstance(e, ExportError) else t("err", e=e)))
        threading.Thread(target=work, daemon=True).start()

    def finish_ui():
        busy[0] = False
        btn_cancel.config(text=t("close"))
        for w in (btn_browse, entry, lb):
            w.config(state="normal")
        bar["value"] = 0
        fill()

    def poll():
        try:
            while True:
                m = q.get_nowait()
                if m[0] == "prog":
                    bar["value"] = m[1]
                    lbl_status.config(text=m[2])
                elif m[0] == "done":
                    finish_ui()
                    lbl_status.config(text="")
                    messagebox.showinfo(t("title"), t("done", w=len(m[2]["written"]), c=len(m[2]["current"]), p=m[1]), parent=win)
                elif m[0] == "fail":
                    finish_ui()
                    lbl_status.config(text=m[1])
                    messagebox.showerror(t("title"), m[1], parent=win)
        except queue.Empty:
            pass
        if win.winfo_exists():
            win.after(150, poll)

    def close():
        if busy[0]:
            cancel.set()
            lbl_status.config(text=t("cancelled"))
        else:
            win.destroy()

    lb.bind("<<ListboxSelect>>", refresh)
    entry.bind("<Return>", lambda _e: fill())
    entry.bind("<FocusOut>", lambda _e: None if busy[0] else fill())
    btn_browse.config(command=browse)
    btn_make.config(command=start)
    btn_cancel.config(command=close)
    win.protocol("WM_DELETE_WINDOW", close)
    fill(select_all=True)
    poll()


# ------------------------------------------------------------------------------------------------ command line (for tests / scripts)
def main(argv=None):
    import argparse
    import awc_radio as radio
    ap = argparse.ArgumentParser(description="Write AWC Radio packs (.radiopak, .podpak) for the Android app")
    ap.add_argument("folder", help="folder for the packs (one file per station and podcast); up-to-date files are left alone")
    ap.add_argument("--stations", nargs="*", help="station and podcast names (default: all)")
    a = ap.parse_args(argv)
    cfg = radio.load_settings()
    idx = radio.load_index(cfg["rpf"], cfg.get("pool", ""))
    if not idx:
        raise SystemExit("no radio index yet: start AWC Radio once")
    stations = {k: v for k, v in pack_sources(idx).items() if not a.stations or k in a.stations}
    os.makedirs(a.folder, exist_ok=True)
    t0 = time.time()
    try:
        res = export_packs(stations, idx, cfg["rpf"], a.folder, lambda f, s: print("\r%3d%% %s" % (f * 100, s), end="   "),
                           cache_dirs=(radio.cache_dir(),))
    except (ExportError, Cancelled) as e:
        raise SystemExit("error: %s" % e) from e                # exit code 1, no error dialog in the windowed exe
    print("\nOK %d written (%s), %d up to date, in %.0fs" % (len(res["written"]), human(res["bytes"]), len(res["current"]),
                                                             time.time() - t0))


if __name__ == "__main__":
    main()
