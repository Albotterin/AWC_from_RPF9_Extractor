"""AWC Tool - find, play and extract RAGE AWC music streams.

Finds music streams in standalone AWC files and (via header scan for 'ADAT') inside RPF archives.
A whole folder can be searched recursively for *.rpf and *.awc files.
The user interface language can be switched at runtime (English / Deutsch).

Requires Python 3 (tkinter) and ffmpeg/ffplay in the PATH (or next to the program).
Run:  python awc_tool.py [--lang=en|de] [folder | file.awc | file.rpf ...]
"""
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import awc_core as core  # noqa: E402
import awc_i18n  # noqa: E402
from awc_core import AwcError, AwcFile, AwcRef  # noqa: E402
from awc_i18n import LANGUAGES, tr  # noqa: E402

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
# format id -> (extension, ffmpeg args, approx. bytes per second of stereo audio)
FORMATS = {
    "opus": ("opus", None, 16000),
    "flac": ("flac", ["-c:a", "flac"], 170000),
    "wav": ("wav", ["-c:a", "pcm_s16le"], 192000),
    "mp3": ("mp3", ["-c:a", "libmp3lame", "-b:a", "320k"], 40000),
}
FORMAT_IDS = list(FORMATS)


def find_tool(name):
    """Look for ffmpeg/ffplay next to the exe / script first, then in PATH."""
    dirs = []
    if getattr(sys, "frozen", False):
        dirs.append(os.path.dirname(sys.executable))
        if getattr(sys, "_MEIPASS", None):
            dirs.append(sys._MEIPASS)
    dirs.append(os.path.dirname(os.path.abspath(__file__)))
    for d in dirs:
        for cand in (os.path.join(d, name + ".exe"), os.path.join(d, name)):
            if os.path.isfile(cand):
                return cand
    return shutil.which(name)


def fmt_time(sec):
    sec = max(0, int(sec))
    h, r = divmod(sec, 3600)
    m, s = divmod(r, 60)
    return "%d:%02d:%02d" % (h, m, s) if h else "%02d:%02d" % (m, s)


def fmt_size(n):
    n = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return "%.0f %s" % (n, unit) if unit == "B" else "%.1f %s" % (n, unit)
        n /= 1024.0


def safe_name(s):
    return "".join(c if c not in '<>:"/\\|?*' else "_" for c in s)


class App(tk.Tk):
    def __init__(self, paths):
        super().__init__()
        self.geometry("1040x720")
        self.minsize(900, 620)

        self.ffmpeg = find_tool("ffmpeg")
        self.ffplay = find_tool("ffplay")
        self.cache_dir = tempfile.mkdtemp(prefix="awctool_")
        self.q = queue.Queue()
        self.nodes = {}            # iid -> ("container", dict) | ("awc", AwcRef) | ("stream", (AwcRef, AwcStream))
        self.refs = {}             # ref.key -> AwcRef
        self.containers = set()    # paths that are already loaded
        self.cache = {}            # (ref.key, stream_idx) -> (opus_path, seconds)  (playback/extract cache)
        self.busy = False
        self.cancel_flag = False
        self._i18n = []            # (widget, option, key) for live re-translation
        self._status = ("st_ready", {})

        # player state
        self.proc = None
        self.cur = None            # (AwcRef, AwcStream)
        self.cur_path = None
        self.duration = 0.0
        self.pos = 0.0
        self.t0 = 0.0
        self.playing = False
        self.dragging = False
        self.want_play = None

        self._build()
        self.retranslate()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.after(100, self._poll)
        self.after(200, self._tick)
        if not self.ffmpeg or not self.ffplay:
            messagebox.showwarning(tr("dlg_ffmpeg_title"), tr("dlg_ffmpeg_body"))
        if paths:
            self.after(300, lambda: self.start_scan(paths))

    # ------------------------------------------------------------ i18n helpers
    def _reg(self, widget, option, key):
        """Register a widget option that is filled from the translation table."""
        self._i18n.append((widget, option, key))
        widget.configure(**{option: tr(key)})
        return widget

    def set_status(self, key, **kw):
        self._status = (key, kw)
        self.status.config(text=tr(key, **kw))

    def _fmt_id(self):
        i = self.fmt.current()
        return FORMAT_IDS[i if i >= 0 else 0]

    def _set_fmt(self, fmt_id):
        self.fmt.current(FORMAT_IDS.index(fmt_id))

    def retranslate(self):
        """Apply the current language to every visible text."""
        self.title(tr("app_title"))
        for widget, option, key in self._i18n:
            widget.configure(**{option: tr(key)})
        self.tree.heading("#0", text=tr("h_tree"))
        for col, key in (("id", "h_id"), ("codec", "h_codec"), ("ch", "h_ch"), ("dur", "h_dur"), ("size", "h_size")):
            self.tree.heading(col, text=tr(key))
        idx = self.fmt.current()
        self.fmt.config(values=[tr("fmt_" + f) for f in FORMAT_IDS])
        self.fmt.current(idx if idx >= 0 else 0)
        self._set_play_btn()
        self.status.config(text=tr(self._status[0], **self._status[1]))
        if self.cur:
            self.lbl_now.config(text="%s  –  %s" % (self.cur[0].name, self.cur[1].label))
        else:
            self.lbl_now.config(text=tr("lbl_no_track"))
        self._retranslate_tree()
        self._update_count()
        self._update_ui()

    def _retranslate_tree(self):
        for iid, (kind, obj) in self.nodes.items():
            if kind == "stream":
                ref, s = obj
                self.tree.item(iid, text=s.label)
                self.tree.set(iid, "ch", tr("stereo") if s.opus_channels == 2 else tr("mono"))
            elif kind == "container":
                self.tree.set(iid, "ch", tr("n_awc", n=len(obj["items"])))

    def change_language(self, _e=None):
        code = list(LANGUAGES)[self.lang_box.current()]
        awc_i18n.set_lang(code)
        awc_i18n.save_lang(code)
        self.retranslate()

    # ------------------------------------------------------------------ GUI
    def _build(self):
        pad = {"padx": 8, "pady": 4}
        top = ttk.Frame(self)
        top.pack(fill="x", **pad)
        self._reg(ttk.Button(top, command=self.pick_folder), "text", "btn_scan_folder").pack(side="left")
        self._reg(ttk.Button(top, command=self.open_files), "text", "btn_open_files").pack(side="left", padx=6)
        self._reg(ttk.Button(top, command=self.clear), "text", "btn_clear").pack(side="left")
        self.use_cache = tk.BooleanVar(value=True)
        self._reg(ttk.Checkbutton(top, variable=self.use_cache), "text", "chk_cache").pack(side="left", padx=12)
        self.btn_scan_cancel = self._reg(ttk.Button(top, command=self.cancel, state="disabled"), "text", "btn_scan_cancel")
        self.btn_scan_cancel.pack(side="left")
        # language switch (right side)
        self.lang_box = ttk.Combobox(top, values=list(LANGUAGES.values()), state="readonly", width=10)
        self.lang_box.current(list(LANGUAGES).index(awc_i18n.get_lang()))
        self.lang_box.pack(side="right")
        self.lang_box.bind("<<ComboboxSelected>>", self.change_language)
        self._reg(ttk.Label(top), "text", "lbl_language").pack(side="right", padx=(12, 4))
        self.lbl_count = ttk.Label(top, text="")
        self.lbl_count.pack(side="right", padx=8)

        mid = ttk.Frame(self)
        mid.pack(fill="both", expand=True, **pad)
        cols = ("id", "codec", "ch", "dur", "size")
        self.tree = ttk.Treeview(mid, columns=cols, selectmode="extended")
        for c, w in (("id", 90), ("codec", 70), ("ch", 70), ("dur", 90), ("size", 90)):
            self.tree.column(c, width=w, anchor="center")
        self.tree.column("#0", width=470)
        sb = ttk.Scrollbar(mid, orient="vertical", command=self.tree.yview)
        self.tree.configure(yscrollcommand=sb.set)
        self.tree.pack(side="left", fill="both", expand=True)
        sb.pack(side="left", fill="y")
        self.tree.bind("<Double-1>", self._dbl)

        pl = self._reg(ttk.LabelFrame(self), "text", "lf_player")
        pl.pack(fill="x", **pad)
        row = ttk.Frame(pl)
        row.pack(fill="x", padx=8, pady=6)
        self.btn_play = ttk.Button(row, width=13, command=self.toggle_play)
        self.btn_play.pack(side="left")
        self._reg(ttk.Button(row, width=8, command=self.stop), "text", "btn_stop").pack(side="left", padx=4)
        self.lbl_time = ttk.Label(row, text="00:00 / 00:00", width=18, anchor="center")
        self.lbl_time.pack(side="left", padx=6)
        self.seek = ttk.Scale(row, from_=0, to=1, orient="horizontal")
        self.seek.pack(side="left", fill="x", expand=True)
        self.seek.bind("<ButtonPress-1>", lambda e: setattr(self, "dragging", True))
        self.seek.bind("<ButtonRelease-1>", self._seek_release)
        self._reg(ttk.Label(row), "text", "lbl_volume").pack(side="left", padx=(12, 4))
        self.vol = ttk.Scale(row, from_=0, to=100, orient="horizontal", length=110)
        self.vol.set(80)
        self.vol.pack(side="left")
        self.vol.bind("<ButtonRelease-1>", self._vol_release)
        self.lbl_now = ttk.Label(pl, foreground="gray40")
        self.lbl_now.pack(anchor="w", padx=10, pady=(0, 6))

        ex = self._reg(ttk.LabelFrame(self), "text", "lf_extract")
        ex.pack(fill="x", **pad)
        r1 = ttk.Frame(ex)
        r1.pack(fill="x", padx=8, pady=(6, 2))
        self._reg(ttk.Label(r1), "text", "lbl_format").pack(side="left")
        self.fmt = ttk.Combobox(r1, state="readonly", width=30)
        self.fmt.config(values=[tr("fmt_" + f) for f in FORMAT_IDS])
        self.fmt.current(0)
        self.fmt.pack(side="left", padx=6)
        self._reg(ttk.Label(r1), "text", "lbl_outdir").pack(side="left", padx=(12, 0))
        self.outdir = tk.StringVar(value=os.path.join(os.path.expanduser("~"), "Music", "AWC-Export"))
        ttk.Entry(r1, textvariable=self.outdir).pack(side="left", fill="x", expand=True, padx=6)
        ttk.Button(r1, text="…", width=3, command=self.pick_out).pack(side="left")
        r2 = ttk.Frame(ex)
        r2.pack(fill="x", padx=8, pady=(2, 6))
        self.btn_sel = self._reg(ttk.Button(r2, command=lambda: self.extract(False)), "text", "btn_extract_sel")
        self.btn_sel.pack(side="left")
        self.btn_all = self._reg(ttk.Button(r2, command=lambda: self.extract(True)), "text", "btn_extract_all")
        self.btn_all.pack(side="left", padx=6)
        self.btn_cancel = self._reg(ttk.Button(r2, command=self.cancel, state="disabled"), "text", "btn_cancel")
        self.btn_cancel.pack(side="left")
        ttk.Separator(r2, orient="vertical").pack(side="left", fill="y", padx=10)
        self.btn_flac = self._reg(ttk.Button(r2, command=lambda: self.extract(False, "flac")), "text", "btn_to_flac")
        self.btn_flac.pack(side="left")
        self.btn_wav = self._reg(ttk.Button(r2, command=lambda: self.extract(False, "wav")), "text", "btn_to_wav")
        self.btn_wav.pack(side="left", padx=6)
        self._reg(ttk.Button(r2, command=self.open_out), "text", "btn_open_out").pack(side="right")
        self.minsec = tk.StringVar(value="0")
        ttk.Spinbox(r2, from_=0, to=36000, increment=10, width=6, textvariable=self.minsec).pack(
            side="right", padx=(0, 4))
        self._reg(ttk.Label(r2), "text", "lbl_minsec").pack(side="right", padx=(10, 4))

        bot = ttk.Frame(self)
        bot.pack(fill="x", padx=8, pady=(0, 8))
        self.bar = ttk.Progressbar(bot, maximum=1.0)
        self.bar.pack(fill="x")
        self.status = ttk.Label(bot, text="")
        self.status.pack(anchor="w", pady=(2, 0))

    # ----------------------------------------------------------------- scanning
    def pick_folder(self):
        d = filedialog.askdirectory(title=tr("dlg_pick_folder"))
        if d:
            self.start_scan([d])

    def open_files(self):
        paths = filedialog.askopenfilenames(
            title=tr("dlg_pick_files"),
            filetypes=[(tr("ft_awc_rpf"), "*.awc *.rpf"), (tr("ft_awc"), "*.awc"), (tr("ft_rpf"), "*.rpf"),
                       (tr("ft_all"), "*.*")])
        if paths:
            self.start_scan(list(paths))

    def start_scan(self, paths):
        if self.busy:
            messagebox.showinfo(tr("dlg_busy_title"), tr("dlg_busy"))
            return
        files = []
        for p in paths:
            p = os.path.abspath(p)
            if os.path.isdir(p):
                files.extend(core.collect_files(p))
            elif os.path.isfile(p):
                files.append(p)
        files = [f for f in files if f not in self.containers]
        if not files:
            self.set_status("st_no_new_files")
            return
        self.cancel_flag = False
        self._set_busy(True, scanning=True)
        self.set_status("st_scanning_n", n=len(files))
        threading.Thread(target=self._scan_worker, args=(files, self.use_cache.get()), daemon=True).start()

    def _scan_worker(self, files, use_cache):
        try:
            res = core.scan_files(
                files,
                progress=lambda fr, nm, nt: self.q.put(("scanprog", fr, nm)),
                cancel=lambda: self.cancel_flag,
                status=lambda t: self.q.put(("status", t)),
                use_cache=use_cache)
            self.q.put(("scanned", res, files))
        except InterruptedError:
            self.q.put(("done", "st_scan_cancelled", {}))
        except Exception as e:  # noqa: BLE001
            self.q.put(("done", "st_scan_error", {"e": e}))

    def _add_containers(self, res, files):
        n_awc = n_str = 0
        for c in res:
            self.containers.add(c["path"])
            items = c["items"]
            tot_sec = sum(max((s["sec"] for s in it["streams"]), default=0) for it in items)
            try:
                csize = fmt_size(os.path.getsize(c["path"]))
            except OSError:
                csize = ""
            cid = self.tree.insert("", "end", text="%s  [%s]" % (c["path"], c["kind"].upper()), open=False,
                                   values=("", "", tr("n_awc", n=len(items)), fmt_time(tot_sec), csize))
            self.nodes[cid] = ("container", c)
            for it in items:
                ref = AwcRef(it)
                self.refs[ref.key] = ref
                n_awc += 1
                nsec = max((s.est_seconds for s in ref.streams), default=0)
                if not ref.in_rpf:
                    parent = cid          # standalone AWC: streams directly under the container node
                    self.tree.item(cid, text=ref.path)
                else:
                    parent = self.tree.insert(cid, "end", text=ref.name, open=False,
                                              values=("", "", len(ref.streams), "~" + fmt_time(nsec),
                                                      fmt_size(ref.size)))
                    self.nodes[parent] = ("awc", ref)
                for s in ref.streams:
                    sid = self.tree.insert(parent, "end", text=s.label,
                                           values=("%08X" % s.id, s.codec_name,
                                                   tr("stereo") if s.opus_channels == 2 else tr("mono"),
                                                   "~" + fmt_time(s.est_seconds), ""))
                    self.nodes[sid] = ("stream", (ref, s))
                    n_str += 1
        self._update_count()
        if not res:
            self.set_status("st_scan_none", n=len(files))
        else:
            self.set_status("st_scan_done", c=len(res), a=n_awc, s=n_str)

    def _update_count(self):
        streams = [v[1][1] for v in self.nodes.values() if v[0] == "stream"]
        if streams:
            self.lbl_count.config(text=tr("count_label", n=len(streams), t=fmt_time(sum(s.est_seconds for s in streams))))
        else:
            self.lbl_count.config(text="")

    def clear(self):
        if self.busy:
            return
        self.stop()
        self.cur = None
        self.lbl_now.config(text=tr("lbl_no_track"))
        self.tree.delete(*self.tree.get_children())
        self.nodes.clear()
        self.refs.clear()
        self.containers.clear()
        self.cache.clear()
        self._update_count()
        for f in os.listdir(self.cache_dir):
            try:
                os.remove(os.path.join(self.cache_dir, f))
            except OSError:
                pass

    def _streams_under(self, iid, out, seen):
        if self.nodes.get(iid, (None,))[0] == "stream":
            if iid not in seen:
                seen.add(iid)
                out.append(self.nodes[iid][1])
            return
        for ch in self.tree.get_children(iid):
            self._streams_under(ch, out, seen)

    def _selected_streams(self):
        out, seen = [], set()
        for iid in self.tree.selection():
            self._streams_under(iid, out, seen)
        return out

    def _all_streams(self):
        return [v[1] for v in self.nodes.values() if v[0] == "stream"]

    def _dbl(self, e):
        iid = self.tree.identify_row(e.y)
        if iid and self.nodes.get(iid, (None,))[0] == "stream":
            self._load_and_play(*self.nodes[iid][1], start=0.0)

    # --------------------------------------------------------------- extraction
    def pick_out(self):
        d = filedialog.askdirectory(title=tr("dlg_pick_out"), initialdir=self.outdir.get() or None)
        if d:
            self.outdir.set(d)

    def open_out(self):
        d = self.outdir.get()
        if os.path.isdir(d):
            os.startfile(d)
        else:
            messagebox.showinfo(tr("dlg_folder_title"), tr("dlg_folder_missing"))

    def cancel(self):
        self.cancel_flag = True
        self.set_status("st_cancelling")

    def _set_busy(self, busy, scanning=False):
        self.busy = busy
        st = "disabled" if busy else "normal"
        for b in (self.btn_sel, self.btn_all, self.btn_flac, self.btn_wav):
            b.config(state=st)
        self.btn_cancel.config(state="normal" if busy and not scanning else "disabled")
        self.btn_scan_cancel.config(state="normal" if busy and scanning else "disabled")

    def extract(self, everything, fmt_id=None):
        if self.busy:
            return
        items = self._all_streams() if everything else self._selected_streams()
        if not items:
            messagebox.showinfo(tr("dlg_nothing_title"), tr("dlg_nothing"))
            return
        if fmt_id:
            self._set_fmt(fmt_id)
        fmt_id = self._fmt_id()
        ext, ff_args, bps = FORMATS[fmt_id]
        if ff_args and not self.ffmpeg:
            messagebox.showerror(tr("dlg_ffmpeg_title"), tr("dlg_need_ffmpeg"))
            return
        try:
            min_sec = float(self.minsec.get().replace(",", "."))
        except ValueError:
            min_sec = 0.0
        if min_sec > 0:
            items = [(a, s) for a, s in items if s.est_seconds >= min_sec]
            if not items:
                messagebox.showinfo(tr("dlg_filter_title"), tr("dlg_filter_none", s=int(min_sec)))
                return
        items = [(a, s) for a, s in items if s.supported]
        if not items:
            messagebox.showwarning(tr("dlg_unsupported_title"), tr("dlg_all_unsupported"))
            return
        out = self.outdir.get().strip()
        sec = sum(s.est_seconds for _a, s in items)
        est = sec * bps
        try:
            os.makedirs(out, exist_ok=True)
            free = shutil.disk_usage(out).free
        except OSError as e:
            messagebox.showerror(tr("dlg_outdir_title"), str(e))
            return
        msg = tr("dlg_confirm_body", n=len(items), dur=fmt_time(sec), size=fmt_size(est), free=fmt_size(free),
                 fmt=tr("fmt_" + fmt_id), out=out)
        if est > free * 0.95:
            messagebox.showerror(tr("dlg_no_space"), msg)
            return
        if len(items) > 20 or est > 5e9:
            if not messagebox.askyesno(tr("dlg_confirm_title"), msg + "\n\n" + tr("dlg_continue")):
                return
        self.cancel_flag = False
        self._set_busy(True)
        threading.Thread(target=self._extract_worker, args=(items, out, ext, ff_args), daemon=True).start()

    def _extract_worker(self, items, out, ext, ff_args):
        try:
            # group by AWC so that every AWC is read only once
            groups, order = {}, []
            for ref, s in items:
                if ref.key not in groups:
                    groups[ref.key] = (ref, [])
                    order.append(ref.key)
                groups[ref.key][1].append(s)
            total = sum(max(1, ref.size) for ref, _ in groups.values()) or 1
            done = 0.0
            made = 0
            for gi, k in enumerate(order):
                ref, streams = groups[k]
                w = max(1, ref.size) / float(total)
                folder = os.path.join(out, safe_name(ref.container)) if ref.in_rpf else out
                os.makedirs(folder, exist_ok=True)
                jobs, final = {}, {}
                for s in streams:
                    base = ref.name if ref.in_rpf else os.path.splitext(ref.name)[0]
                    name = "%s_stream%d_%08X.%s" % (safe_name(base), s.index, s.id, ext)
                    final[s.index] = os.path.join(folder, name)
                    jobs[s.index] = final[s.index] if ext == "opus" else os.path.join(
                        self.cache_dir, "x_%d_%d_%d.opus" % (os.getpid(), gi, s.index))
                self.q.put(("statuskey", "st_group", {"i": gi + 1, "n": len(order), "name": ref.name,
                                                      "k": len(streams)}))
                share = w if ext == "opus" else w * 0.5
                need = dict(jobs)
                for i in list(need):   # already in the playback cache?
                    c = self.cache.get((ref.key, i))
                    if c and os.path.isfile(c[0]):
                        if ext == "opus":
                            shutil.copyfile(c[0], final[i])
                        else:
                            jobs[i] = c[0]
                        del need[i]
                if need:
                    ref.extract(need, progress=lambda f, d=done, sh=share: self.q.put(("progress", d + f * sh)),
                                cancel=lambda: self.cancel_flag)
                if ext != "opus":
                    for s in streams:
                        self._ffmpeg_convert(jobs[s.index], final[s.index], ff_args, s.est_seconds,
                                             done + share, w * 0.5 / len(streams))
                        if os.path.basename(jobs[s.index]).startswith("x_"):
                            try:
                                os.remove(jobs[s.index])
                            except OSError:
                                pass
                made += len(streams)
                done += w
                self.q.put(("progress", done))
            self.q.put(("done", "st_extract_done", {"n": made, "out": out}))
        except InterruptedError:
            self.q.put(("done", "st_cancelled", {}))
        except Exception as e:  # noqa: BLE001
            self.q.put(("error", str(e)))

    def _ffmpeg_convert(self, src, dst, ff_args, dur, lo, span):
        cmd = [self.ffmpeg, "-y", "-v", "error", "-nostats", "-progress", "pipe:1", "-i", src] + ff_args + [dst]
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                             creationflags=NO_WINDOW)
        for line in p.stdout:
            if self.cancel_flag:
                p.kill()
                p.wait()
                try:
                    os.remove(dst)
                except OSError:
                    pass
                raise InterruptedError()
            if line.startswith("out_time_us=") and dur > 0:
                try:
                    t = int(line.split("=")[1]) / 1e6
                    self.q.put(("progress", lo + min(1.0, t / dur) * span))
                except ValueError:
                    pass
        err = p.stderr.read()
        if p.wait() != 0:
            raise RuntimeError(tr("err_ffmpeg", e=err[-800:]))

    # ------------------------------------------------------------------- player
    def _set_play_btn(self):
        self.btn_play.config(text=tr("btn_pause") if self.playing else tr("btn_play"))

    def _load_and_play(self, ref, s, start=0.0):
        if not s.supported:
            messagebox.showinfo(tr("dlg_unsupported_title"), tr("dlg_codec_play", c=s.codec_name))
            return
        if not self.ffplay:
            messagebox.showerror(tr("dlg_ffplay_title"), tr("dlg_ffplay"))
            return
        self._kill()
        self.playing = False
        self._set_play_btn()
        self.cur = (ref, s)
        self.duration = s.est_seconds
        self.pos = start
        self.lbl_now.config(text="%s  –  %s" % (ref.name, s.label))
        c = self.cache.get((ref.key, s.index))
        if c and os.path.isfile(c[0]):
            self.cur_path = c[0]
            self.duration = c[1]
            self._start_ffplay(start)
            return
        if self.busy:
            self.set_status("st_wait_other")
            return
        self.cancel_flag = False
        self._set_busy(True)
        self.set_status("st_preparing")
        self.want_play = (ref.key, s.index, start)
        threading.Thread(target=self._cache_worker, args=(ref, s), daemon=True).start()

    def _cache_worker(self, ref, s):
        try:
            dst = os.path.join(self.cache_dir, "play_%d_%d.opus" % (abs(hash(ref.key)), s.index))
            secs = ref.extract({s.index: dst}, progress=lambda f: self.q.put(("progress", f)),
                               cancel=lambda: self.cancel_flag)[s.index]
            self.q.put(("cached", (ref.key, s.index), dst, secs))
        except InterruptedError:
            self.q.put(("done", "st_cancelled", {}))
        except Exception as e:  # noqa: BLE001
            self.q.put(("error", str(e)))

    def toggle_play(self):
        if self.playing:
            self._pause()
            return
        sel = self._selected_streams()
        if self.cur is None or (sel and (sel[0][0].key, sel[0][1].index) != (self.cur[0].key, self.cur[1].index)):
            if not sel:
                messagebox.showinfo(tr("dlg_player_title"), tr("dlg_select_first"))
                return
            self._load_and_play(*sel[0], start=0.0)
        else:
            self._load_and_play(self.cur[0], self.cur[1], start=self.pos)

    def _start_ffplay(self, start):
        self._kill()
        if start >= self.duration - 0.5:
            start = 0.0
        cmd = [self.ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet", "-ss", "%.3f" % start,
               "-volume", str(int(self.vol.get())), self.cur_path]
        try:
            self.proc = subprocess.Popen(cmd, creationflags=NO_WINDOW)
        except OSError as e:
            messagebox.showerror("ffplay", str(e))
            return
        self.pos = start
        self.t0 = time.monotonic()
        self.playing = True
        self._set_play_btn()
        self.set_status("st_playing")

    def _kill(self):
        if self.proc and self.proc.poll() is None:
            self.proc.kill()
            try:
                self.proc.wait(timeout=2)
            except subprocess.TimeoutExpired:
                pass
        self.proc = None

    def _pause(self):
        if self.playing:
            self.pos = min(self.duration, self.pos + time.monotonic() - self.t0)
        self._kill()
        self.playing = False
        self._set_play_btn()
        self.set_status("st_paused")

    def stop(self):
        self.want_play = None
        self._kill()
        self.playing = False
        self.pos = 0.0
        self._set_play_btn()
        self._update_ui()

    def _seek_release(self, _e):
        self.dragging = False
        if self.cur is None or self.duration <= 0:
            return
        target = float(self.seek.get()) * self.duration
        self.pos = target
        if self.playing:
            self._start_ffplay(target)
        else:
            self._update_ui()

    def _vol_release(self, _e):
        if self.playing:
            self.pos = min(self.duration, self.pos + time.monotonic() - self.t0)
            self._start_ffplay(self.pos)

    def _tick(self):
        if self.playing:
            cur = self.pos + (time.monotonic() - self.t0)
            if self.proc is not None and self.proc.poll() is not None:
                self.playing = False
                self.pos = 0.0
                self._set_play_btn()
                self.set_status("st_ended")
            else:
                self._update_ui(cur)
        self.after(200, self._tick)

    def _update_ui(self, cur=None):
        cur = self.pos if cur is None else cur
        cur = min(cur, self.duration) if self.duration else cur
        self.lbl_time.config(text="%s / %s" % (fmt_time(cur), fmt_time(self.duration)))
        if not self.dragging and self.duration > 0:
            self.seek.set(cur / self.duration)

    # --------------------------------------------------------------- event pump
    def _poll(self):
        try:
            while True:
                msg = self.q.get_nowait()
                kind = msg[0]
                if kind == "progress":
                    self.bar["value"] = msg[1]
                elif kind == "scanprog":
                    self.bar["value"] = msg[1]
                    self.lbl_count.config(text=tr("scan_progress", p=int(msg[1] * 100), n=msg[2]))
                elif kind == "status":                 # already translated text (from core)
                    self.status.config(text=msg[1])
                elif kind == "statuskey":
                    self.set_status(msg[1], **msg[2])
                elif kind == "scanned":
                    self._set_busy(False)
                    self.bar["value"] = 0
                    self._add_containers(msg[1], msg[2])
                elif kind == "done":
                    cancelled = msg[1] in ("st_cancelled", "st_scan_cancelled", "st_scan_error")
                    self.bar["value"] = 0 if cancelled else 1.0
                    self.set_status(msg[1], **msg[2])
                    self._set_busy(False)
                    self._update_count()
                elif kind == "error":
                    self.bar["value"] = 0
                    self._set_busy(False)
                    self.set_status("st_error")
                    self._update_count()
                    messagebox.showerror(tr("err_title"), msg[1])
                elif kind == "cached":
                    self.cache[msg[1]] = (msg[2], msg[3])
                    self._set_busy(False)
                    self.bar["value"] = 0
                    w = self.want_play
                    self.want_play = None
                    if w and self.cur and (self.cur[0].key, self.cur[1].index) == (w[0], w[1]):
                        self.cur_path = msg[2]
                        self.duration = msg[3]
                        self._start_ffplay(w[2])
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _close(self):
        self.cancel_flag = True
        self._kill()
        shutil.rmtree(self.cache_dir, ignore_errors=True)
        self.destroy()


def selftest(out_path, awc_path):
    """Check mode for the built exe: writes tool paths and a parser/ffmpeg check to a file."""
    lines = []
    ff, fp = find_tool("ffmpeg"), find_tool("ffplay")
    lines.append("ffmpeg=%s" % ff)
    lines.append("ffplay=%s" % fp)
    lines.append("lang=%s" % awc_i18n.get_lang())
    try:
        a = AwcFile(awc_path)
        lines.append("awc streams=%d blocks=%d" % (len(a.streams), a.n_blocks))
        lines.append("parse ok")
    except Exception as e:  # noqa: BLE001
        lines.append("awc error: %r" % e)
    if ff:
        r = subprocess.run([ff, "-version"], capture_output=True, text=True, creationflags=NO_WINDOW)
        lines.append("ffmpeg -version rc=%d: %s" % (r.returncode, r.stdout.splitlines()[0] if r.stdout else ""))
    with open(out_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")


def main():
    awc_i18n.load_saved_lang()
    args = []
    for a in sys.argv[1:]:
        if a.startswith("--lang="):
            awc_i18n.set_lang(a.split("=", 1)[1])
        else:
            args.append(a)
    if len(args) >= 3 and args[0] == "--selftest":
        selftest(args[1], args[2])
        return
    app = App([a for a in args if os.path.exists(a)])
    app.mainloop()


if __name__ == "__main__":
    main()
