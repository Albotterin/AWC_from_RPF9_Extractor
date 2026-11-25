"""The podcasts in AWC Radio on the PC: unlike a station they wait for the listener. One episode after the other, with
pause, seeking, 15 s back and 30 s forward, and the place in every episode is remembered (like in the Android app, see
Episodes.java and Heard.java).

Listener is the logic (no window: tested with a stand-in player and clock), PodcastPanel the part of the window.
"""
import os
import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk

END_ZONE = 15.0                      # an episode left this close to its end counts as heard
SAVE_EVERY = 5.0
BACK, FORWARD = 15, 30


class Listener:
    """Where the listener is. memory: a dict that is kept in the settings; player(episode, position) -> an object with
    poll() and kill(), or None while the audio is not ready yet; clock() -> seconds."""

    def __init__(self, memory, player, clock=time.time):
        self.heard = memory.setdefault("heard", {})       # track key -> [place in seconds, heard to the end once]
        self.last = memory.setdefault("last", {})         # podcast -> episode played last
        self.player, self.clock = player, clock
        self.show, self.episodes, self.keys = "", [], []
        self.index = -1
        self.proc = None
        self.wanted = False                               # the listener wants to hear it (it may still be loading)
        self.failed = False
        self.pos, self.since, self.saved = 0.0, 0.0, 0.0

    # ---------------------------------------------------------------- what is kept
    def place(self, key):
        return float(self.heard.get(key, [0, False])[0])

    def done(self, key):
        return bool(self.heard.get(key, [0, False])[1])

    def _remember(self, key, position, finished):
        self.heard[key] = [0 if finished else round(position, 1), finished or self.done(key)]

    def resume_at(self, show, keys):
        """The episode to go on with: the one played last, else the next one not heard; -1: nothing to go on with."""
        last, n = self.last.get(show, -1), len(keys)
        if not 0 <= last < n:
            return -1
        for k in range(n):
            i = (last + k) % n
            if not self.done(keys[i]) or self.place(keys[i]) > 0:
                return i
        return -1

    # ---------------------------------------------------------------- playing
    @property
    def duration(self):
        return self.episodes[self.index]["sec"] if self.index >= 0 else 0.0

    def position(self):
        if self.index < 0:
            return 0.0
        p = self.pos + (self.clock() - self.since if self.proc is not None else 0.0)
        return max(0.0, min(self.duration, p))

    def open(self, show, episodes, keys, index, play=True):
        """Go to an episode, at the place where the listener left it."""
        self.leave()
        self.show, self.episodes, self.keys = show, episodes, keys
        self.index = max(0, min(len(episodes) - 1, index))
        key = keys[self.index]
        at = self.place(key)
        self.pos = at if 0 <= at <= self.duration - END_ZONE else 0.0
        self.saved, self.wanted, self.failed = self.pos, play, False
        self.last[show] = self.index

    def _kill(self):
        if self.proc is not None:
            if self.proc.poll() is None:
                self.proc.kill()
            self.proc = None

    def save(self):
        if self.index < 0 or self.failed:
            return
        p = self.position()
        self._remember(self.keys[self.index], p, p >= self.duration - END_ZONE)
        self.saved = p

    def pause(self):
        self.save()
        self.pos = self.position()
        self._kill()
        self.wanted = False

    def play(self):
        if self.index >= 0:
            self.failed = False
            self.wanted = True

    def seek(self, seconds):
        if self.index < 0:
            return
        self._kill()
        self.pos = max(0.0, min(self.duration - 1.0, seconds))
        self.saved, self.failed = self.pos, False
        self._remember(self.keys[self.index], self.pos, self.pos >= self.duration - END_ZONE)

    def jump(self, seconds):
        self.seek(self.position() + seconds)

    def restart(self):
        """Start the player anew where it is (another volume)."""
        if self.proc is not None:
            self.pos = self.position()
            self._kill()

    def step(self, delta):
        """The next or the previous episode; going back first returns to the start of this one."""
        if self.index < 0:
            return False
        if delta < 0 and (self.position() > 3 or self.index == 0):
            self.seek(0)
            return False
        to = self.index + delta
        if not 0 <= to < len(self.episodes):
            return False
        self.open(self.show, self.episodes, self.keys, to, self.wanted)
        return True

    def tick(self):
        """Starts the player when the audio is there and moves on at the end; True when another episode began."""
        if self.index < 0 or not self.wanted:
            return False
        if self.proc is None:
            proc = self.player(self.episodes[self.index], self.pos)
            if proc is False:                             # the audio cannot be had
                self.failed, self.wanted = True, False
            elif proc is not None:
                self.proc, self.since = proc, self.clock()
            return False
        if self.proc.poll() is not None:
            at = self.position()
            started = self.clock() - self.since
            self._kill()
            if at < self.duration - END_ZONE:             # ended long before its end: broken, or no sound device
                self.pos = at if started > 2.0 else self.pos
                self.failed, self.wanted = True, False
                return False
            self._remember(self.keys[self.index], 0, True)
            if self.index + 1 < len(self.episodes):
                self.open(self.show, self.episodes, self.keys, self.index + 1, True)
                return True
            self.pos, self.wanted = 0.0, False
            return False
        if abs(self.position() - self.saved) >= SAVE_EVERY:
            self.save()
        return False

    def leave(self):
        """Stop and note the place."""
        if self.index >= 0:
            self.pause()


class PodcastPanel(tk.Frame):
    """The right half of the window while a podcast is open. radio: the module awc_radio (colours, texts, tools)."""

    def __init__(self, app, radio, master):
        super().__init__(master, bg=radio.BG)
        self.app, self.radio = app, radio
        self.name = ""
        self.ready = {}                                   # track key -> file ("": could not be extracted)
        self.loading = set()
        self.listener = Listener(app.cfg.setdefault("podcast_memory", {}), self._spawn)
        self._saved_at = 0.0
        self._build()
        self.after(200, self._tick)

    def _build(self):
        radio, t = self.radio, self.radio.t
        bg, fg, dim = radio.BG, radio.FG, radio.DIM
        head = tk.Frame(self, bg=bg)
        head.pack(fill="x")
        self.cover = tk.Canvas(head, width=110, height=110, bg=bg, highlightthickness=0)
        self.cover.pack(side="left")
        box = tk.Frame(head, bg=bg)
        box.pack(side="left", fill="x", expand=True, padx=(14, 0))
        self.lbl_name = tk.Label(box, text="", bg=bg, fg=fg, font=("Segoe UI", 20, "bold"), anchor="w")
        self.lbl_name.pack(fill="x")
        self.lbl_summary = tk.Label(box, text="", bg=bg, fg=dim, anchor="w")
        self.lbl_summary.pack(fill="x")
        bottom = tk.Frame(self, bg=bg)                    # placed first: it stays visible in a small window
        bottom.pack(side="bottom", fill="x")
        self.lbl_now = tk.Label(bottom, text="", bg=bg, fg=fg, font=("Segoe UI", 12, "bold"), anchor="w")
        self.lbl_now.pack(fill="x", pady=(8, 0))
        bar = tk.Frame(bottom, bg=bg)
        bar.pack(fill="x", pady=(6, 4))
        self.lbl_pos = tk.Label(bar, text="0:00", bg=bg, fg=dim, width=6)
        self.lbl_pos.pack(side="left")
        self.bar = ttk.Progressbar(bar, maximum=1.0, cursor="hand2")
        self.bar.pack(side="left", fill="x", expand=True, padx=6)
        self.bar.bind("<Button-1>", self._bar_clicked)
        self.lbl_len = tk.Label(bar, text="0:00", bg=bg, fg=dim, width=6)
        self.lbl_len.pack(side="left")
        ctl = tk.Frame(bottom, bg=bg)
        ctl.pack(fill="x", pady=(2, 4))
        ttk.Button(ctl, text="⏮", width=3, command=lambda: self._do(self.listener.step, -1)).pack(side="left")
        ttk.Button(ctl, text=t("pod_back", n=BACK), command=lambda: self._do(self.listener.jump, -BACK)).pack(side="left", padx=4)
        self.btn_play = ttk.Button(ctl, text=t("btn_play"), command=self._toggle)
        self.btn_play.pack(side="left")
        ttk.Button(ctl, text=t("pod_forward", n=FORWARD), command=lambda: self._do(self.listener.jump, FORWARD)).pack(side="left", padx=4)
        ttk.Button(ctl, text="⏭", width=3, command=lambda: self._do(self.listener.step, 1)).pack(side="left")
        vol = tk.Frame(bottom, bg=bg)
        vol.pack(fill="x")
        ttk.Label(vol, text=t("lbl_volume"), style="Dim.TLabel").pack(side="left", padx=(0, 6))
        self.vol = ttk.Scale(vol, from_=0, to=100, orient="horizontal", length=140)
        self.vol.pack(side="left")
        self.vol.bind("<ButtonRelease-1>", self._volume)
        mid = tk.Frame(self, bg=bg)
        mid.pack(fill="both", expand=True, pady=(12, 0))
        self.list = tk.Listbox(mid, bg=radio.PANEL, fg=fg, selectbackground=radio.CARD_SEL, selectforeground=fg, borderwidth=0,
                               highlightthickness=0, activestyle="none", font=("Segoe UI", 11), exportselection=False)
        sb = ttk.Scrollbar(mid, orient="vertical", command=self.list.yview)
        self.list.configure(yscrollcommand=sb.set)
        sb.pack(side="right", fill="y")
        self.list.pack(side="left", fill="both", expand=True)
        self.list.bind("<ButtonRelease-1>", self._chosen)
        self.list.bind("<Return>", self._chosen)

    # ---------------------------------------------------------------- what is shown
    def _episodes(self, name):
        entry = self.app.index["podcasts"][name]["songs"]
        return entry, [self.radio._tkey(e) for e in entry]

    def open_show(self, name):
        """Show a podcast; what is playing goes on until another episode is chosen."""
        self.name = name
        episodes, keys = self._episodes(name)
        self.app._draw_logo(self.cover, name, 110)
        self.lbl_name.config(text=name)
        self.lbl_summary.config(text=self.radio.t("pod_summary", n=len(episodes), m=int(round(sum(e["sec"] for e in episodes) / 60))))
        self.vol.set(self.app.vol.get())
        if not self.listener.wanted and (self.listener.show != name or self.listener.index < 0):
            go = self.listener.resume_at(name, keys)
            self.listener.open(name, episodes, keys, max(0, go), play=False)
        self._refresh(full=True)

    def _line(self, i, episode, key):
        t, fmt = self.radio.t, self.radio.fmt_time
        listener = self.listener
        current = listener.show == self.name and listener.index == i
        at = listener.position() if current else listener.place(key)
        note = t("pod_resume", t=fmt(at)) if at >= 1 else t("pod_heard") if listener.done(key) else ""
        mark = "▶ " if current and listener.wanted else "    "
        return "%s%s      %s%s" % (mark, t("pod_episode", n=i + 1), fmt(episode["sec"]), "      " + note if note else "")

    def _refresh(self, full=False):
        if not self.name:
            return
        episodes, keys = self._episodes(self.name)
        listener = self.listener
        lines = [self._line(i, e, k) for i, (e, k) in enumerate(zip(episodes, keys))]
        if full or list(self.list.get(0, "end")) != lines:
            top = self.list.yview()[0]
            self.list.delete(0, "end")
            for line in lines:
                self.list.insert("end", line)
            self.list.yview_moveto(0 if full else top)
            if listener.show == self.name and listener.index >= 0:
                self.list.selection_set(listener.index)
        if listener.index >= 0:
            state = self.radio.t("pod_error") if listener.failed else self.radio.t("st_loading") \
                if listener.wanted and listener.proc is None else ""
            now = "%s – %s" % (listener.show, self.radio.t("pod_episode", n=listener.index + 1))
            self.lbl_now.config(text="%s   %s" % (now, state) if state else now)
            self.lbl_pos.config(text=self.radio.fmt_time(listener.position()))
            self.lbl_len.config(text=self.radio.fmt_time(listener.duration))
            self.bar["value"] = listener.position() / max(1.0, listener.duration)
        self.btn_play.config(text=self.radio.t("btn_pause" if listener.wanted else "btn_play"))

    # ---------------------------------------------------------------- controls
    def _do(self, action, *args):
        action(*args)
        self.listener.tick()
        self.app._save_prefs()
        self._refresh()

    def _chosen(self, _e=None):
        sel = self.list.curselection()
        if not sel or not self.name:
            return
        listener = self.listener
        if listener.show == self.name and listener.index == sel[0]:
            self._do(listener.play)
            return
        episodes, keys = self._episodes(self.name)
        self._do(listener.open, self.name, episodes, keys, sel[0])

    def _toggle(self):
        self._do(self.listener.pause if self.listener.wanted else self.listener.play)

    def _bar_clicked(self, e):
        self._do(self.listener.seek, self.listener.duration * e.x / max(1, self.bar.winfo_width()))

    def _volume(self, _e=None):
        self.app.vol.set(self.vol.get())
        self.app._save_prefs()
        self.listener.restart()                           # ffplay cannot change its volume from outside

    # ---------------------------------------------------------------- audio
    def _extract(self, episode, key, dst):
        try:
            if not (os.path.isfile(dst) and os.path.getsize(dst) > 0):
                self.radio.extract_track(self.app.index, self.app.cfg["rpf"], episode, dst + ".part")
                os.replace(dst + ".part", dst)
            self.ready[key] = dst
        except Exception:  # noqa: BLE001
            self.ready[key] = ""
        finally:
            self.loading.discard(key)

    def _spawn(self, episode, position):
        """The player of the Listener: None while the episode is being cut out of the archive, False if it cannot be."""
        key = self.radio._tkey(episode)
        path = self.ready.get(key)
        if path is None:
            if key not in self.loading:
                self.loading.add(key)
                dst = os.path.join(self.radio.cache_dir(), "r_%s.opus" % key)
                threading.Thread(target=self._extract, args=(episode, key, dst), daemon=True).start()
            return None
        if not path or not os.path.isfile(path):
            self.ready.pop(key, None)
            return False
        cmd = [self.app.ffplay, "-nodisp", "-autoexit", "-loglevel", "quiet", "-volume", str(int(self.app.vol.get()))]
        if position > 0.5:
            cmd += ["-ss", "%.2f" % position]
        try:
            return subprocess.Popen(cmd + [path], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                    stderr=subprocess.DEVNULL, creationflags=self.radio.NO_WINDOW)
        except OSError:
            return False

    def _tick(self):
        try:
            if self.listener.wanted:
                changed = self.listener.tick()
                if time.time() - self._saved_at >= SAVE_EVERY:
                    self._saved_at = time.time()
                    self.app._save_prefs()
                if self.winfo_ismapped() or changed:
                    self._refresh()
        finally:
            self.after(200, self._tick)

    def stop(self):
        """Pause, e.g. because a station is tuned in."""
        if self.listener.wanted:
            self.listener.pause()
            self.app._save_prefs()
            self._refresh()
