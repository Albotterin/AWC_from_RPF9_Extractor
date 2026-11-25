package org.awctool.radio;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The playback logic of one station, free of any Android classes so that it can be tested on a PC with a fake clock and
 * fake players (see android/test/EngineTest.java).
 *
 * Every tick it looks at the programme (Schedule) for "now": items that are about to start are opened ahead of time
 * (PRELOAD), started when their time comes at the position the station has reached by then, and faded in and out with an
 * equal-power crossfade where the programme overlaps two songs (else one follows the other). A player that fails is opened again a few times; a clock jump or a dropped audio focus is
 * handled with resync().
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

        void release();
    }

    interface Factory {
        Player open(Schedule.Track track);        // starts preparing right away; never throws
    }

    static final double PRELOAD = 25.0;           // open items this many seconds before they start
    static final double START_COMPENSATION = 0.08;  // start() needs a moment, start that much early
    static final int MAX_TRIES = 3;
    static final double TAIL = 0.25;              // an item that is not faded out may end this much after its time

    private static final class Slot {
        final Schedule.Item item;
        Player player;
        boolean started, dead;
        int tries = 1;
        double retryAt;

        Slot(Schedule.Item item) {
            this.item = item;
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

    Engine(Schedule schedule, Clock clock, Factory factory) {
        this.schedule = schedule;
        this.clock = clock;
        this.factory = factory;
    }

    void setDuck(float d) {
        duck = d;
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

    private void kill(Slot s) {
        s.dead = true;
        if (s.player != null) {
            try {
                s.player.release();
            } catch (RuntimeException ignored) {
            }
            s.player = null;
        }
    }

    private Player open(Schedule.Item it) {
        try {
            return factory.open(it.track);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Equal-power crossfade: the next item fades in while the previous one fades out. */
    static float volume(Schedule.Item it, double now, float duck) {
        double v = 1.0;
        double t = now - it.start;
        if (it.fadeIn > 0 && t < it.fadeIn) v *= Math.sin(Math.PI / 2 * Math.max(0, t) / it.fadeIn);
        double tt = now - (it.end - it.fadeOut);
        if (it.fadeOut > 0 && tt > 0) v *= Math.cos(Math.PI / 2 * Math.min(1.0, tt / it.fadeOut));
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
                Slot s = new Slot(it);
                s.player = open(it);
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
            double grace = it.fadeOut > 0 ? 0.1 : it.end - it.start > it.track.sec - 0.01 ? TAIL : 0.0;
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
                    s.player = open(it);
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
            if (!s.started) {
                if (now + 2.0 >= it.start) busy = true;
                // start() needs a moment: a little early, so that one item follows the other without a gap
                if (p.isPrepared() && now + START_COMPENSATION >= it.start) {
                    p.startAt(Math.max(0.0, now + START_COMPENSATION - it.start), volume(it, now, duck));
                    s.started = true;
                }
            } else {
                p.setVolume(volume(it, now, duck));
                if (now - it.start < it.fadeIn + 0.2 || now > it.end - it.fadeOut - 0.2) busy = true;
            }
            if (s.started && (cur == null || it.start > cur.item.start)) cur = s;
        }
        if (cur != null && cur.item != current) {
            current = cur.item;
            changed = true;
        }
        return busy ? 50 : 250;
    }
}
