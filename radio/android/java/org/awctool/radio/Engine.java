package org.awctool.radio;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The playback logic of one station, free of any Android classes so that it can be tested on a PC with a fake clock and
 * fake players (see android/test/EngineTest.java).
 *
 * Every tick it looks at the programme (Schedule) for "now": items that are about to start are opened ahead of time
 * (PRELOAD), started when their time comes at the position the station has reached by then, and faded in and out as the
 * programme says (two items may overlap). A player that fails is opened again a few times; a clock jump or a dropped
 * audio focus is handled with resync().
 *
 * With the DJ switched on (setDj) a second player says his lines over a song (Schedule.voices) while the song steps back.
 * Under talk of the DJ that has a music bed (Schedule.bedOf) another player repeats that music quietly.
 */
final class Engine {
    interface Clock {
        double now();                             // UNIX seconds
    }

    /** One song/jingle being prepared or played. Implemented by PackPlayer (MediaPlayer) and by the test's fake. */
    interface Player {
        boolean isPrepared();

        boolean isFailed();                       // an error happened (decoder, file, ...)

        boolean isFinished();                     // played to its end

        void startAt(double posSec, float volume);   // seek if needed, then start; may complete asynchronously

        void setVolume(float volume);

        void loop();                              // start again at the end, for a music bed

        void release();
    }

    interface Factory {
        Player open(Schedule.Track track);        // starts preparing right away; never throws
    }

    static final double PRELOAD = 25.0;           // open items this many seconds before they start
    static final double START_COMPENSATION = 0.08;  // start() needs a moment, start that much early
    static final int MAX_TRIES = 3;
    static final double TAIL = 0.25;              // an item that is not faded out may end this much after its time
    static final float DUCK = 0.25f;              // how loud the music stays while the DJ talks, and how loud a music bed
                                                  // is under his talk (awc_dj.py: the same)
    static final double RAMP = 0.35;              // seconds the music takes to step back and forward again
    static final double VOICE_PRELOAD = 10.0;     // open a line this many seconds before it is said

    /** One line of the DJ over an item. */
    private static final class Line {
        final Schedule.Voice voice;
        Player player;
        boolean started, over;

        Line(Schedule.Voice voice) {
            this.voice = voice;
        }
    }

    private static final class Slot {
        final Schedule.Item item;
        Player player;
        boolean started, dead;
        int tries = 1;
        double retryAt;
        List<Line> lines;                         // what the DJ says over it; null: not looked up
        final Schedule.Track bed;                 // the music under it, or null
        Player under;
        boolean underStarted, underOver;

        Slot(Schedule.Item item, Schedule.Track bed) {
            this.item = item;
            this.bed = bed;
        }
    }

    private final Schedule schedule;
    private final Clock clock;
    private final Factory factory;
    private final List<Slot> slots = new ArrayList<>();
    private float duck = 1f;
    private Schedule.Item current;
    private boolean changed;
    private int failures;
    private boolean dj;

    Engine(Schedule schedule, Clock clock, Factory factory) {
        this.schedule = schedule;
        this.clock = clock;
        this.factory = factory;
    }

    void setDuck(float d) {
        duck = d;
    }

    /** The DJ's intros and outros on or off, at any time. */
    void setDj(boolean on) {
        dj = on;
        if (!on) for (Slot s : slots) silence(s);
    }

    /** The programme this engine plays. */
    Schedule schedule() {
        return schedule;
    }

    /** The item that is on air now (the one that started last), or null while nothing has started yet. */
    Schedule.Item current() {
        return current;
    }

    /** True once after the current item changed. */
    boolean takeChanged() {
        boolean c = changed;
        changed = false;
        return c;
    }

    /** The item after {@code it} in the programme. */
    Schedule.Item itemAfter(Schedule.Item it) {
        for (Schedule.Item x : schedule.itemsFrom(it.start + 0.001, 3)) {
            if (x.start > it.start + 1e-6) return x;
        }
        return null;
    }

    int failures() {
        return failures;
    }

    /** Number of the DJ's players that exist right now (for tests and diagnostics). */
    int openLines() {
        int n = 0;
        for (Slot s : slots) if (s.lines != null) for (Line l : s.lines) if (l.player != null) n++;
        return n;
    }

    /** Number of players of music beds that exist right now (for tests and diagnostics). */
    int openBeds() {
        int n = 0;
        for (Slot s : slots) if (s.under != null) n++;
        return n;
    }

    /** Number of players that exist right now (for tests and diagnostics). */
    int openPlayers() {
        int n = 0;
        for (Slot s : slots) if (s.player != null) n++;
        return n;
    }

    /** Drop everything and start again from "now" (clock jump, audio focus regained, ...). */
    void resync() {
        releaseAll();
        current = null;
        changed = true;
    }

    void stop() {
        releaseAll();
        current = null;
    }

    private void releaseAll() {
        for (Slot s : slots) kill(s);
        slots.clear();
    }

    private Slot find(Schedule.Item it) {
        for (Slot s : slots) {
            if (s.item.track.key.equals(it.track.key) && Math.abs(s.item.start - it.start) < 1e-6) return s;
        }
        return null;
    }

    private static void release(Line l) {
        l.over = true;
        if (l.player != null) {
            try {
                l.player.release();
            } catch (RuntimeException ignored) {
            }
            l.player = null;
        }
    }

    private static void silence(Slot s) {
        if (s.lines != null) for (Line l : s.lines) release(l);
        s.lines = null;
    }

    /** Starts and ends the DJ's lines over an item; true while the volume is on the move. */
    private boolean talk(Slot s, double now) {
        if (!dj) return false;
        if (s.lines == null) {
            s.lines = new ArrayList<>();
            for (Schedule.Voice v : schedule.voices(s.item)) s.lines.add(new Line(v));
        }
        boolean busy = false;
        for (Line l : s.lines) {
            double from = s.item.start + l.voice.at, to = from + l.voice.line.sec;
            if (l.over || now < from - VOICE_PRELOAD) continue;
            if (!l.started && now > to - 1.0) {                       // too late to say it (tuned in just now)
                release(l);
                continue;
            }
            if (now > from - RAMP - 0.3 && now < to + RAMP + 0.3) busy = true;
            if (l.player == null) {
                try {
                    l.player = factory.open(l.voice.line);
                } catch (RuntimeException e) {
                    l.player = null;
                }
                if (l.player == null) {
                    release(l);
                    continue;
                }
            }
            if (l.player.isFailed()) {
                failures++;
                release(l);
            } else if (!l.started) {
                if (l.player.isPrepared() && s.started && now + START_COMPENSATION >= from) {
                    l.player.startAt(Math.max(0.0, now + START_COMPENSATION - from), duck);
                    l.started = true;
                }
            } else if (l.player.isFinished() || now > to + 0.5) {
                l.player.release();                                   // said: the music may still be stepping forward
                l.player = null;
                l.over = true;
            } else {
                l.player.setVolume(duck);
            }
        }
        return busy;
    }

    /** How loud the music of an item is at `now` because of the DJ: 1, or less while he talks over it. */
    private static float behindDj(Slot s, double now) {
        if (s.lines == null) return 1f;
        double most = 0;
        for (Line l : s.lines) {
            if (!l.started && (l.over || l.player == null || !l.player.isPrepared())) continue;    // nothing to hear
            double from = s.item.start + l.voice.at, to = from + l.voice.line.sec;
            double in = Math.min(1, Math.max(0, (now - (from - RAMP)) / RAMP));
            double out = Math.min(1, Math.max(0, (to + RAMP - now) / RAMP));
            most = Math.max(most, in * out);
        }
        return (float) (1 - (1 - DUCK) * most);
    }

    private static void releaseBed(Slot s) {
        if (s.under != null) {
            try {
                s.under.release();
            } catch (RuntimeException ignored) {
            }
            s.under = null;
        }
        s.underStarted = false;
    }

    /** How loud the music bed of an item is: quiet, faded with the item, and brought in and out with its talk. */
    static float bedVolume(Schedule.Item it, double now, float duck) {
        double t = now - it.start;
        double in = Math.min(1, Math.max(0, t / RAMP));
        double out = Math.min(1, Math.max(0, (it.track.sec - t) / RAMP));
        return (float) (DUCK * in * out) * volume(it, now, duck);
    }

    /** Starts the music bed of an item with it and keeps its volume; true while that volume is on the move. */
    private boolean underlay(Slot s, double now) {
        if (s.bed == null || s.underOver) return false;
        if (s.under == null) {
            s.under = open(s.bed);
            if (s.under == null) {
                s.underOver = true;                       // the talk goes on without it
                return false;
            }
        }
        if (s.under.isFailed()) {
            failures++;
            releaseBed(s);
            s.underOver = true;
            return false;
        }
        double t = now - s.item.start;
        if (!s.underStarted) {
            if (s.under.isPrepared() && s.started && now + START_COMPENSATION >= s.item.start) {
                s.under.loop();
                s.under.startAt(Math.max(0.0, now + START_COMPENSATION - s.item.start) % Math.max(1.0, s.bed.sec),
                        bedVolume(s.item, now, duck));
                s.underStarted = true;
            }
        } else {
            s.under.setVolume(bedVolume(s.item, now, duck));
        }
        return t < RAMP + 0.2 || t > s.item.track.sec - RAMP - 0.2;
    }

    private void kill(Slot s) {
        s.dead = true;
        silence(s);
        releaseBed(s);
        if (s.player != null) {
            try {
                s.player.release();
            } catch (RuntimeException ignored) {
            }
            s.player = null;
        }
    }

    private Player open(Schedule.Track track) {
        try {
            return factory.open(track);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The loudness of an item at `now`: as its fades say, times `duck`. */
    static float volume(Schedule.Item it, double now, float duck) {
        double v = 1.0;
        double t = now - it.start;
        Schedule.Fade in = it.fadeIn, out = it.fadeOut;
        if (in != null && t < in.at + in.len) v *= in.gain(Math.max(0, Math.min(1.0, (t - in.at) / in.len)));
        if (out != null && t > out.at) v *= out.gain(1.0 - Math.min(1.0, (t - out.at) / out.len));
        return (float) (v * duck);
    }

    /**
     * Does what is due at this moment.
     *
     * @return how many milliseconds to wait before the next tick: short while something fades or is about to start,
     * long otherwise (saves battery)
     */
    long tick() {
        double now = clock.now();
        boolean busy = false;
        for (Schedule.Item it : schedule.itemsFrom(now, 4)) {
            if (it.start - now > PRELOAD) break;
            if (now >= it.end) continue;
            if (find(it) == null) {
                Slot s = new Slot(it, schedule.bedOf(it));
                s.player = open(it.track);
                if (s.player == null) {
                    s.dead = true;
                    s.retryAt = now + 1.0;
                    failures++;
                }
                slots.add(s);
            }
        }
        Slot cur = null;
        Iterator<Slot> iter = slots.iterator();
        while (iter.hasNext()) {
            Slot s = iter.next();
            Schedule.Item it = s.item;
            // a faded item is silent at its end; one that is not faded gets a moment for its last words (a player starts
            // a little late), unless midnight UTC cuts it: then it ends exactly on time
            double grace = it.fadeOut != null ? 0.1 : it.end - it.start > it.track.sec - 0.01 ? TAIL : 0.0;
            if (now >= it.end + grace) {
                kill(s);
                iter.remove();
                continue;
            }
            if (s.dead) {                                    // failed: try again a few times while it is still worth it
                if (s.retryAt > 0 && now >= s.retryAt && s.tries < MAX_TRIES && now < it.end - 2.0) {
                    s.tries++;
                    s.started = false;
                    s.dead = false;
                    s.player = open(it.track);
                    if (s.player == null) {
                        s.dead = true;
                        s.retryAt = now + 1.0;
                        failures++;
                    }
                } else {
                    continue;
                }
            }
            Player p = s.player;
            if (p == null) continue;
            if (p.isFinished()) {                            // the file ended (maybe a moment before the nominal end)
                kill(s);
                s.retryAt = 0;
                continue;
            }
            if (p.isFailed()) {
                failures++;
                kill(s);
                s.retryAt = now + 1.0;
                continue;
            }
            if (talk(s, now)) busy = true;
            if (!s.started) {
                if (now + 2.0 >= it.start) busy = true;
                // start() needs a moment: a little early, so that one item follows the other without a gap
                if (p.isPrepared() && now + START_COMPENSATION >= it.start) {
                    p.startAt(Math.max(0.0, now + START_COMPENSATION - it.start), volume(it, now, duck) * behindDj(s, now));
                    s.started = true;
                }
            } else {
                p.setVolume(volume(it, now, duck) * behindDj(s, now));
                double in = it.fadeIn == null ? 0.0 : it.fadeIn.at + it.fadeIn.len;
                double goes = it.fadeOut == null ? it.end : it.start + it.fadeOut.at;
                if (now - it.start < in + 0.2 || now > goes - 0.2) busy = true;
            }
            if (underlay(s, now)) busy = true;
            if (s.started && (cur == null || it.start > cur.item.start)) cur = s;
        }
        if (cur != null && cur.item != current) {
            current = cur.item;
            changed = true;
        }
        return busy ? 50 : 250;
    }
}
