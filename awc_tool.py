"""AWC Tool - find, play and extract RAGE AWC music streams.

Finds music streams in standalone AWC files and (via header scan for 'ADAT') inside RPF archives.
A whole folder can be searched recursively for *.rpf and *.awc files.
The user interface language can be switched at runtime (English / Deutsch).

Requires Python 3 (tkinter) and ffmpeg/ffplay in the PATH (or next to the program).
Run:  python awc_tool.py [--lang=en|de] [folder | file.awc | file.rpf ...]
"""
import bisect
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import tkinter as tk
import json
from tkinter import filedialog, messagebox, simpledialog, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import awc_core as core  # noqa: E402
import awc_i18n  # noqa: E402
import awc_config as gamecfg  # noqa: E402
import awc_credits as credits  # noqa: E402
import awc_icons as icons  # noqa: E402
import awc_names as names  # noqa: E402
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


def _names_file():
    return os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool", "group_names.json")


def load_group_names():
    """{archive path (lower case): {group number as str: custom name}} - set via right click > Rename group."""
    try:
        with open(_names_file(), "r", encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def save_group_names(d):
    try:
        os.makedirs(os.path.dirname(_names_file()), exist_ok=True)
        with open(_names_file(), "w", encoding="utf-8") as f:
            json.dump(d, f, ensure_ascii=False, indent=1)
    except OSError:
        pass


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
        self.group_names = load_group_names()
        self.names = names.NameStore()   # known titles/artists per stream ID
        self.stations = names.load_stations()   # station names found in the game config
        self._runs = None          # cache of _station_runs()
        self.icons_busy = False
        self._icon_img = None
        self.cue_info = {}         # ref.key -> [(standalone AWC name, stream id) or None] per cue of a mix
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
        self.after(900, self._maybe_icon_prompt)

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
            self.lbl_now.config(text="%s  –  %s" % (self.cur[0].name, self._stream_text(self.cur[1])))
        else:
            self.lbl_now.config(text=tr("lbl_no_track"))
        self._retranslate_tree()
        self._update_count()
        self._update_ui()

    @staticmethod
    def _gkey(cpath):
        return os.path.normcase(os.path.abspath(cpath))

    def _gname(self, cpath, g):
        return self.group_names.get(self._gkey(cpath), {}).get(str(g), "")

    def _stream_text(self, s):
        label = names.track_label(self.names.get(s.id))
        return "%s   [%s]" % (label, s.label) if label else s.label

    def _station(self, ref, s):
        rec = self.names.get(s.id) or {}
        st = rec.get("station")
        if st:
            return st
        st = self._inferred_station(ref)
        if st:
            return st
        name = self._gname(ref.path, ref.group) if ref.in_rpf and ref.group else ""
        return "" if " + " in name else name

    def _cue_label(self, ref, k):
        """Name of the track that starts at marker k of a mix: its title if known, else its number."""
        val = ref.cues[k][1]
        link = (self.cue_info.get(ref.key) or [None] * (k + 1))[k]
        title = names.track_label(self.names.get(link[1])) if link else ""
        return title or tr("cue_track", v=val)

    def _cue_text(self, ref, k):
        return "%s  %s" % (fmt_time(ref.cues[k][0]), self._cue_label(ref, k))

    def _group_text(self, cpath, g):
        name = self._gname(cpath, g)
        return tr("grp_label_named", g=g, name=name) if name else tr("grp_label", g=g)

    def _group_dir(self, ref):
        """Output sub folder of an AWC inside an archive: group_03 or group_03_<custom name>."""
        if not ref.in_rpf or not ref.group:
            return ""
        name = self._gname(ref.path, ref.group)
        return "group_%02d" % ref.group + ("_" + safe_name(name) if name else "")

    def _retranslate_tree(self):
        for iid, (kind, obj) in self.nodes.items():
            if kind == "group":
                self.tree.item(iid, text=self._group_text(obj[0], obj[1]))
                self.tree.set(iid, "ch", tr("n_awc", n=obj[2]))
            elif kind == "stream":
                ref, s = obj
                self.tree.item(iid, text=self._stream_text(s))
                self.tree.set(iid, "ch", tr("stereo") if s.opus_channels == 2 else tr("mono"))
            elif kind == "cue":
                self.tree.item(iid, text=self._cue_text(obj[0], obj[2]))
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
        self.tree.bind("<Button-3>", self._context_menu)

        nf = self._reg(ttk.LabelFrame(self), "text", "lf_names")
        nf.pack(fill="x", **pad)
        nrow = ttk.Frame(nf)
        nrow.pack(fill="x", padx=8, pady=6)
        self._reg(ttk.Button(nrow, command=self.import_names), "text", "btn_names_import").pack(side="left")
        self._reg(ttk.Button(nrow, command=self.export_template), "text", "btn_names_template").pack(side="left", padx=6)
        self._reg(ttk.Button(nrow, command=self.clear_names), "text", "btn_names_clear").pack(side="left")
        self.write_playlist = tk.BooleanVar(value=True)
        self._reg(ttk.Checkbutton(nrow, variable=self.write_playlist), "text", "chk_playlist").pack(side="left", padx=14)
        self.lbl_named = ttk.Label(nrow, text="", foreground="gray40")
        self.lbl_named.pack(side="right")
        nrow2 = ttk.Frame(nf)
        nrow2.pack(fill="x", padx=8, pady=(0, 6))
        self._reg(ttk.Button(nrow2, command=self.read_game_names), "text", "btn_names_game").pack(side="left")
        self._reg(ttk.Button(nrow2, command=self.read_credits), "text", "btn_names_credits").pack(side="left", padx=(6, 0))
        self._reg(ttk.Button(nrow2, command=self.icons_button), "text", "btn_icons").pack(side="left", padx=(6, 0))
        self.split_mixes = tk.BooleanVar(value=False)
        self._reg(ttk.Checkbutton(nrow2, variable=self.split_mixes), "text", "chk_split").pack(side="left", padx=14)

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
        info = ttk.Frame(pl)
        info.pack(fill="x", padx=10, pady=(0, 6))
        self.lbl_icon = ttk.Label(info)
        self.lbl_now = ttk.Label(info, foreground="gray40")
        self.lbl_now.pack(side="left")

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
            # per-group totals (groups = clusters of AWCs that sit together in the archive, see core.add_groups)
            gstat = {}
            for it in items:
                st = gstat.setdefault(it.get("g", 0), [0, 0.0, 0])
                st[0] += 1
                st[1] += max((s["sec"] for s in it["streams"]), default=0)
                st[2] += it["length"] or 0
            tids = {}          # (group, track number) -> standalone AWC item that carries that number
            for it in items:
                if it.get("tid") is not None:
                    tids.setdefault((it.get("g", 0), it["tid"]), it)
            gnodes = {}
            for it in sorted(items, key=lambda x: x["base"]):
                ref = AwcRef(it)
                self.refs[ref.key] = ref
                n_awc += 1
                nsec = max((s.est_seconds for s in ref.streams), default=0)
                if not ref.in_rpf:
                    parent = cid          # standalone AWC: streams directly under the container node
                    self.tree.item(cid, text=ref.path)
                else:
                    gparent = cid
                    if ref.group:
                        if ref.group not in gnodes:
                            n, sec, size = gstat[ref.group]
                            gnodes[ref.group] = self.tree.insert(
                                cid, "end", text=self._group_text(c["path"], ref.group), open=False,
                                values=("", "", tr("n_awc", n=n), fmt_time(sec), fmt_size(size)))
                            self.nodes[gnodes[ref.group]] = ("group", (c["path"], ref.group, n))
                        gparent = gnodes[ref.group]
                    parent = self.tree.insert(gparent, "end", text=ref.name, open=False,
                                              values=("", "", len(ref.streams), "~" + fmt_time(nsec),
                                                      fmt_size(ref.size)))
                    self.nodes[parent] = ("awc", ref)
                for s in ref.streams:
                    sid = self.tree.insert(parent, "end", text=self._stream_text(s),
                                           values=("%08X" % s.id, s.codec_name,
                                                   tr("stereo") if s.opus_channels == 2 else tr("mono"),
                                                   "~" + fmt_time(s.est_seconds), ""))
                    self.nodes[sid] = ("stream", (ref, s))
                    n_str += 1
                    if ref.cues:
                        links = self.cue_info.setdefault(ref.key, [])
                        for k, (t, val) in enumerate(ref.cues):
                            cand = tids.get((ref.group, val))
                            if len(links) <= k:
                                links.append((cand["name"], cand["streams"][0]["id"]) if cand else None)
                            cid2 = self.tree.insert(sid, "end", text="", values=("", "", "", fmt_time(t), ""))
                            self.nodes[cid2] = ("cue", (ref, s, k))
                            self.tree.item(cid2, text=self._cue_text(ref, k))
        self._update_count()
        self._refresh_names()
        if not res:
            self.set_status("st_scan_none", n=len(files))
        else:
            self.set_status("st_scan_done", c=len(res), a=n_awc, s=n_str)
            if icons.installed() and icons.missing_stations() and self._icons_pools():
                self._icons_start(quiet=True)

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
        self.lbl_icon.pack_forget()
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

    def _context_menu(self, e):
        iid = self.tree.identify_row(e.y)
        kind = self.nodes.get(iid, (None,))[0] if iid else None
        if kind == "stream":
            self.tree.selection_set(iid)
            s = self.nodes[iid][1][1]
            m = tk.Menu(self, tearoff=0)
            m.add_command(label=tr("ctx_set_track"), command=lambda: self.name_stream(iid))
            if self.names.get(s.id):
                m.add_command(label=tr("ctx_clear_track"), command=lambda: self.name_stream(iid, clear=True))
            m.tk_popup(e.x_root, e.y_root)
            return
        if kind != "group":
            return
        self.tree.selection_set(iid)
        m = tk.Menu(self, tearoff=0)
        m.add_command(label=tr("ctx_rename"), command=lambda: self.rename_group(iid))
        if self._gname(self.nodes[iid][1][0], self.nodes[iid][1][1]):
            m.add_command(label=tr("ctx_clear_name"), command=lambda: self.rename_group(iid, clear=True))
        m.tk_popup(e.x_root, e.y_root)

    def rename_group(self, iid, clear=False):
        cpath, g, _n = self.nodes[iid][1]
        if clear:
            name = ""
        else:
            guess = self._group_station_name(cpath, g)
            choices = ([guess] if guess else []) + [x for x in self.stations if x != guess]
            name = self._ask_name(tr("dlg_rename_title"), tr("dlg_rename_prompt", g=g),
                                  self._gname(cpath, g) or guess, choices)
            if name is None:
                return
        d = self.group_names.setdefault(self._gkey(cpath), {})
        if g in d.get("~auto", []):
            d["~auto"].remove(g)          # a name chosen by the user is never replaced automatically
        if name.strip():
            d[str(g)] = name.strip()
        else:
            d.pop(str(g), None)
        save_group_names(self.group_names)
        self.tree.item(iid, text=self._group_text(cpath, g))

    def _ask_name(self, title, prompt, initial, choices):
        """Ask for a text; with `choices` the entry is an editable drop-down. Returns None when cancelled."""
        if not choices:
            return simpledialog.askstring(title, prompt, initialvalue=initial, parent=self)
        dlg = tk.Toplevel(self)
        dlg.title(title)
        dlg.transient(self)
        dlg.resizable(False, False)
        ttk.Label(dlg, text=prompt, justify="left").pack(padx=12, pady=(12, 6), anchor="w")
        var = tk.StringVar(value=initial)
        cb = ttk.Combobox(dlg, textvariable=var, values=list(choices), width=42)
        cb.pack(padx=12, fill="x")
        res = []

        def ok(_e=None):
            res.append(var.get())
            dlg.destroy()

        row = ttk.Frame(dlg)
        row.pack(pady=12)
        ttk.Button(row, text=tr("btn_ok"), command=ok).pack(side="left", padx=4)
        ttk.Button(row, text=tr("btn_cancel"), command=dlg.destroy).pack(side="left", padx=4)
        dlg.bind("<Return>", ok)
        dlg.bind("<Escape>", lambda _e: dlg.destroy())
        cb.focus_set()
        dlg.grab_set()
        self.wait_window(dlg)
        return res[0] if res else None

    # ------------------------------------------------------------------ station logos
    def _maybe_icon_prompt(self):
        """First start: offer to download the texture decoder (once; 'Not now' asks again next time)."""
        if icons.installed() or awc_i18n.get_setting("icons_prompt") == "never":
            return
        self._icon_dialog(first=True)

    def _icon_dialog(self, first=False):
        dlg = tk.Toplevel(self)
        dlg.title(tr("dlg_icons_title"))
        dlg.transient(self)
        dlg.resizable(False, False)
        ttk.Label(dlg, text=tr("dlg_icons_text", folder=icons.decoder_dir()), wraplength=480, justify="left"
                  ).pack(padx=16, pady=(14, 8))
        row = ttk.Frame(dlg)
        row.pack(fill="x", padx=16, pady=(0, 14))

        def close(choice):
            if choice == "never" and first:
                awc_i18n.set_setting("icons_prompt", "never")
            dlg.destroy()
            if choice == "go":
                self._icons_start()

        ttk.Button(row, text=tr("btn_icons_download"), command=lambda: close("go")).pack(side="left")
        ttk.Button(row, text=tr("btn_icons_later"), command=lambda: close("later")).pack(side="left", padx=6)
        if first:
            ttk.Button(row, text=tr("btn_icons_never"), command=lambda: close("never")).pack(side="left")
        dlg.protocol("WM_DELETE_WINDOW", lambda: close("later"))
        dlg.update_idletasks()
        x = self.winfo_rootx() + max(0, (self.winfo_width() - dlg.winfo_width()) // 2)
        y = self.winfo_rooty() + 80
        dlg.geometry("+%d+%d" % (x, y))
        dlg.grab_set()

    def icons_button(self):
        if self.busy:
            messagebox.showinfo(tr("dlg_busy_title"), tr("dlg_busy"))
        elif not icons.installed():
            self._icon_dialog()
        else:
            self._icons_start(report=True)

    def _icons_pools(self):
        pools = {}
        for ref in self.refs.values():
            if ref.in_rpf and ref.path not in pools:
                pools[ref.path] = icons.find_pool(ref.path)
        return [p for p in pools.values() if p]

    def _icons_start(self, report=False, quiet=False):
        """Download the decoder if needed, then cut the logos out of the game's texture pool (background thread)."""
        if self.icons_busy:
            return
        self.icons_busy = True
        if not quiet:
            self.set_status("st_icons_downloading" if not icons.installed() else "st_icons_extracting")
        threading.Thread(target=self._icons_worker, args=(self._icons_pools(), report), daemon=True).start()

    def _icons_worker(self, pools, report):
        n, err = 0, ""
        try:
            if not icons.installed():
                icons.download()
            for p in pools:
                if icons.missing_stations():
                    n += icons.extract(p)
        except ImportError:
            err = tr("dlg_icons_pillow")
        except Exception as e:  # noqa: BLE001
            err = str(e)
        self.q.put(("icons", n, err, report, bool(pools)))

    def _icons_done(self, n, err, report, had_pool):
        self.icons_busy = False
        if n or report:
            self.set_status("st_icons_done", n=n)
        if err:
            messagebox.showerror(tr("dlg_icons_title"), tr("dlg_icons_error", e=err))
        elif report or n:
            if not had_pool and not n:
                messagebox.showinfo(tr("dlg_icons_title"), tr("dlg_icons_nopool"))
            else:
                total = len(icons.STATION_KEYS)
                messagebox.showinfo(tr("dlg_icons_title"), tr("dlg_icons_result", n=n,
                                                              t=total - len(icons.missing_stations()), all=total))
        if self.cur:
            self._set_icon(*self.cur)

    def _set_icon(self, ref, s):
        path = icons.icon_path(self._station(ref, s)) if ref.in_rpf else ""
        if not path:
            self.lbl_icon.pack_forget()
            return
        try:
            img = tk.PhotoImage(file=path)
            self._icon_img = img.subsample(2, 2)
        except tk.TclError:
            self.lbl_icon.pack_forget()
            return
        self.lbl_icon.config(image=self._icon_img)
        self.lbl_icon.pack(side="left", padx=(0, 8), before=self.lbl_now)

    # ------------------------------------------------------------------ credits page
    def read_credits(self):
        if self.busy:
            messagebox.showinfo(tr("dlg_busy_title"), tr("dlg_busy"))
            return
        ids = {st.id for _r, st in self._all_streams()}
        if not ids:
            messagebox.showinfo(tr("dlg_names_title"), tr("dlg_credits_none"))
            return
        self._set_busy(True)
        self.set_status("st_reading_credits")
        threading.Thread(target=self._credits_worker, args=(ids, None), daemon=True).start()

    def _credits_worker(self, ids, path):
        try:
            if path:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read()
            else:
                text = credits.download()
        except (OSError, ValueError) as e:
            self.q.put(("credits_fail", ids, str(e), path is not None))
            return
        songs = credits.parse(text)
        if not songs:
            self.q.put(("credits_fail", ids, "no songs found in the page", True))
            return
        self.q.put(("credits", credits.match(songs, ids), len(songs)))

    def _credits_failed(self, ids, err, from_file):
        self._set_busy(False)
        self.set_status("st_ready")
        if from_file:
            messagebox.showerror(tr("dlg_names_title"), tr("dlg_credits_error", e=err))
            return
        if not messagebox.askyesno(tr("dlg_names_title"), tr("dlg_credits_fail", e=err)):
            return
        path = filedialog.askopenfilename(title=tr("dlg_credits_pick"),
                                          filetypes=[("HTML", "*.html *.htm"), ("*", "*.*")])
        if path:
            self._set_busy(True)
            self.set_status("st_reading_credits")
            threading.Thread(target=self._credits_worker, args=(ids, path), daemon=True).start()

    def _apply_credits(self, found, n_songs):
        k = kept = 0
        stations = set()
        for sid, (title, artist, station) in found.items():
            cur = self.names.get(sid)
            if cur and (cur.get("artist") or cur.get("station")):
                kept += 1
                continue
            self.names.set(sid, title, artist, station)
            stations.add(station)
            k += 1
        self.names.save()
        if stations:
            self.stations = sorted(set(self.stations) | stations)
            names.save_stations(self.stations)
        self._runs = None
        self._refresh_names()
        gn = self._auto_name_groups()
        self.set_status("st_credits_read", k=k)
        total = len(self._all_streams())
        unnamed = sum(1 for _r, st in self._all_streams() if not self.names.get(st.id))
        messagebox.showinfo(tr("dlg_names_title"), tr("dlg_credits_result", k=k, kept=kept, s=n_songs,
                                                      m=len(found), st=len(stations), gn=gn, n=unnamed, t=total))

    def _station_of(self, sid):
        st = (self.names.get(sid) or {}).get("station")
        return st if st and credits.is_radio(st) else ""

    def _station_runs(self):
        """{archive path: [(base offset, station)]} for the AWCs whose tracks carry a station, in archive order.
        The stations lie in the archive one after the other, so an unnamed AWC between two AWCs of the same station
        belongs to that station."""
        if self._runs is None:
            runs = {}
            for ref in self.refs.values():
                if not ref.in_rpf:
                    continue
                count = {}
                for s in ref.streams:
                    st = self._station_of(s.id)
                    if st:
                        count[st] = count.get(st, 0) + 1
                if count:
                    runs.setdefault(ref.path, []).append((ref.base, max(count, key=count.get)))
            for v in runs.values():
                v.sort()
            self._runs = runs
        return self._runs

    def _inferred_station(self, ref):
        run = self._station_runs().get(ref.path) if ref.in_rpf else None
        if not run:
            return ""
        i = bisect.bisect_left([b for b, _s in run], ref.base)
        before = run[i - 1][1] if i > 0 else None
        after = run[i][1] if i < len(run) else None
        return before if before and before == after else ""

    def _group_station_name(self, cpath, g):
        """Station name(s) of a group from its tracks, in archive order ('A + B' if the group spans two)."""
        count, first = {}, {}
        for ref in self.refs.values():
            if ref.in_rpf and ref.path == cpath and ref.group == g:
                for s in ref.streams:
                    st = self._station_of(s.id)
                    if st:
                        count[st] = count.get(st, 0) + 1
                        first[st] = min(first.get(st, ref.base), ref.base)
        if not count:
            return ""
        total = sum(count.values())
        keep = [st for st, n in count.items() if n >= 2 and n >= 0.2 * total] or [max(count, key=count.get)]
        return " + ".join(sorted(keep, key=first.get))

    def _auto_name_groups(self):
        """Name every group after the station(s) of its tracks, unless the user named it. Returns how many."""
        done = 0
        seen = sorted({(ref.path, ref.group) for ref in self.refs.values() if ref.in_rpf and ref.group})
        for cpath, g in seen:
            d = self.group_names.setdefault(self._gkey(cpath), {})
            auto = d.setdefault("~auto", [])
            cur = d.get(str(g), "")
            if cur and g not in auto:
                continue
            name = self._group_station_name(cpath, g)
            if name:
                if name != cur:
                    done += 1
                d[str(g)] = name
                if g not in auto:
                    auto.append(g)
        save_group_names(self.group_names)
        for iid, (kind, obj) in self.nodes.items():
            if kind == "group":
                self.tree.item(iid, text=self._group_text(obj[0], obj[1]))
        return done

    # ------------------------------------------------------------------ names
    def read_game_names(self):
        if self.busy:
            messagebox.showinfo(tr("dlg_busy_title"), tr("dlg_busy"))
            return
        by_path = {}
        for ref in self.refs.values():
            if ref.in_rpf:
                by_path.setdefault(ref.path, set()).update(s.id for s in ref.streams)
        if not by_path:
            messagebox.showinfo(tr("dlg_names_title"), tr("dlg_gamenames_none"))
            return
        self._set_busy(True)
        self.set_status("st_reading_names")
        threading.Thread(target=self._gamenames_worker, args=(by_path,), daemon=True).start()

    def _gamenames_worker(self, by_path):
        found, stations, failed = {}, set(), []
        for path, ids in by_path.items():
            try:
                n, st, _cnt = gamecfg.read_names(path, ids)
                found.update(n)
                stations.update(st)
            except (OSError, ValueError) as e:
                failed.append("%s (%s)" % (path, e))
        self.q.put(("gamenames", found, sorted(stations), failed))

    def _apply_game_names(self, found, stations, failed):
        k = kept = 0
        for sid, title in found.items():
            if self.names.get(sid):
                kept += 1
            else:
                self.names.set(sid, title)
                k += 1
        self.names.save()
        if stations:
            self.stations = sorted(set(self.stations) | set(stations))
            names.save_stations(self.stations)
        self._refresh_names()
        self.set_status("st_names_read", k=k)
        streams = self._all_streams()
        total = len(streams)
        missing = sum(1 for _r, st in streams if st.id not in found)
        if failed:
            messagebox.showwarning(tr("dlg_names_title"), tr("dlg_gamenames_error", paths="\n".join(failed)))
        messagebox.showinfo(tr("dlg_names_title"), tr("dlg_gamenames_result", k=k, kept=kept, st=len(stations),
                                                      n=missing, t=total))

    def _refresh_names(self):
        self._runs = None
        known = 0
        for iid, (kind, obj) in self.nodes.items():
            if kind == "stream":
                self.tree.item(iid, text=self._stream_text(obj[1]))
                known += 1 if self.names.get(obj[1].id) else 0
            elif kind == "cue":
                self.tree.item(iid, text=self._cue_text(obj[0], obj[2]))
        total = len(self._all_streams())
        self.lbl_named.config(text=tr("lbl_named", k=known, n=total) if total else "")
        if self.cur:
            self.lbl_now.config(text="%s  –  %s" % (self.cur[0].name, self._stream_text(self.cur[1])))

    def name_stream(self, iid, clear=False):
        s = self.nodes[iid][1][1]
        if clear:
            self.names.set(s.id)
        else:
            rec = self.names.get(s.id) or {}
            cur = "%s - %s" % (rec["artist"], rec["title"]) if rec.get("artist") else rec.get("title", "")
            txt = simpledialog.askstring(tr("dlg_track_title"), tr("dlg_track_prompt", id="%08X" % s.id),
                                         initialvalue=cur, parent=self)
            if txt is None:
                return
            a, t = names.split_artist_title(txt)
            self.names.set(s.id, t, a, rec.get("station", ""))
        self.names.save()
        self._refresh_names()

    def import_names(self):
        path = filedialog.askopenfilename(
            title=tr("dlg_names_import"),
            filetypes=[(tr("ft_names"), "*.csv *.tsv *.txt *.json"), (tr("ft_all"), "*.*")])
        if not path:
            return
        known = [s.id for _r, s in self._all_streams()]
        try:
            ok, skipped = self.names.import_file(path, known)
        except (OSError, ValueError) as e:
            messagebox.showerror(tr("err_title"), str(e))
            return
        self._refresh_names()
        messagebox.showinfo(tr("dlg_names_title"), tr("dlg_names_result", ok=ok, skipped=skipped))

    def export_template(self):
        streams = self._all_streams()
        if not streams:
            messagebox.showinfo(tr("dlg_nothing_title"), tr("dlg_names_noscan"))
            return
        path = filedialog.asksaveasfilename(title=tr("dlg_names_template"), defaultextension=".csv",
                                            initialfile="track_names.csv", filetypes=[("CSV", "*.csv")])
        if not path:
            return
        rows = [(s.id, ref.name, self._group_text(ref.path, ref.group) if ref.in_rpf and ref.group else "",
                 self._gname(ref.path, ref.group) if ref.in_rpf and ref.group else "")
                for ref, s in streams]
        try:
            names.NameStore.write_template(path, rows, self.names)
        except OSError as e:
            messagebox.showerror(tr("err_title"), str(e))
            return
        messagebox.showinfo(tr("dlg_names_title"), tr("dlg_names_template_done", n=len(rows), path=path))

    def clear_names(self):
        if self.names.tracks and messagebox.askyesno(tr("dlg_names_title"), tr("dlg_names_clear_q")):
            self.names.clear()
            self.names.save()
            self._refresh_names()

    def _dbl(self, e):
        iid = self.tree.identify_row(e.y)
        kind = self.nodes.get(iid, (None,))[0] if iid else None
        if kind == "stream":
            self._load_and_play(*self.nodes[iid][1], start=0.0)
        elif kind == "cue":
            ref, s, k = self.nodes[iid][1]
            self._load_and_play(ref, s, start=ref.cues[k][0])

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
        split = self.split_mixes.get()
        if (ff_args or split) and not self.ffmpeg:
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
        threading.Thread(target=self._extract_worker, args=(items, out, ext, ff_args, self.write_playlist.get(), split),
                         daemon=True).start()

    def _extract_worker(self, items, out, ext, ff_args, playlist=True, split=False):
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
            used, lists = set(), {}        # file names handed out per folder / playlist entries per folder
            for gi, k in enumerate(order):
                ref, streams = groups[k]
                w = max(1, ref.size) / float(total)
                folder = os.path.join(out, safe_name(ref.container), self._group_dir(ref)) if ref.in_rpf else out
                os.makedirs(folder, exist_ok=True)
                jobs, final, tags, labels = {}, {}, {}, {}
                for s in streams:
                    rec = self.names.get(s.id)
                    entries = lists.setdefault(folder, [])
                    if rec:
                        labels[s.index] = names.track_label(rec)
                        tags[s.index] = names.make_tags(rec, self._station(ref, s), len(entries) + 1)
                        stem = names.safe_filename(labels[s.index].replace(" – ", " - "))
                        n, name = 2, stem
                        while (folder, name.lower()) in used:
                            name = "%s (%d)" % (stem, n)
                            n += 1
                        used.add((folder, name.lower()))
                        name += "." + ext
                    else:
                        base = ref.name if ref.in_rpf else os.path.splitext(ref.name)[0]
                        name = "%s_stream%d_%08X.%s" % (safe_name(base), s.index, s.id, ext)
                    entries.append((name, s.est_seconds, labels.get(s.index, "")))
                    final[s.index] = os.path.join(folder, name)
                    jobs[s.index] = final[s.index] if ext == "opus" else os.path.join(
                        self.cache_dir, "x_%d_%d_%d.opus" % (os.getpid(), gi, s.index))
                self.q.put(("statuskey", "st_group", {"i": gi + 1, "n": len(order), "name": ref.name,
                                                      "k": len(streams)}))
                share = w if ext == "opus" else w * 0.5
                need = dict(jobs)
                for i in list(need):   # already in the playback cache?
                    c = self.cache.get((ref.key, i))
                    if c and os.path.isfile(c[0]) and not (ext == "opus" and i in tags):
                        if ext == "opus":
                            shutil.copyfile(c[0], final[i])
                        else:
                            jobs[i] = c[0]
                        del need[i]
                if need:
                    ref.extract(need, progress=lambda f, d=done, sh=share: self.q.put(("progress", d + f * sh)),
                                cancel=lambda: self.cancel_flag, tags=tags)
                if ext != "opus":
                    for s in streams:
                        self._ffmpeg_convert(jobs[s.index], final[s.index],
                                             ff_args + names.ffmpeg_meta_args(tags.get(s.index)), s.est_seconds,
                                             done + share, w * 0.5 / len(streams))
                        if os.path.basename(jobs[s.index]).startswith("x_"):
                            try:
                                os.remove(jobs[s.index])
                            except OSError:
                                pass
                if split and ref.cues:
                    for s in streams:
                        self._split_mix(final[s.index], ref, s)
                made += len(streams)
                done += w
                self.q.put(("progress", done))
            if playlist:
                for folder, entries in lists.items():
                    names.write_m3u8(folder, entries)
            self.q.put(("done", "st_extract_done", {"n": made, "out": out}))
        except InterruptedError:
            self.q.put(("done", "st_cancelled", {}))
        except Exception as e:  # noqa: BLE001
            self.q.put(("error", str(e)))

    def _split_mix(self, path, ref, s):
        """Cut an exported station mix at its track markers (stream copy, no re-encode)."""
        stem, ext = os.path.splitext(path)
        folder = stem + " (tracks)"
        os.makedirs(folder, exist_ok=True)
        n = len(ref.cues)
        for k, (t, _val) in enumerate(ref.cues):
            if self.cancel_flag:
                raise InterruptedError()
            label = self._cue_label(ref, k)
            dst = os.path.join(folder, "%02d %s%s" % (k + 1, names.safe_filename(label), ext))
            cmd = [self.ffmpeg, "-y", "-v", "error", "-ss", "%.3f" % t, "-i", path]
            if k + 1 < n:
                cmd += ["-t", "%.3f" % max(0.1, ref.cues[k + 1][0] - t)]
            cmd += ["-c", "copy", "-metadata", "title=" + label, "-metadata", "track=%d/%d" % (k + 1, n), dst]
            r = subprocess.run(cmd, capture_output=True, text=True, creationflags=NO_WINDOW)
            if r.returncode != 0:
                raise RuntimeError(tr("err_ffmpeg", e=r.stderr[-800:]))

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
        self.lbl_now.config(text="%s  –  %s" % (ref.name, self._stream_text(s)))
        self._set_icon(ref, s)
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
                elif kind == "gamenames":
                    self._set_busy(False)
                    self._apply_game_names(msg[1], msg[2], msg[3])
                elif kind == "credits":
                    self._set_busy(False)
                    self._apply_credits(msg[1], msg[2])
                elif kind == "credits_fail":
                    self._credits_failed(msg[1], msg[2], msg[3])
                elif kind == "icons":
                    self._icons_done(msg[1], msg[2], msg[3], msg[4])
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
