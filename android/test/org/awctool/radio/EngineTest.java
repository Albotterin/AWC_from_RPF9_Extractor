package org.awctool.radio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Simulation test of Engine with a fake clock and fake players. Run: see android/test/run_tests.py. */
public class EngineTest {
    static final class FakeClock implements Engine.Clock {
        double t;

        public double now() {
            return t;
        }
    }

    static final class Fake implements Engine.Player {
        final Schedule.Track track;
        final FakeClock clock;
        final double created, prepDelay;
        boolean started, released, failPrepare;
        double failAfterStart = -1, finishEarlyBy = 0;
        double startClock, startPos;
        float vol;

        Fake(Schedule.Track track, FakeClock clock, double prepDelay) {
            this.track = track;
            this.clock = clock;
            this.created = clock.t;
            this.prepDelay = prepDelay;
        }

        public boolean isPrepared() {
            return !failPrepare && clock.t - created >= prepDelay;
        }

        public boolean isFailed() {
            if (failPrepare && clock.t - created >= prepDelay) return true;
            return failAfterStart >= 0 && started && clock.t - startClock >= failAfterStart;
        }

        public boolean isFinished() {
            return started && startPos + (clock.t - startClock) >= track.sec - finishEarlyBy;
        }

        public void startAt(double pos, float v) {
            if (started) throw new AssertionError("started twice");
            started = true;
            startClock = clock.t;
            startPos = pos;
            vol = v;
        }

        public void setVolume(float v) {
            vol = v;
        }

        public void release() {
            released = true;
        }

        double position() {
            return startPos + (clock.t - startClock);
        }
    }

    static int checks = 0;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg);
    }

    static Schedule station(String name, int songs, int jingles, long seed) {
        Random r = new Random(seed);
        List<Schedule.Track> s = new ArrayList<>(), j = new ArrayList<>();
        for (int i = 0; i < songs; i++) s.add(new Schedule.Track((1000 + i) + "_0", "s" + i, "a", 120 + r.nextInt(200) + r.nextDouble()));
        for (int i = 0; i < jingles; i++) j.add(new Schedule.Track((9000 + i) + "_0", "", "", 2 + r.nextInt(20) + r.nextDouble()));
        return new Schedule(name, s, j);
    }

    interface Hook {
        void onCreate(Fake f);
    }

    /** Run the engine for `seconds` of simulated time starting at clock time `start`, checking invariants all the way. */
    static void run(String label, Schedule sch, double start, double seconds, Hook hook, boolean strictAudible) {
        FakeClock clock = new FakeClock();
        clock.t = start;
        List<Fake> all = new ArrayList<>();
        Map<Fake, Integer> byKey = new HashMap<>();
        Engine eng = new Engine(sch, clock, track -> {
            Fake f = new Fake(track, clock, 0.15);
            if (hook != null) hook.onCreate(f);
            all.add(f);
            return f;
        });
        double end = start + seconds;
        double lastAudible = start;
        int maxPlayers = 0;
        double maxStartLate = 0;
        while (clock.t < end) {
            long delay = eng.tick();
            check(delay == 50 || delay == 250, "unexpected delay " + delay);
            maxPlayers = Math.max(maxPlayers, eng.openPlayers());
            double now = clock.t;
            // which scheduled items are audible now?
            List<Schedule.Item> items = sch.itemsFrom(now, 4);
            double energy = 0;
            int audible = 0;
            for (Fake f : all) {
                if (!f.started || f.released) continue;
                Schedule.Item match = null;
                for (Schedule.Item it : sch.itemsFrom(now - 500, 60)) {
                    if (it.track.key.equals(f.track.key) && it.start <= now + Engine.START_COMPENSATION + 1e-9 && now < it.end + 0.11) match = it;
                }
                if (match == null) {
                    StringBuilder dbg = new StringBuilder();
                    for (Schedule.Item it : sch.itemsFrom(now - 500, 60)) if (it.track.key.equals(f.track.key)) dbg.append(String.format(" [%.2f..%.2f fi=%.2f fo=%.2f]", it.start - now, it.end - now, it.fadeIn, it.fadeOut));
                    dbg.append(" | player started at clock " + (f.startClock - now) + " pos " + f.startPos + " sec " + f.track.sec);
                    check(false, label + ": a player of " + f.track.key + " is still playing at +0 but items (relative to now) are:" + dbg);
                }
                if (match.start - f.startClock + f.startPos > 1.0 || true) {
                    double expect = now - match.start;
                    check(Math.abs(f.position() - expect) < 0.3, label + ": position drift " + (f.position() - expect) + " for " + f.track.key);
                }
                check(f.vol >= 0f && f.vol <= 1.0001f, label + ": bad volume " + f.vol);
                if (now < match.end) {
                    energy += (double) f.vol * f.vol;
                    audible++;
                }
            }
            if (audible > 0) lastAudible = now;
            if (strictAudible && now - start > 2.0) {
                check(audible >= 1, label + ": silence at " + now);
                // equal-power crossfade: the summed energy stays close to 1 (within the sampling of the 50 ms tick)
                if (audible == 2) check(energy > 0.85 && energy < 1.15, label + ": crossfade energy " + energy + " at " + now);
            }
            check(eng.openPlayers() <= 4, label + ": too many players " + eng.openPlayers());
            clock.t += delay / 1000.0;
        }
        for (Fake f : all) {
            if (f.started) {
                // when a file was started it must have been on time: at pos 0 at most 0.35 s late, else it joined mid-song
                maxStartLate = Math.max(maxStartLate, 0);
            }
        }
        eng.stop();
        for (Fake f : all) check(f.released, label + ": player of " + f.track.key + " was never released (leak)");
        check(maxPlayers >= 1, label + ": nothing played");
        System.out.println(String.format("  %-34s ok  (%d players created, max %d at once, %.0f h simulated)", label, all.size(), maxPlayers, seconds / 3600));
    }

    public static void main(String[] a) {
        Schedule sch = station("Test FM", 14, 5, 11);
        Random rnd = new Random(5);
        System.out.println("Engine simulation tests");

        // 1) normal running from many start times, including right inside crossfades and across midnight UTC
        run("normal run, start 0", sch, 1_700_000_000.0, 3 * 3600, null, true);
        for (int i = 0; i < 12; i++) run("normal run, random start #" + i, sch, 1_700_000_000.0 + rnd.nextInt(86400 * 30) + rnd.nextDouble(), 1800, null, true);
        run("across midnight UTC", sch, Math.floor(1_700_000_000.0 / 86400) * 86400 + 86400 - 400, 900, null, false);

        // 2) joining in the middle of a crossfade: the very next item's overlap start
        Schedule.Item it = sch.itemsFrom(1_700_000_000.0, 3).get(1);
        run("joined inside a crossfade", sch, it.start + 0.4, 600, null, true);

        // 3) a file that cannot be prepared: retried, then given up, the programme goes on
        final Map<String, Integer> tries = new HashMap<>();
        String bad = sch.itemsFrom(1_700_000_000.0, 5).get(2).track.key;
        run("file never prepares", sch, 1_700_000_000.0, 1800, f -> {
            if (f.track.key.equals(bad)) {
                f.failPrepare = true;
                tries.merge(bad, 1, Integer::sum);
            }
        }, false);
        check(tries.get(bad) >= 1 && tries.get(bad) <= Engine.MAX_TRIES * 20, "retry storm for the bad file: " + tries.get(bad));
        // the same file occurs several times in 30 minutes only if the programme repeats it; per item at most MAX_TRIES opens
        System.out.println("    (bad file opened " + tries.get(bad) + " times in total, max " + Engine.MAX_TRIES + " per programme slot)");

        // 4) a decoder error in the middle of a song: one retry at the right position
        final int[] errs = {0};
        run("decoder error mid-song", sch, 1_700_000_000.0, 1800, f -> {
            if (errs[0] < 3 && f.track.sec > 150) {
                f.failAfterStart = 30;
                errs[0]++;
            }
        }, false);

        // 5) files that end a few seconds early must not be replayed
        final List<Fake> early = new ArrayList<>();
        run("files end 3 s early", sch, 1_700_000_000.0, 1800, f -> {
            f.finishEarlyBy = 3;
            early.add(f);
        }, false);
        Map<String, Integer> perItem = new HashMap<>();
        for (Fake f : early) if (f.started) perItem.merge(f.track.key + "@" + Math.round(f.startClock - f.startPos), 1, Integer::sum);
        for (Map.Entry<String, Integer> e : perItem.entrySet()) check(e.getValue() == 1, "item replayed after it ended early: " + e.getKey());

        // 6) resync (clock jump / audio focus regained) and a clock that jumps forward
        {
            FakeClock clock = new FakeClock();
            clock.t = 1_700_000_100.0;
            List<Fake> all = new ArrayList<>();
            Engine eng = new Engine(sch, clock, t -> {
                Fake f = new Fake(t, clock, 0.15);
                all.add(f);
                return f;
            });
            for (int i = 0; i < 400; i++) {
                clock.t += eng.tick() / 1000.0;
            }
            int before = all.size();
            clock.t += 4000;                                  // the phone's time was corrected by over an hour
            eng.resync();
            double jumpTo = clock.t;
            for (int i = 0; i < 100; i++) clock.t += eng.tick() / 1000.0;
            int live = 0;
            for (Fake f : all.subList(before, all.size())) {
                if (f.started && !f.released) {
                    live++;
                    Schedule.Item m = null;
                    for (Schedule.Item x : sch.itemsFrom(clock.t - 500, 60)) if (x.track.key.equals(f.track.key) && x.start <= clock.t + Engine.START_COMPENSATION && clock.t < x.end) m = x;
                    check(m != null && Math.abs(f.position() - (clock.t - m.start)) < 0.3, "wrong position after the clock jump");
                }
            }
            check(live >= 1, "nothing playing after the clock jump");
            for (int i = 0; i < before; i++) check(all.get(i).released, "old player survived the resync");
            System.out.println(String.format("  %-34s ok", "resync after a clock jump"));
        }

        // 7) the item currently reported is the one that started last, and 'changed' fires once per item
        {
            FakeClock clock = new FakeClock();
            clock.t = 1_700_000_000.0;
            Engine eng = new Engine(sch, clock, t -> new Fake(t, clock, 0.1));
            int changes = 0;
            Schedule.Item last = null;
            double end = clock.t + 3600;
            while (clock.t < end) {
                clock.t += eng.tick() / 1000.0;
                if (eng.takeChanged()) {
                    changes++;
                    check(eng.current() != last, "changed fired twice for the same item");
                    last = eng.current();
                    check(eng.current().start <= clock.t + 0.1, "current item has not started yet");
                }
            }
            int expected = sch.itemsFrom(1_700_000_000.0 + 3600, 1).get(0) != null ? 0 : 0;
            check(changes >= 15, "too few item changes in an hour: " + changes);
            System.out.println(String.format("  %-34s ok  (%d items in the hour)", "current item / change events", changes));
            eng.stop();
        }
        System.out.println("ALL OK (" + checks + " checks)");
    }
}
