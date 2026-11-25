"""The score window of AWC Tool: read the game's score from audio.rpf, browse it by group and mission, listen to a
piece, save pieces as Ogg Opus files and write the score packs for the Android app. See awc_score.
"""
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import tkinter as tk
from concurrent.futures import ThreadPoolExecutor
from tkinter import filedialog, messagebox, ttk

import awc_score as score
from awc_i18n import tr

try:
    import awc_android as android
except ImportError:                    # in the repository the writer of the app packs lives with the radio
    sys.path.append(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "radio"))
    import awc_android as android

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def clock(sec):
    sec = int(round(sec))
    return "%d:%02d" % (sec // 60, sec % 60)


def safe(name):
    out = "".join(c if c not in '<>:"/\\|?*' and ord(c) >= 32 else "_" for c in name).strip(" .")
    return out[:100] or "_"


def group_name(group):
    return tr("score_" + group)


def default_archive(app):
    """audio.rpf among the scanned files, else the one AWC Radio uses, else ""."""
    paths = [c["path"] for c in app.loaded if c["path"].lower().endswith(".rpf")]
    for p in paths:
        if os.path.basename(p).lower() == "audio.rpf":
            return p
    try:
        import awc_radio  # noqa: PLC0415
        p = awc_radio.load_settings().get("rpf", "")
        if p and os.path.isfile(p):
            return p
    except (ImportError, OSError, ValueError):
        pass
    return paths[0] if paths else ""


class ScoreWindow(tk.Toplevel):
    def __init__(self, app):
        super().__init__(app)
        self.app = app
        self.title(tr("score_title"))
        self.geometry("980x640")
        self.minsize(760, 480)
        self.q = queue.Queue()
        self.cancel = threading.Event()
        self.busy = False
        self.pieces = []
        self.nodes = {}                    # tree row -> [pieces under it]
        self.proc = None
        self.preview = tempfile.mkdtemp(prefix="awcscore_")
        self.wanted = None                 # the piece that should play when it is mixed
        self.archive = tk.StringVar(value=default_archive(app))
        self.search = tk.StringVar()
        self.minsec = tk.StringVar(value=str(int(score_min())))
        self._build()
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.after(150, self._poll)
        if self.archive.get():
            self.after(200, self.read)
        else:
            self.status.config(text=tr("score_pick"))

    # ----------------------------------------------------------------- screen
    def _build(self):
        pad = {"padx": 8, "pady": 4}
        top = ttk.Frame(self)
        top.pack(fill="x", **pad)
        ttk.Label(top, text="audio.rpf:").pack(side="left")
        ttk.Entry(top, textvariable=self.archive).pack(side="left", fill="x", expand=True, padx=6)
        self.btn_pick = ttk.Button(top, text="…", width=3, command=self.pick)
        self.btn_pick.pack(side="left")
        self.btn_read = ttk.Button(top, text=tr("score_read"), command=lambda: self.read(force=True))
        self.btn_read.pack(side="left", padx=(6, 0))
        intro = ttk.Label(self, text=tr("score_intro"), foreground="gray35", wraplength=940, justify="left")
        intro.pack(fill="x", padx=10)
        intro.bind("<Configure>", lambda e: intro.config(wraplength=max(200, e.width - 8)))
        flt = ttk.Frame(self)
        flt.pack(fill="x", **pad)
        ttk.Label(flt, text=tr("score_search")).pack(side="left")
        ttk.Entry(flt, textvariable=self.search, width=30).pack(side="left", padx=6)
        ttk.Label(flt, text=tr("score_minsec")).pack(side="left", padx=(12, 4))
        ttk.Spinbox(flt, from_=0, to=600, increment=10, width=5, textvariable=self.minsec).pack(side="left")
        self.lbl_count = ttk.Label(flt, text="")
        self.lbl_count.pack(side="right")
        for var in (self.search, self.minsec):
            var.trace_add("write", lambda *_: self._fill_soon())

        self.status = ttk.Label(self, text="", anchor="w", wraplength=940, justify="left")
        self.status.pack(side="bottom", fill="x", padx=10, pady=(2, 8))
        self.bar = ttk.Progressbar(self, maximum=1.0)
        self.bar.pack(side="bottom", fill="x", padx=8)
        act = ttk.Frame(self)
        act.pack(side="bottom", fill="x", **pad)
        self.btn_sel = ttk.Button(act, text=tr("btn_extract_sel"), command=lambda: self.extract(False))
        self.btn_sel.pack(side="left")
        self.btn_all = ttk.Button(act, text=tr("score_extract_all"), command=lambda: self.extract(True))
        self.btn_all.pack(side="left", padx=6)
        self.btn_packs = ttk.Button(act, text=tr("score_packs"), command=self.packs)
        self.btn_packs.pack(side="left")
        self.btn_cancel = ttk.Button(act, text=tr("btn_cancel"), command=self.cancel.set, state="disabled")
        self.btn_cancel.pack(side="left", padx=6)
        out = ttk.Frame(self)
        out.pack(side="bottom", fill="x", **pad)
        ttk.Label(out, text=tr("lbl_outdir")).pack(side="left")
        ttk.Entry(out, textvariable=self.app.outdir).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(out, text="…", width=3, command=self.app.pick_out).pack(side="left")
        play = ttk.Frame(self)
        play.pack(side="bottom", fill="x", **pad)
        self.btn_play = ttk.Button(play, text=tr("btn_play"), width=13, command=self.play)
        self.btn_play.pack(side="left")
        ttk.Button(play, text=tr("btn_stop"), width=8, command=self.stop).pack(side="left", padx=4)
        self.lbl_now = ttk.Label(play, text="", foreground="gray35")
        self.lbl_now.pack(side="left", padx=8)

        mid = ttk.Frame(self)
        mid.pack(fill="both", expand=True, **pad)
        cols = ("len", "bpm", "parts", "name")
        self.tree = ttk.Treeview(mid, columns=cols, selectmode="extended")
        self.tree.heading("#0", text=tr("score_h_title"))
        for c, key, w in (("len", "h_dur", 70), ("bpm", "score_h_bpm", 55), ("parts", "score_h_parts", 80),
                          ("name", "score_h_name", 320)):
            self.tree.heading(c, text=tr(key))
            self.tree.column(c, width=w, anchor="w" if c == "name" else "center", stretch=c == "name")
        self.tree.column("#0", width=380)
        sb = ttk.Scrollbar(mid, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        self.tree.bind("<Double-1>", self._double)

        self._set_busy(False)

    def _set_busy(self, busy):
        self.busy = busy
        ready = "disabled" if busy or not self.pieces else "normal"
        for w in (self.btn_sel, self.btn_all, self.btn_packs):
            w.config(state=ready)
        for w in (self.btn_pick, self.btn_read):
            w.config(state="disabled" if busy else "normal")
        self.btn_play.config(state="normal" if self.pieces else "disabled")
        self.btn_cancel.config(state="normal" if busy else "disabled")

    def _min_seconds(self):
        try:
            return max(0.0, float(self.minsec.get().replace(",", ".") or 0))
        except ValueError:
            return 0.0

    def _fill_soon(self):
        if getattr(self, "_pending", None):
            self.after_cancel(self._pending)
        self._pending = self.after(300, self.fill)

    def shown(self):
        """The pieces that pass the filters, in their order."""
        words = self.search.get().lower().split()
        low = self._min_seconds()
        out = []
        for p in self.pieces:
            if p["sec"] < low:
                continue
            text = " ".join((group_name(p["group"]), p["sub"], p["title"], p["name"])).lower()
            if all(w in text for w in words):
                out.append(p)
        return out

    def fill(self):
        self._pending = None
        self.tree.delete(*self.tree.get_children())
        self.nodes.clear()
        shown = self.shown()
        groups, subs = {}, {}
        for p in shown:
            g = p["group"]
            if g not in groups:
                groups[g] = self.tree.insert("", "end", text=group_name(g), open=bool(self.search.get()))
                self.nodes[groups[g]] = []
            key = (g, p["sub"])
            if key not in subs:
                subs[key] = self.tree.insert(groups[g], "end", text=p["sub"], open=bool(self.search.get()))
                self.nodes[subs[key]] = []
            row = self.tree.insert(subs[key], "end", text=p["title"],
                                   values=(clock(p["sec"]), "%g" % p["bpm"], len(p["parts"]), p["name"]))
            self.nodes[row] = [p]
            self.nodes[groups[g]].append(p)
            self.nodes[subs[key]].append(p)
        for row in list(groups.values()) + list(subs.values()):
            total = sum(p["sec"] for p in self.nodes[row])
            self.tree.item(row, values=(clock(total), "", tr("score_n", n=len(self.nodes[row])), ""))
        self.lbl_count.config(text=tr("score_count", n=len(shown), t=len(self.pieces),
                                      h="%.1f" % (sum(p["sec"] for p in shown) / 3600)))

    # ----------------------------------------------------------------- reading
    def pick(self):
        p = filedialog.askopenfilename(parent=self, title="audio.rpf",
                                       filetypes=[("RPF", "*.rpf"), (tr("ft_all"), "*.*")])
        if p:
            self.archive.set(os.path.normpath(p))
            self.read()

    def _work(self, job):
        self.cancel.clear()
        self._set_busy(True)

        def run():
            try:
                job()
            except (InterruptedError, android.Cancelled):
                self.q.put(("done", tr("st_cancelled")))
            except Exception as e:  # noqa: BLE001
                self.q.put(("error", str(e)))
        threading.Thread(target=run, daemon=True).start()

    def read(self, force=False):
        path = self.archive.get().strip()
        if self.busy:
            return
        if not os.path.isfile(path):
            self.status.config(text=tr("score_pick"))
            return
        self.status.config(text=tr("score_reading"))

        def job():
            pieces = score.load(path, progress=lambda f: self.q.put(("progress", f)), cancel=self.cancel.is_set,
                                use_cache=not force)
            self.q.put(("loaded", pieces))
        self._work(job)

    # ----------------------------------------------------------------- listening
    def _chosen(self):
        sel = self.tree.selection()
        return self.nodes.get(sel[0], [None])[0] if sel else None

    def _double(self, e):
        row = self.tree.identify_row(e.y)
        if row and not self.tree.get_children(row):
            self.tree.selection_set(row)
            self.play()

    def play(self):
        piece = self._chosen()
        if not piece:
            self.status.config(text=tr("score_select"))
            return
        if not self.app.ffplay or not self.app.ffmpeg:
            messagebox.showerror(tr("dlg_ffmpeg_title"), tr("dlg_ffmpeg_body"), parent=self)
            return
        self.app.stop()
        self.stop()
        self.wanted = piece["id"]
        dst = os.path.join(self.preview, piece["id"] + ".opus")
        if os.path.isfile(dst):
            self._start(piece, dst)
            return
        if self.busy:
            self.status.config(text=tr("st_wait_other"))
            return
        self.status.config(text=tr("score_mixing", name=piece["title"]))
        path, ffmpeg = self.archive.get().strip(), self.app.ffmpeg
        spare = os.path.join(self.app.outdir.get().strip(), ".score_preview")

        def job():
            folder = self.preview
            need = int(piece["sec"] * 2_500_000)          # generous: all layers of the piece unpacked
            if shutil.disk_usage(folder).free < need:     # the temporary folder is full: next to the output files
                os.makedirs(spare, exist_ok=True)
                folder = self.preview = spare
            out = os.path.join(folder, piece["id"] + ".opus")
            score.render(path, piece, out, ffmpeg, cancel=self.cancel.is_set)
            self.q.put(("mixed", piece, out))
        self._work(job)

    def _start(self, piece, path):
        self.stop(keep=True)
        cmd = [self.app.ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", str(int(self.app.vol.get())), path]
        try:
            self.proc = subprocess.Popen(cmd, creationflags=NO_WINDOW)
        except OSError as e:
            messagebox.showerror("ffplay", str(e), parent=self)
            return
        self.lbl_now.config(text="%s – %s – %s  (%s)" % (group_name(piece["group"]), piece["sub"], piece["title"],
                                                         clock(piece["sec"])))
        self.status.config(text=tr("st_playing"))

    def stop(self, keep=False):
        if not keep:
            self.wanted = None
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
        self.proc = None
        self.lbl_now.config(text="")

    # ----------------------------------------------------------------- saving
    def extract(self, everything):
        if everything:
            pieces = self.shown()
        else:
            found = {}
            for row in self.tree.selection():
                for p in self.nodes.get(row, []):
                    found[p["id"]] = p
            pieces = list(found.values())
        if not pieces:
            self.status.config(text=tr("score_select"))
            return
        out = os.path.join(self.app.outdir.get().strip(), "Score")
        path, ffmpeg = self.archive.get().strip(), self.app.ffmpeg
        if not ffmpeg:
            messagebox.showerror(tr("dlg_ffmpeg_title"), tr("dlg_ffmpeg_body"), parent=self)
            return

        def job():
            done, kept = [0], [0]

            def one(p):
                if self.cancel.is_set():
                    return
                dst = os.path.join(out, safe(group_name(p["group"])), safe(p["sub"]), safe(p["title"]) + ".opus")
                if os.path.isfile(dst):
                    kept[0] += 1
                else:
                    score.render(path, p, dst, ffmpeg, cancel=self.cancel.is_set,
                                 tags={"TITLE": p["title"], "ALBUM": "%s – %s" % (group_name(p["group"]), p["sub"]),
                                       "COMMENT": p["name"]})
                done[0] += 1
                self.q.put(("count", done[0], len(pieces)))
            with ThreadPoolExecutor(max_workers=max(1, min(4, (os.cpu_count() or 2) - 1))) as pool:
                futures = [pool.submit(one, p) for p in pieces]
                try:
                    for f in futures:
                        f.result()
                except BaseException:
                    for f in futures:
                        f.cancel()
                    raise
            if self.cancel.is_set():
                raise InterruptedError()
            self.q.put(("done", tr("score_saved", n=done[0] - kept[0], k=kept[0], p=out)))
        self.status.config(text=tr("score_saving", i=0, n=len(pieces)))
        self._work(job)

    def packs(self):
        folder = filedialog.askdirectory(parent=self, title=tr("score_pick_packs"))
        if not folder:
            return
        path = self.archive.get().strip()
        low = self._min_seconds()

        def job():
            sources = android.score_sources(path, low)
            res = android.export_packs(sources, {}, path, os.path.normpath(folder),
                                       progress=lambda f, s: self.q.put(("packs", f, s)), cancel=self.cancel)
            self.q.put(("done", tr("score_packed", w=len(res["written"]), c=len(res["current"]), p=folder)))
        self._work(job)

    # ----------------------------------------------------------------- messages of the workers
    def _poll(self):
        try:
            while True:
                m = self.q.get_nowait()
                if m[0] == "progress":
                    self.bar["value"] = m[1]
                elif m[0] == "count":
                    self.bar["value"] = m[1] / float(m[2])
                    self.status.config(text=tr("score_saving", i=m[1], n=m[2]))
                elif m[0] == "packs":
                    self.bar["value"] = m[1]
                    self.status.config(text=m[2])
                elif m[0] == "loaded":
                    self.pieces = m[1]
                    self.bar["value"] = 0
                    self._set_busy(False)
                    self.fill()
                    self.status.config(text=tr("score_ready"))
                elif m[0] == "mixed":
                    self._set_busy(False)
                    if self.wanted == m[1]["id"]:
                        self._start(m[1], m[2])
                elif m[0] in ("done", "error"):
                    self.bar["value"] = 0
                    self._set_busy(False)
                    self.status.config(text=m[1])
                    if m[0] == "error":
                        messagebox.showerror(tr("err_title"), m[1], parent=self)
        except queue.Empty:
            pass
        if self.proc and self.proc.poll() is not None:
            self.proc = None
            self.lbl_now.config(text="")
            self.status.config(text=tr("st_ended"))
        self.after(150, self._poll)

    def close(self):
        self.cancel.set()
        self.stop()
        shutil.rmtree(self.preview, ignore_errors=True)
        self.destroy()


def score_min():
    return android.SCORE_MIN


def open_window(app):
    """One score window at a time."""
    win = getattr(app, "score_window", None)
    if win is not None and win.winfo_exists():
        win.lift()
        win.focus_set()
        return win
    app.score_window = ScoreWindow(app)
    return app.score_window
