"""AWC Radio station pack for the Android app.

The Android app (AWC-Radio.apk, the same file for everybody, no game data in it) plays the stations live by the UTC
clock, exactly like AWC Radio on the PC. Its music comes from ONE file next to it: the station pack (.dat) that is made
here from the user's own audio.rpf - the songs of the chosen stations in the game's own quality (about 128 kbit/s,
not re-encoded), their logos and the programme data. On the first start the app asks for that file; the songs are played
straight out of it, the huge audio.rpf never goes to the phone.

Pack layout (little endian), read by android/java/org/awctool/radio/Pack.java:
    0   8 bytes  magic "AWCPACK1"
    8   u32      version (1)
    12  u32      reserved
    16  u64      offset of the index
    24  u64      length of the index
    32  ...      files back to back: Ogg Opus songs and PNG logos
    idx          UTF-8 JSON {"v":1,"created":..,"stations":[..],"files":{"t/<key>":[offset,length],"logos/<n>.png":[..]}}
                 a song of a station may carry "m": [[start second, title, artist], ...], the parts of a long DJ mix (optional,
                 older apps ignore it)

The pack contains game audio: for personal use only, do not share it.
"""
import datetime
import json
import os
import shutil
import struct
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import awc_i18n  # noqa: E402
import awc_icons as icons  # noqa: E402

NO_WINDOW = 0x08000000 if os.name == "nt" else 0
MAGIC = b"AWCPACK1"
VERSION = 1
HEADER = 32
BYTES_PER_SEC = 16200                       # the game's Opus streams are about 129 kbit/s


class Cancelled(Exception):
    pass


class ExportError(Exception):
    pass


STR = {
    "en": {
        "title": "AWC Radio station pack for Android",
        "intro": "Makes ONE file (.dat) with the stations you pick: their songs in the game's own quality (about 128 kbit/s), "
                 "the logos and the programme. Install AWC-Radio.apk on the phone (same file for everybody, from the GitHub release), "
                 "copy the .dat to the phone and pick it when the app starts for the first time. For your own use only: the pack contains game audio.",
        "stations": "Stations (Ctrl/Shift for several)", "all": "All", "none": "None", "size": "Estimated size: {s}",
        "create": "Create pack…", "cancel": "Cancel", "close": "Close", "save_title": "Save the station pack as",
        "st_extract": "Reading songs … {i}/{n}", "st_write": "Writing the pack … {i}/{n}", "st_done": "Finishing …",
        "done": "The station pack is ready:\n{p}\n\nSize: {s}\n\nOn the phone: install AWC-Radio.apk (from the GitHub release), copy this file "
                "to the phone, start the app and pick the file. Please do not share it: it contains game audio.",
        "err_space": "Not enough free disk space in the target folder (about {s} needed).", "err_pick": "Pick at least one station.",
        "err": "Something went wrong:\n{e}", "cancelled": "Cancelled.",
    },
    "de": {
        "title": "AWC Radio Sender-Pack für Android",
        "intro": "Erzeugt EINE Datei (.dat) mit den gewählten Sendern: ihre Songs in der Originalqualität des Spiels (ca. 128 kbit/s), "
                 "die Logos und das Programm. Auf dem Handy AWC-Radio.apk installieren (für alle dieselbe Datei, aus dem GitHub-Release), "
                 "die .dat aufs Handy kopieren und beim ersten Start der App auswählen. Nur für den eigenen Gebrauch: das Pack enthält Spiel-Audio.",
        "stations": "Sender (Strg/Umschalt für mehrere)", "all": "Alle", "none": "Keine", "size": "Geschätzte Größe: {s}",
        "create": "Pack erstellen…", "cancel": "Abbrechen", "close": "Schließen", "save_title": "Sender-Pack speichern unter",
        "st_extract": "Lese Songs … {i}/{n}", "st_write": "Schreibe das Pack … {i}/{n}", "st_done": "Schließe ab …",
        "done": "Das Sender-Pack ist fertig:\n{p}\n\nGröße: {s}\n\nAuf dem Handy: AWC-Radio.apk installieren (aus dem GitHub-Release), diese Datei "
                "aufs Handy kopieren, die App starten und die Datei auswählen. Bitte nicht weitergeben: sie enthält Spiel-Audio.",
        "err_space": "Im Zielordner ist nicht genug Platz frei (es werden etwa {s} gebraucht).", "err_pick": "Bitte mindestens einen Sender wählen.",
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
    """Rough size of the pack for the given stations ({name: station dict})."""
    total = 300_000
    for st in stations.values():
        for tr in st["songs"] + st["jingles"]:
            total += tr["sec"] * BYTES_PER_SEC + 200
    return int(total)


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


def export_pack(stations, index, rpf, out_path, progress=None, cancel=None, cache_dirs=()):
    """Write the station pack. stations: {name: {"songs": [...], "jingles": [...]}} (the chosen ones); index: the radio
    index (it knows where each song lies in audio.rpf); rpf: path of audio.rpf. progress(fraction, text); cancel: Event.
    Returns the size of the pack in bytes."""
    import awc_radio as radio  # noqa: PLC0415  (awc_radio imports this module only on demand)
    progress = progress or (lambda f, s: None)
    cancel = cancel or threading.Event()
    if not stations:
        raise ExportError(t("err_pick"))
    out_dir = os.path.dirname(os.path.abspath(out_path))
    need = estimate_bytes(stations)
    if shutil.disk_usage(out_dir).free < need * 1.15 + 50_000_000:
        raise ExportError(t("err_space", s=human(need * 1.15)))
    ffmpeg = find_ffmpeg()
    work = os.path.join(out_dir, ".awc_pack_work")
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    tracks = {}
    for st in stations.values():
        for tr in st["songs"] + st["jingles"]:
            tracks[track_key(tr)] = tr
    keys = sorted(tracks)
    n = len(keys)
    tmp = out_path + ".part"
    writer = PackWriter(tmp)
    done = [0]
    lock = threading.Lock()
    workers = max(1, min(8, os.cpu_count() or 2))
    ahead = threading.Semaphore(workers * 2)           # extraction may run only a few files ahead of the writing
    errors = []

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
            with lock:
                done[0] += 1
                progress(0.95 * done[0] / n, t("st_write", i=done[0], n=n))
        finally:
            ahead.release()

    try:
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = []
            for i, key in enumerate(keys):
                if cancel.is_set() or errors:
                    break
                while not ahead.acquire(timeout=0.5):
                    if cancel.is_set():
                        break
                if cancel.is_set():
                    ahead.release()
                    break
                tr = tracks[key]
                src = next((os.path.join(d, "r_%s.opus" % key) for d in cache_dirs
                            if os.path.isfile(os.path.join(d, "r_%s.opus" % key))), None)
                own = src is None
                try:
                    if own:
                        src = os.path.join(work, key + ".orig")
                        radio.extract_track(index, rpf, tr, src + ".part")
                        os.replace(src + ".part", src)
                except BaseException:
                    ahead.release()
                    raise
                progress(0.95 * done[0] / n, t("st_extract", i=i + 1, n=n))
                fu = pool.submit(finish_one, key, src, own)
                futures.append(fu)
                for x in [x for x in futures if x.done() and x.exception()]:
                    errors.append(x.exception())
            for fu in futures:
                try:
                    fu.result()
                except Exception as e:  # noqa: BLE001
                    errors.append(e)
        if cancel.is_set():
            raise Cancelled()
        if errors:
            raise ExportError(str(errors[0]))
        progress(0.96, t("st_done"))
        meta = {"v": VERSION, "created": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "stations": []}
        for name, st in stations.items():
            logo = ""
            p = icons.icon_path(name)
            if p and os.path.isfile(p):
                logo = "logos/%s.png" % icons.slug(name)
                with open(p, "rb") as fh:
                    writer.add_bytes(logo, fh.read())
            meta["stations"].append({
                "name": name, "logo": logo,
                "songs": [dict({"k": track_key(s), "t": s["title"], "a": s["artist"], "s": s["sec"]}, **({"m": s["m"]} if s.get("m") else {}))
                          for s in st["songs"]],
                "jingles": [{"k": track_key(j), "s": j["sec"]} for j in st["jingles"]]})
        writer.finish(meta)
        os.replace(tmp, out_path)
    except BaseException:
        writer.abort()
        try:
            os.remove(tmp)
        except OSError:
            pass
        raise
    finally:
        shutil.rmtree(work, ignore_errors=True)
    progress(1.0, "")
    return os.path.getsize(out_path)


# ------------------------------------------------------------------------------------------------ dialog (used by awc_radio.py)
def open_dialog(app):
    """Dialog for AWC Radio: pick the stations, then write the station pack."""
    import queue
    import tkinter as tk
    from tkinter import filedialog, messagebox, ttk

    import awc_radio as radio
    names = list(app.stations)
    win = tk.Toplevel(app)
    win.title(t("title"))
    win.configure(bg=radio.BG)
    win.geometry("640x600")
    win.transient(app)
    q = queue.Queue()
    cancel = threading.Event()
    busy = [False]

    tk.Label(win, text=t("title"), bg=radio.BG, fg=radio.ACCENT, font=("Segoe UI", 16, "bold")).pack(anchor="w", padx=16, pady=(14, 2))
    tk.Label(win, text=t("intro"), bg=radio.BG, fg=radio.DIM, wraplength=600, justify="left").pack(anchor="w", padx=16)
    top = tk.Frame(win, bg=radio.BG)
    top.pack(fill="x", padx=16, pady=(10, 2))
    tk.Label(top, text=t("stations"), bg=radio.BG, fg=radio.FG).pack(side="left")
    box = tk.Frame(win, bg=radio.BG)
    box.pack(fill="both", expand=True, padx=16)
    lb = tk.Listbox(box, selectmode="multiple", exportselection=False, bg=radio.PANEL, fg=radio.FG, selectbackground=radio.CARD_SEL,
                    selectforeground=radio.FG, highlightthickness=0, borderwidth=0, activestyle="none", font=("Segoe UI", 10))
    sb = ttk.Scrollbar(box, orient="vertical", command=lb.yview)
    lb.configure(yscrollcommand=sb.set)
    sb.pack(side="right", fill="y")
    lb.pack(side="left", fill="both", expand=True)
    for nm in names:
        st = app.stations[nm]
        lb.insert("end", "%s   –   %s   (%d)" % (nm, human(estimate_bytes({nm: st})), len(st["songs"])))
    lb.select_set(0, "end")
    ttk.Button(top, text=t("none"), command=lambda: (lb.select_clear(0, "end"), refresh())).pack(side="right")
    ttk.Button(top, text=t("all"), command=lambda: (lb.select_set(0, "end"), refresh())).pack(side="right", padx=6)
    lbl_size = tk.Label(win, text="", bg=radio.BG, fg=radio.FG, anchor="w", wraplength=600, justify="left")
    lbl_size.pack(fill="x", padx=16, pady=(8, 0))
    bar = ttk.Progressbar(win, maximum=1.0)
    bar.pack(fill="x", padx=16, pady=(10, 2))
    lbl_status = tk.Label(win, text="", bg=radio.BG, fg=radio.DIM, anchor="w", wraplength=600, justify="left")
    lbl_status.pack(fill="x", padx=16)
    btns = tk.Frame(win, bg=radio.BG)
    btns.pack(fill="x", padx=16, pady=12)
    btn_make = ttk.Button(btns, text=t("create"), style="Accent.TButton")
    btn_make.pack(side="left")
    btn_cancel = ttk.Button(btns, text=t("close"))
    btn_cancel.pack(side="right")

    def chosen():
        return {names[i]: app.stations[names[i]] for i in lb.curselection()}

    def refresh(*_):
        sel = chosen()
        lbl_size.config(text=t("size", s=human(estimate_bytes(sel))))
        if not busy[0]:
            btn_make.config(state="normal" if sel else "disabled")

    lb.bind("<<ListboxSelect>>", refresh)

    def start():
        sel = chosen()
        if not sel:
            messagebox.showwarning(t("title"), t("err_pick"), parent=win)
            return
        p = filedialog.asksaveasfilename(parent=win, title=t("save_title"), defaultextension=".dat", initialfile="AWC-Radio.dat",
                                         initialdir=app.cfg.get("pack_dir") or None, filetypes=[("AWC station pack", "*.dat")])
        if not p:
            return
        app.cfg["pack_dir"] = os.path.dirname(p)
        radio.save_settings(app.cfg)
        busy[0] = True
        cancel.clear()
        btn_make.config(state="disabled")
        btn_cancel.config(text=t("cancel"))
        lb.config(state="disabled")

        def work():
            try:
                size = export_pack(sel, app.index, app.cfg["rpf"], p, lambda f, s: q.put(("prog", f, s)), cancel,
                                   cache_dirs=(radio.cache_dir(),))
                q.put(("done", p, size))
            except Cancelled:
                q.put(("fail", t("cancelled")))
            except Exception as e:  # noqa: BLE001
                q.put(("fail", str(e) if isinstance(e, ExportError) else t("err", e=e)))
        threading.Thread(target=work, daemon=True).start()

    def finish_ui():
        busy[0] = False
        btn_cancel.config(text=t("close"))
        lb.config(state="normal")
        bar["value"] = 0
        refresh()

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
                    messagebox.showinfo(t("title"), t("done", p=m[1], s=human(m[2])), parent=win)
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

    btn_make.config(command=start)
    btn_cancel.config(command=close)
    win.protocol("WM_DELETE_WINDOW", close)
    refresh()
    poll()


# ------------------------------------------------------------------------------------------------ command line (for tests / scripts)
def main(argv=None):
    import argparse
    import awc_radio as radio
    ap = argparse.ArgumentParser(description="Write an AWC Radio station pack (.dat) for the Android app")
    ap.add_argument("out", help="output file, e.g. AWC-Radio.dat")
    ap.add_argument("--stations", nargs="*", help="station names (default: all)")
    a = ap.parse_args(argv)
    cfg = radio.load_settings()
    idx = radio.load_index(cfg["rpf"], cfg.get("pool", ""))
    if not idx:
        raise SystemExit("no radio index yet: start AWC Radio once")
    stations = {k: v for k, v in idx["stations"].items() if not a.stations or k in a.stations}
    t0 = time.time()
    try:
        size = export_pack(stations, idx, cfg["rpf"], a.out, lambda f, s: print("\r%3d%% %s" % (f * 100, s), end="   "),
                           cache_dirs=(radio.cache_dir(),))
    except (ExportError, Cancelled) as e:
        raise SystemExit("error: %s" % e) from e                # exit code 1, no error dialog in the windowed exe
    print("\nOK %s (%s) in %.0fs" % (a.out, human(size), time.time() - t0))


if __name__ == "__main__":
    main()
