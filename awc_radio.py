"""AWC Radio - a small web-radio style player for the game's stations.

Pick the game's audio.rpf and its texture pool once; the app finds the stations (names come from the public credits
list, see awc_credits.py), shows them with their logos and plays their songs one after the other, with a short
jingle after every 2-4 songs. Switching stations drops you into the middle of a song, like tuning a real radio.

It uses the same scan cache as awc_tool.py and needs ffplay (next to the program or in PATH).
"""
import json
import os
import queue
import random
import subprocess
import sys
import threading
import time
import tkinter as tk
import zlib
from tkinter import filedialog, messagebox, ttk

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import awc_config as gamecfg  # noqa: E402
import awc_core as core  # noqa: E402
import awc_credits as credits  # noqa: E402
import awc_i18n  # noqa: E402
import awc_icons as icons  # noqa: E402

NO_WINDOW = 0x08000000 if os.name == "nt" else 0
SONG_MIN, SONG_MAX = 60.0, 1500.0          # seconds: shorter is not a song, longer is a mix
JINGLE_MIN, JINGLE_MAX = 2.0, 25.0         # unnamed short streams of a station are used as jingles
MIN_SONGS = 5                              # a station needs at least this many songs to be listed
INDEX_VERSION = 1

BG, PANEL, CARD, CARD_SEL, FG, DIM, ACCENT = "#14161c", "#1c1f27", "#232733", "#2f3550", "#eceef4", "#8b91a3", "#ff5a36"

STR = {
    "en": {
        "title": "AWC Radio", "setup_title": "Choose your game files",
        "setup_intro": "Select the game's audio.rpf and its texture file (data\\ttdb\\ui\\ttdb_ui.ttpf). The first start reads the "
                       "archive once; later starts are instant.",
        "lbl_rpf": "audio.rpf", "lbl_pool": "Texture file (ttdb_ui.ttpf)", "btn_browse": "Browse…", "btn_start": "Start radio",
        "btn_settings": "Files…", "btn_rescan": "Re-read", "st_scan": "Reading the archive … (the first time takes several minutes)",
        "st_credits": "Loading the song list …", "st_names": "Reading names from the game config …",
        "st_build": "Building the stations …", "st_icons": "Reading the station logos …", "st_ready": "{n} stations",
        "err_rpf": "Please choose an existing audio.rpf.", "err_scan": "No music streams found in that file.",
        "err_ffplay": "ffplay was not found. Put ffplay.exe next to the program or into your PATH.",
        "err_credits": "The credits page could not be downloaded:\n{e}\n\nPick the page saved from your browser instead?",
        "err_html": "HTML page", "err_generic": "Something went wrong:\n{e}", "err_nostations": "No station with songs was found.",
        "ask_decoder": "Station logos need the Leonida texture decoder (a few files from GitHub, GPL-3; they are saved to\n{folder}\nand are not part of this program). Download it now?",
        "kind_song": "SONG", "kind_jingle": "JINGLE", "btn_next": "Next ⏭", "btn_play": "▶ Play", "btn_pause": "⏸ Pause",
        "chk_mid": "Join songs in the middle", "lbl_volume": "Volume", "n_songs": "{n} songs", "st_loading": "Loading …",
        "lbl_next": "Up next:", "tune": "Tune in to a station on the left", "no_logo_hint": "",
    },
    "de": {
        "title": "AWC Radio", "setup_title": "Spieldateien wählen",
        "setup_intro": "Wähle die audio.rpf des Spiels und seine Textur-Datei (data\\ttdb\\ui\\ttdb_ui.ttpf). Beim ersten Start wird "
                       "das Archiv einmal eingelesen; spätere Starts gehen sofort.",
        "lbl_rpf": "audio.rpf", "lbl_pool": "Textur-Datei (ttdb_ui.ttpf)", "btn_browse": "Durchsuchen…", "btn_start": "Radio starten",
        "btn_settings": "Dateien…", "btn_rescan": "Neu einlesen", "st_scan": "Lese das Archiv … (beim ersten Mal dauert das mehrere Minuten)",
        "st_credits": "Lade die Songliste …", "st_names": "Lese Namen aus der Spiel-Konfiguration …",
        "st_build": "Baue die Sender …", "st_icons": "Lese die Sender-Logos …", "st_ready": "{n} Sender",
        "err_rpf": "Bitte eine vorhandene audio.rpf wählen.", "err_scan": "In dieser Datei wurden keine Musik-Streams gefunden.",
        "err_ffplay": "ffplay wurde nicht gefunden. ffplay.exe neben das Programm legen oder in den PATH aufnehmen.",
        "err_credits": "Die Credits-Seite konnte nicht geladen werden:\n{e}\n\nStattdessen die im Browser gespeicherte Seite wählen?",
        "err_html": "HTML-Seite", "err_generic": "Es ist ein Fehler aufgetreten:\n{e}", "err_nostations": "Es wurde kein Sender mit Songs gefunden.",
        "ask_decoder": "Für die Sender-Logos wird der Leonida-Texturdecoder gebraucht (ein paar Dateien von GitHub, GPL-3; sie werden nach\n{folder}\ngespeichert und sind nicht Teil dieses Programms). Jetzt herunterladen?",
        "kind_song": "SONG", "kind_jingle": "JINGLE", "btn_next": "Weiter ⏭", "btn_play": "▶ Abspielen", "btn_pause": "⏸ Pause",
        "chk_mid": "Mitten im Song einsteigen", "lbl_volume": "Lautstärke", "n_songs": "{n} Songs", "st_loading": "Lade …",
        "lbl_next": "Als Nächstes:", "tune": "Links einen Sender wählen", "no_logo_hint": "",
    },
}


def t(key, **kw):
    lang = awc_i18n.get_lang()
    s = STR.get(lang, STR["en"]).get(key) or STR["en"].get(key, key)
    return s.format(**kw) if kw else s


# ------------------------------------------------------------------------------------------------ files
def appdata():
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")
    os.makedirs(d, exist_ok=True)
    return d


def settings_path():
    return os.path.join(appdata(), "radio.json")


def load_settings():
    try:
        with open(settings_path(), "r", encoding="utf-8") as f:
            d = json.load(f)
        return d if isinstance(d, dict) else {}
    except (OSError, ValueError):
        return {}


def save_settings(d):
    try:
        with open(settings_path(), "w", encoding="utf-8") as f:
            json.dump(d, f, indent=1)
    except OSError:
        pass


def index_path():
    return os.path.join(appdata(), "radio_index.json")


def credits_cache_path():
    return os.path.join(appdata(), "radio_credits.json")


def cache_dir():
    d = os.path.join(appdata(), "radio_cache")
    os.makedirs(d, exist_ok=True)
    return d


def find_tool(name):
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
    from shutil import which
    return which(name)


def fingerprint(rpf, pool):
    st = os.stat(rpf)
    return {"v": INDEX_VERSION, "rpf": os.path.abspath(rpf).lower(), "size": st.st_size, "mtime": int(st.st_mtime),
            "pool": os.path.abspath(pool).lower() if pool else ""}


def load_index(rpf, pool):
    try:
        with open(index_path(), "r", encoding="utf-8") as f:
            idx = json.load(f)
        if idx.get("fp") == fingerprint(rpf, pool) and idx.get("stations"):
            return idx
    except (OSError, ValueError, KeyError):
        pass
    return None


# ------------------------------------------------------------------------------------------------ model
def sort_key(name):
    n = name.lower()
    return n[4:] if n.startswith("the ") else n


def build_stations(items, found, cfg_names):
    """-> {station: {"songs": [track], "jingles": [track]}}.

    items      scan items (dicts with "base", "streams", optional "cues")
    found      {stream id: (title, artist, station)} from the credits list
    cfg_names  {stream id: title} from the game config (no artist, no station)

    A track is {"base", "idx", "sid", "title", "artist", "sec"}. The stations lie in the archive one after the
    other: an AWC without a station inherits it when the nearest AWCs before and after it agree."""
    items = sorted(items, key=lambda x: x["base"])
    runs, group_votes = [], {}
    for it in items:
        votes = {}
        for s in it["streams"]:
            hit = found.get(s["id"])
            if hit and credits.is_radio(hit[2]):
                votes[hit[2]] = votes.get(hit[2], 0) + 1
        if votes:
            st = max(votes, key=votes.get)
            runs.append((it["base"], st))
            gv = group_votes.setdefault(it.get("g", 0), {})
            gv[st] = gv.get(st, 0) + 1
    bases = [b for b, _s in runs]
    # a group (cluster of AWCs in the archive) that is almost entirely one station belongs to that station
    group_station = {}
    for g, gv in group_votes.items():
        total = sum(gv.values())
        top = max(gv, key=gv.get)
        if g and total >= 3 and gv[top] >= 0.85 * total:
            group_station[g] = top

    def inferred(it):
        import bisect
        i = bisect.bisect_left(bases, it["base"])
        before = runs[i - 1][1] if i > 0 else None
        after = runs[i][1] if i < len(runs) else None
        if before and before == after:
            return before
        return group_station.get(it.get("g", 0))

    out = {}

    def add(station, kind, it, s, title="", artist=""):
        out.setdefault(station, {"songs": [], "jingles": []})[kind].append(
            {"base": it["base"], "idx": s["i"], "sid": s["id"], "title": title, "artist": artist, "sec": s["sec"]})

    for it in items:
        if it.get("cues"):
            continue                                   # a whole station mix, not a single track
        st_inf = None
        for s in it["streams"]:
            sec, sid = s["sec"], s["id"]
            hit = found.get(sid)
            if hit and credits.is_radio(hit[2]):
                if SONG_MIN <= sec <= SONG_MAX:
                    add(hit[2], "songs", it, s, hit[0], hit[1])
                continue
            if sid in found or not (JINGLE_MIN <= sec <= JINGLE_MAX or (sid in cfg_names and SONG_MIN <= sec <= SONG_MAX)):
                continue
            if st_inf is None:
                st_inf = inferred(it) or ""
            if not st_inf:
                continue
            if sid in cfg_names:
                add(st_inf, "songs", it, s, cfg_names[sid], "")
            elif JINGLE_MIN <= sec <= JINGLE_MAX:
                add(st_inf, "jingles", it, s)
    return {k: v for k, v in sorted(out.items(), key=lambda kv: sort_key(kv[0])) if len(v["songs"]) >= MIN_SONGS}


class Programme:
    """What a station plays: shuffled songs, one jingle after every 2-4 songs."""

    def __init__(self, station):
        self.station = station
        self.songs = list(station["songs"])
        self.jingles = list(station["jingles"])
        self.deck = []
        self.last_jingle = None
        self.count = 0
        self.target = random.randint(2, 4)

    def _song(self):
        if not self.deck:
            self.deck = self.songs[:]
            random.shuffle(self.deck)
        return self.deck.pop()

    def next(self):
        if self.jingles and self.count >= self.target:
            self.count, self.target = 0, random.randint(2, 4)
            pool = [j for j in self.jingles if j is not self.last_jingle] or self.jingles
            self.last_jingle = random.choice(pool)
            return "jingle", self.last_jingle
        self.count += 1
        return "song", self._song()


# ------------------------------------------------------------------------------------------------ app
class NeedCredits(Exception):
    pass


def fmt_time(sec):
    sec = max(0, int(sec))
    return "%d:%02d" % (sec // 60, sec % 60)


class RadioApp(tk.Tk):
    def __init__(self):
        super().__init__()
        awc_i18n.load_saved_lang()
        self.title(t("title"))
        self.geometry("1020x640")
        self.minsize(820, 520)
        self.configure(bg=BG)
        self.q = queue.Queue()
        self.cfg = load_settings()
        self.ffplay = find_tool("ffplay")
        self.index = None
        self.stations = {}
        self.cards = {}
        self.logo_refs = []
        self.station = None
        self.prog = None
        self.upcoming = []              # [(kind, track)] already chosen
        self.cur = None                 # (kind, track)
        self.proc = None
        self.state = "idle"             # idle | loading | playing | paused
        self.pos = 0.0
        self.t0 = 0.0
        self.first_after_tune = False
        self.fail_count = 0
        self.want = queue.Queue()       # tracks to extract
        self.ready = {}                 # key -> path
        self.pending_html = None
        self._style()
        self._build()
        threading.Thread(target=self._prepare_loop, daemon=True).start()
        self.protocol("WM_DELETE_WINDOW", self._close)
        self.after(100, self._poll)
        self.after(250, self._tick)
        self.after(50, self._startup)

    # -------------------------------------------------------------------------------- ui
    def _style(self):
        s = ttk.Style(self)
        try:
            s.theme_use("clam")
        except tk.TclError:
            pass
        s.configure(".", background=BG, foreground=FG, fieldbackground=PANEL, borderwidth=0)
        s.configure("TLabel", background=BG, foreground=FG)
        s.configure("Dim.TLabel", foreground=DIM)
        s.configure("TCheckbutton", background=BG, foreground=FG)
        s.map("TCheckbutton", background=[("active", BG)])
        s.configure("TButton", background=CARD, foreground=FG, padding=(12, 6), borderwidth=0)
        s.map("TButton", background=[("active", CARD_SEL), ("disabled", PANEL)])
        s.configure("Accent.TButton", background=ACCENT, foreground="white", padding=(18, 8), font=("Segoe UI", 11, "bold"))
        s.map("Accent.TButton", background=[("active", "#ff7a58")])
        s.configure("TEntry", fieldbackground=PANEL, foreground=FG, insertcolor=FG)
        s.configure("Horizontal.TProgressbar", background=ACCENT, troughcolor=PANEL)
        s.configure("Horizontal.TScale", background=BG, troughcolor=PANEL)
        s.configure("Vertical.TScrollbar", background=CARD, troughcolor=BG, arrowcolor=DIM, bordercolor=BG, lightcolor=CARD, darkcolor=CARD)
        s.map("Vertical.TScrollbar", background=[("active", CARD_SEL)])

    def _build(self):
        self.setup = tk.Frame(self, bg=BG)
        self.radio = tk.Frame(self, bg=BG)
        self._build_setup()
        self._build_radio()

    def _build_setup(self):
        f = tk.Frame(self.setup, bg=BG)
        f.place(relx=0.5, rely=0.45, anchor="center")
        tk.Label(f, text=t("title"), bg=BG, fg=ACCENT, font=("Segoe UI", 28, "bold")).pack(anchor="w")
        tk.Label(f, text=t("setup_title"), bg=BG, fg=FG, font=("Segoe UI", 14)).pack(anchor="w", pady=(0, 6))
        tk.Label(f, text=t("setup_intro"), bg=BG, fg=DIM, wraplength=620, justify="left").pack(anchor="w", pady=(0, 14))
        self.var_rpf = tk.StringVar(value=self.cfg.get("rpf", ""))
        self.var_pool = tk.StringVar(value=self.cfg.get("pool", ""))
        for label, var, kind in ((t("lbl_rpf"), self.var_rpf, "rpf"), (t("lbl_pool"), self.var_pool, "pool")):
            ttk.Label(f, text=label, style="Dim.TLabel").pack(anchor="w")
            row = tk.Frame(f, bg=BG)
            row.pack(fill="x", pady=(0, 10))
            ttk.Entry(row, textvariable=var, width=70).pack(side="left", fill="x", expand=True)
            ttk.Button(row, text=t("btn_browse"), command=lambda v=var, k=kind: self._browse(v, k)).pack(side="left", padx=(8, 0))
        self.btn_start = ttk.Button(f, text=t("btn_start"), style="Accent.TButton", command=self._start_clicked)
        self.btn_start.pack(anchor="w", pady=(6, 10))
        self.setup_bar = ttk.Progressbar(f, maximum=1.0, length=620)
        self.setup_bar.pack(anchor="w")
        self.setup_status = tk.Label(f, text="", bg=BG, fg=DIM, wraplength=620, justify="left")
        self.setup_status.pack(anchor="w", pady=(6, 0))

    def _build_radio(self):
        top = tk.Frame(self.radio, bg=PANEL)
        top.pack(fill="x")
        tk.Label(top, text=t("title"), bg=PANEL, fg=ACCENT, font=("Segoe UI", 15, "bold")).pack(side="left", padx=14, pady=8)
        ttk.Button(top, text=t("btn_settings"), command=self._show_setup).pack(side="right", padx=8, pady=6)
        ttk.Button(top, text=t("btn_rescan"), command=lambda: self._start_clicked(force=True)).pack(side="right")
        body = tk.Frame(self.radio, bg=BG)
        body.pack(fill="both", expand=True)
        left = tk.Frame(body, bg=BG, width=330)
        left.pack(side="left", fill="y")
        left.pack_propagate(False)
        self.canvas = tk.Canvas(left, bg=BG, highlightthickness=0)
        sb = ttk.Scrollbar(left, orient="vertical", command=self.canvas.yview)
        self.canvas.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.canvas.pack(side="left", fill="both", expand=True)
        self.list_frame = tk.Frame(self.canvas, bg=BG)
        self.canvas.create_window((0, 0), window=self.list_frame, anchor="nw", tags="lf")
        self.list_frame.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure("lf", width=e.width))
        self.canvas.bind_all("<MouseWheel>", lambda e: self.canvas.yview_scroll(int(-e.delta / 120) * 2, "units"))

        right = tk.Frame(body, bg=BG)
        right.pack(side="left", fill="both", expand=True, padx=(10, 20), pady=16)
        self.big = tk.Canvas(right, width=150, height=150, bg=BG, highlightthickness=0)
        self.big.pack(anchor="w")
        self.lbl_station = tk.Label(right, text=t("tune"), bg=BG, fg=FG, font=("Segoe UI", 22, "bold"), anchor="w")
        self.lbl_station.pack(fill="x", pady=(10, 0))
        self.lbl_kind = tk.Label(right, text="", bg=BG, fg=ACCENT, font=("Segoe UI", 9, "bold"), anchor="w")
        self.lbl_kind.pack(fill="x", pady=(14, 0))
        self.lbl_title = tk.Label(right, text="", bg=BG, fg=FG, font=("Segoe UI", 17), anchor="w", wraplength=520, justify="left")
        self.lbl_title.pack(fill="x")
        self.lbl_artist = tk.Label(right, text="", bg=BG, fg=DIM, font=("Segoe UI", 12), anchor="w")
        self.lbl_artist.pack(fill="x")
        bar = tk.Frame(right, bg=BG)
        bar.pack(fill="x", pady=(16, 4))
        self.lbl_pos = tk.Label(bar, text="0:00", bg=BG, fg=DIM, width=5)
        self.lbl_pos.pack(side="left")
        self.bar = ttk.Progressbar(bar, maximum=1.0)
        self.bar.pack(side="left", fill="x", expand=True, padx=6)
        self.lbl_len = tk.Label(bar, text="0:00", bg=BG, fg=DIM, width=5)
        self.lbl_len.pack(side="left")
        ctl = tk.Frame(right, bg=BG)
        ctl.pack(fill="x", pady=8)
        self.btn_play = ttk.Button(ctl, text=t("btn_pause"), command=self._toggle)
        self.btn_play.pack(side="left")
        ttk.Button(ctl, text=t("btn_next"), command=self._skip).pack(side="left", padx=8)
        ttk.Label(ctl, text=t("lbl_volume"), style="Dim.TLabel").pack(side="left", padx=(20, 6))
        self.vol = ttk.Scale(ctl, from_=0, to=100, orient="horizontal", length=140)
        self.vol.set(self.cfg.get("vol", 80))
        self.vol.pack(side="left")
        self.vol.bind("<ButtonRelease-1>", self._vol_changed)
        self.var_mid = tk.BooleanVar(value=self.cfg.get("mid", True))
        ttk.Checkbutton(right, text=t("chk_mid"), variable=self.var_mid, command=self._save_prefs).pack(anchor="w", pady=(4, 0))
        self.lbl_next = tk.Label(right, text="", bg=BG, fg=DIM, anchor="w")
        self.lbl_next.pack(fill="x", pady=(18, 0))
        self.status = tk.Label(self.radio, text="", bg=PANEL, fg=DIM, anchor="w")
        self.status.pack(fill="x", side="bottom")

    def _show_setup(self):
        self._stop()
        self.radio.pack_forget()
        self.setup.pack(fill="both", expand=True)

    def _show_radio(self):
        self.setup.pack_forget()
        self.radio.pack(fill="both", expand=True)

    # -------------------------------------------------------------------------------- setup flow
    def _browse(self, var, kind):
        init = os.path.dirname(var.get()) if var.get() else ""
        if kind == "rpf":
            p = filedialog.askopenfilename(title=t("lbl_rpf"), initialdir=init or None, filetypes=[("RPF", "*.rpf"), ("*", "*.*")])
        else:
            p = filedialog.askopenfilename(title=t("lbl_pool"), initialdir=init or None, filetypes=[("TTPF", "*.ttpf"), ("*", "*.*")])
        if p:
            var.set(os.path.normpath(p))
            if kind == "rpf" and not self.var_pool.get().strip():
                guess = icons.find_pool(p)
                if guess:
                    self.var_pool.set(guess)

    def _startup(self):
        if not self.ffplay:
            messagebox.showwarning(t("title"), t("err_ffplay"))
        rpf, pool = self.cfg.get("rpf", ""), self.cfg.get("pool", "")
        if rpf and os.path.isfile(rpf):
            idx = load_index(rpf, pool)
            if idx:
                self._open_index(idx, pool, background_icons=True)
                return
        self.setup.pack(fill="both", expand=True)

    def _start_clicked(self, force=False):
        rpf, pool = self.var_rpf.get().strip(), self.var_pool.get().strip()
        if force and self.radio.winfo_ismapped():
            rpf, pool = self.cfg.get("rpf", rpf), self.cfg.get("pool", pool)
            self._show_setup()
        if not os.path.isfile(rpf):
            messagebox.showerror(t("title"), t("err_rpf"))
            return
        if pool and not os.path.isfile(pool):
            pool = ""
        self.cfg.update({"rpf": rpf, "pool": pool})
        save_settings(self.cfg)
        if not force:
            idx = load_index(rpf, pool)
            if idx:
                self._open_index(idx, pool, background_icons=True)
                return
        allow_dl = False
        if pool and not icons.installed():
            allow_dl = messagebox.askyesno(t("title"), t("ask_decoder", folder=icons.decoder_dir()))
        self.btn_start.config(state="disabled")
        self._setup_status(t("st_scan"), 0)
        threading.Thread(target=self._setup_worker, args=(rpf, pool, allow_dl, self.pending_html), daemon=True).start()

    def _setup_status(self, text, frac=None):
        self.setup_status.config(text=text)
        if frac is not None:
            self.setup_bar["value"] = frac

    def _load_credits(self, html_path):
        if html_path:
            with open(html_path, "r", encoding="utf-8", errors="replace") as f:
                songs = credits.parse(f.read())
        elif os.path.isfile(credits_cache_path()):
            with open(credits_cache_path(), "r", encoding="utf-8") as f:
                return json.load(f)
        else:
            try:
                songs = credits.parse(credits.download())
            except (OSError, ValueError) as e:
                raise NeedCredits(str(e)) from e
        if not songs:
            raise NeedCredits("no songs found in the page")
        with open(credits_cache_path(), "w", encoding="utf-8") as f:
            json.dump(songs, f, ensure_ascii=False)
        return songs

    def _setup_worker(self, rpf, pool, allow_dl, html_path):
        q = self.q
        try:
            res = core.scan_files([rpf], progress=lambda f, n, nt: q.put(("setup_prog", f * 0.7)))
            items = [it for c in res for it in c["items"]]
            if not items:
                q.put(("setup_fail", t("err_scan")))
                return
            q.put(("setup_status", t("st_credits"), 0.72))
            songs = self._load_credits(html_path)
            ids = {s["id"] for it in items for s in it["streams"]}
            found = credits.match(songs, ids)
            q.put(("setup_status", t("st_names"), 0.8))
            try:
                cfg_names = gamecfg.read_names(rpf, ids)[0]
            except (OSError, ValueError):
                cfg_names = {}
            q.put(("setup_status", t("st_build"), 0.88))
            stations = build_stations(items, found, cfg_names)
            if not stations:
                q.put(("setup_fail", t("err_nostations")))
                return
            used = {}
            for st in stations.values():
                for tr_ in st["songs"] + st["jingles"]:
                    used[tr_["base"]] = None
            by_base = {it["base"]: it for it in items}
            idx = {"fp": fingerprint(rpf, pool), "stations": stations,
                   "items": {str(b): by_base[b] for b in used}}
            with open(index_path(), "w", encoding="utf-8") as f:
                json.dump(idx, f)
            q.put(("setup_status", t("st_icons"), 0.94))
            self._make_icons(pool, allow_dl)
            q.put(("setup_done", idx, pool))
        except NeedCredits as e:
            q.put(("need_credits", str(e)))
        except InterruptedError:
            return
        except Exception as e:  # noqa: BLE001
            q.put(("setup_fail", t("err_generic", e=e)))

    @staticmethod
    def _make_icons(pool, allow_dl):
        """Download the decoder (if allowed) and cut the logos out of the texture pool; failures are not fatal."""
        try:
            if not pool or not os.path.isfile(pool):
                return
            if not icons.installed():
                if not allow_dl:
                    return
                icons.download()
            if icons.missing_stations():
                icons.extract(pool)
        except Exception:  # noqa: BLE001
            pass

    def _open_index(self, idx, pool, background_icons=False):
        self.index = idx
        self.stations = idx["stations"]
        self._fill_stations()
        self._show_radio()
        self.status.config(text=t("st_ready", n=len(self.stations)))
        if background_icons and pool and os.path.isfile(pool) and icons.installed() and icons.missing_stations():
            def work():
                self._make_icons(pool, False)
                self.q.put(("icons_ready",))
            threading.Thread(target=work, daemon=True).start()

    # -------------------------------------------------------------------------------- station list
    def _logo(self, station, size_div):
        p = icons.icon_path(station)
        if not p:
            return None
        try:
            img = tk.PhotoImage(file=p)
            img = img.subsample(size_div, size_div) if size_div > 1 else img
            self.logo_refs.append(img)
            return img
        except tk.TclError:
            return None

    @staticmethod
    def _color(name):
        h = zlib.crc32(name.encode("utf-8"))
        return "#%02x%02x%02x" % (60 + (h & 0x7F), 60 + ((h >> 8) & 0x7F), 80 + ((h >> 16) & 0x7F))

    def _draw_logo(self, cv, station, size, div):
        cv.delete("all")
        img = self._logo(station, div)
        if img:
            cv.create_image(size // 2, size // 2, image=img)
        else:
            cv.create_rectangle(0, 0, size, size, fill=self._color(station), outline="")
            initials = "".join(w[0] for w in station.replace("-", " ").split()[:3]).upper()
            cv.create_text(size // 2, size // 2, text=initials, fill="white", font=("Segoe UI", max(9, size // 4), "bold"))

    def _fill_stations(self):
        for w in self.list_frame.winfo_children():
            w.destroy()
        self.cards.clear()
        self.logo_refs.clear()
        for name, st in self.stations.items():
            card = tk.Frame(self.list_frame, bg=CARD, cursor="hand2")
            card.pack(fill="x", padx=(10, 6), pady=3)
            cv = tk.Canvas(card, width=44, height=44, bg=CARD, highlightthickness=0)
            cv.pack(side="left", padx=8, pady=6)
            self._draw_logo(cv, name, 44, 3)
            box = tk.Frame(card, bg=CARD)
            box.pack(side="left", fill="x", expand=True)
            lab = tk.Label(box, text=name, bg=CARD, fg=FG, font=("Segoe UI", 11, "bold"), anchor="w")
            lab.pack(fill="x")
            sub = tk.Label(box, text=t("n_songs", n=len(st["songs"])), bg=CARD, fg=DIM, anchor="w")
            sub.pack(fill="x")
            for w in (card, cv, box, lab, sub):
                w.bind("<Button-1>", lambda e, n=name: self._tune(n))
            self.cards[name] = (card, cv, box, lab, sub)
        self._mark_selected()

    def _mark_selected(self):
        for name, widgets in self.cards.items():
            col = CARD_SEL if name == self.station else CARD
            for w in widgets:
                w.config(bg=col)

    def _refresh_logos(self):
        self.logo_refs.clear()
        for name, widgets in self.cards.items():
            self._draw_logo(widgets[1], name, 44, 3)
        if self.station:
            self._draw_logo(self.big, self.station, 150, 1)

    # -------------------------------------------------------------------------------- playback
    def _save_prefs(self):
        self.cfg["vol"] = int(self.vol.get())
        self.cfg["mid"] = bool(self.var_mid.get())
        save_settings(self.cfg)

    def _tune(self, name):
        if not self.ffplay:
            messagebox.showerror(t("title"), t("err_ffplay"))
            return
        self._stop()
        self.station = name
        with self.want.mutex:               # drop work for the previous station
            self.want.queue.clear()
        self.prog = Programme(self.stations[name])
        self.upcoming = []
        self.fail_count = 0
        self.first_after_tune = True
        self._mark_selected()
        self._draw_logo(self.big, name, 150, 1)
        self.lbl_station.config(text=name)
        self._advance()

    def _track_key(self, tr_):
        return "%s_%s" % (tr_["base"], tr_["idx"])

    def _fill_upcoming(self):
        while len(self.upcoming) < 2 and self.prog:
            self.upcoming.append(self.prog.next())
        for _kind, tr_ in self.upcoming:
            if self._track_key(tr_) not in self.ready:
                self.want.put(tr_)
        self._show_next()

    def _show_next(self):
        if self.upcoming:
            kind, tr_ = self.upcoming[0]
            label = t("kind_jingle") if kind == "jingle" else "%s - %s" % (tr_["artist"], tr_["title"]) if tr_["artist"] else tr_["title"]
            self.lbl_next.config(text="%s %s" % (t("lbl_next"), label))

    def _advance(self):
        """Move on to the next item of the programme (waits until its file is ready)."""
        self._kill()
        self._fill_upcoming()
        if not self.upcoming:
            return
        self.cur = self.upcoming.pop(0)
        self._fill_upcoming()
        kind, tr_ = self.cur
        self.lbl_kind.config(text=t("kind_jingle") if kind == "jingle" else t("kind_song"))
        self.lbl_title.config(text=("♪ " + t("kind_jingle")) if kind == "jingle" else tr_["title"])
        self.lbl_artist.config(text=tr_["artist"] if kind == "song" else self.station or "")
        self.lbl_len.config(text=fmt_time(tr_["sec"]))
        self.pos = 0.0
        if self.first_after_tune and kind == "song" and self.var_mid.get():
            self.pos = random.uniform(0.1, 0.55) * tr_["sec"]
        self.first_after_tune = False
        self.state = "loading"
        self.status.config(text=t("st_loading"))

    def _start_proc(self):
        kind, tr_ = self.cur
        path = self.ready.get(self._track_key(tr_))
        if not path or not os.path.isfile(path):
            return False
        cmd = [self.ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", str(int(self.vol.get()))]
        if self.pos > 0.5:
            cmd += ["-ss", "%.2f" % self.pos]
        cmd.append(path)
        try:
            self.proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                         stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
        except OSError as e:
            messagebox.showerror(t("title"), t("err_generic", e=e))
            self.state = "idle"
            return False
        self.t0 = time.time() - self.pos
        self.state = "playing"
        self.btn_play.config(text=t("btn_pause"))
        self.status.config(text=t("st_ready", n=len(self.stations)))
        return True

    def _kill(self):
        if self.proc and self.proc.poll() is None:
            try:
                self.proc.kill()
                self.proc.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                pass
        self.proc = None

    def _stop(self):
        self._kill()
        self.state = "idle"
        self.cur = None
        self.prog = None
        self.upcoming = []
        self.bar["value"] = 0

    def _toggle(self):
        if self.state == "playing":
            self.pos = time.time() - self.t0
            self._kill()
            self.state = "paused"
            self.btn_play.config(text=t("btn_play"))
        elif self.state == "paused":
            self._start_proc()
        elif self.state == "idle" and self.station:
            self._tune(self.station)

    def _skip(self):
        if self.cur and self.prog:
            self._advance()

    def _vol_changed(self, _e=None):
        self._save_prefs()
        if self.state == "playing":           # ffplay cannot change its volume from outside: restart at this position
            self.pos = time.time() - self.t0
            self._kill()
            self._start_proc()

    def _prepare_loop(self):
        """Background thread: cut the tracks out of the archive into small Opus files that ffplay can play."""
        while True:
            tr_ = self.want.get()
            key = self._track_key(tr_)
            if key in self.ready:
                continue
            dst = os.path.join(cache_dir(), "r_%s.opus" % key)
            try:
                if not (os.path.isfile(dst) and os.path.getsize(dst) > 0):
                    item = self.index["items"][str(tr_["base"])]
                    ref = core.AwcRef(dict(item, path=self.cfg["rpf"]))
                    ref.extract({tr_["idx"]: dst + ".part"})
                    os.replace(dst + ".part", dst)
                self.ready[key] = dst
            except Exception:  # noqa: BLE001
                self.ready[key] = ""

    def _tick(self):
        try:
            if self.state == "loading" and self.cur:
                key = self._track_key(self.cur[1])
                if key in self.ready:
                    if self.ready[key]:
                        self._start_proc()
                    else:                         # extraction failed: skip this one
                        self._count_failure()
            elif self.state == "playing" and self.cur:
                pos = time.time() - self.t0
                sec = max(1.0, self.cur[1]["sec"])
                self.bar["value"] = min(1.0, pos / sec)
                self.lbl_pos.config(text=fmt_time(pos))
                if self.proc is not None and self.proc.poll() is not None:
                    if pos < min(3.0, sec * 0.5) and self.pos < 1:       # ended right away: probably broken
                        self._count_failure()
                    else:
                        self.fail_count = 0
                        self._advance()
        finally:
            self.after(250, self._tick)

    def _count_failure(self):
        self.fail_count += 1
        if self.fail_count >= 4:
            self._stop()
            self.status.config(text=t("err_generic", e="playback failed"))
            return
        self._advance()

    # -------------------------------------------------------------------------------- messages
    def _poll(self):
        try:
            while True:
                msg = self.q.get_nowait()
                kind = msg[0]
                if kind == "setup_prog":
                    self._setup_status(t("st_scan"), msg[1])
                elif kind == "setup_status":
                    self._setup_status(msg[1], msg[2])
                elif kind == "setup_fail":
                    self.btn_start.config(state="normal")
                    self._setup_status("", 0)
                    messagebox.showerror(t("title"), msg[1])
                elif kind == "need_credits":
                    self.btn_start.config(state="normal")
                    self._setup_status("", 0)
                    if messagebox.askyesno(t("title"), t("err_credits", e=msg[1])):
                        p = filedialog.askopenfilename(title=t("err_html"), filetypes=[("HTML", "*.html *.htm"), ("*", "*.*")])
                        if p:
                            self.pending_html = p
                            self._start_clicked()
                elif kind == "setup_done":
                    self.btn_start.config(state="normal")
                    self.pending_html = None
                    self._setup_status("", 0)
                    self._open_index(msg[1], msg[2])
                elif kind == "icons_ready":
                    self._refresh_logos()
        except queue.Empty:
            pass
        self.after(100, self._poll)

    def _close(self):
        self._kill()
        self._save_prefs()
        self.destroy()


def main():
    RadioApp().mainloop()


if __name__ == "__main__":
    main()
