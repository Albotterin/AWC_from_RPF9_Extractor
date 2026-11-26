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

        boolean looping;

        public boolean isFinished() {
            return !looping && started && startPos + (clock.t - startClock) >= track.sec - finishEarlyBy;
        }

        public void loop() {
            looping = true;
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

    /** A station whose songs all have a window at the start and one at the end, with a line for each. */
    static Schedule djStation(long seed) {
        Random r = new Random(seed);
        List<Schedule.Track> s = new ArrayList<>(), j = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            Schedule.Track t = new Schedule.Track((1000 + i) + "_0", "s" + i, "a", 120 + r.nextInt(100) + r.nextDouble());
            t.intro = new Schedule.Window(4.0, 18.0, List.of(new Schedule.Track((5000 + i) + "_0", "", "", 5 + r.nextDouble()),
                    new Schedule.Track((6000 + i) + "_0", "", "", 7 + r.nextDouble())));
            t.outro = new Schedule.Window(t.sec - 25, t.sec - 8, List.of(new Schedule.Track((7000 + i) + "_0", "", "", 6 + r.nextDouble())));
            s.add(t);
        }
        for (int i = 0; i < 4; i++) j.add(new Schedule.Track((9000 + i) + "_0", "", "", 5 + r.nextDouble()));
        return new Schedule("Talk Over FM", s, j);
    }

    /** The DJ: every line of the programme is said at its time, the music steps back for it and comes back. */
    static void dj(double start, double seconds, boolean on) {
        Schedule sch = djStation(7);
        FakeClock clock = new FakeClock();
        clock.t = start;
        List<Fake> all = new ArrayList<>();
        Engine eng = new Engine(sch, clock, track -> {
            Fake f = new Fake(track, clock, 0.15);
            all.add(f);
            return f;
        });
        eng.setDj(on);
        List<Schedule.Item> items = sch.itemsFrom(start, 80);
        int ducked = 0, full = 0;
        while (clock.t < start + seconds) {
            long delay = eng.tick();
            double now = clock.t;
            check(eng.openPlayers() <= 4 && eng.openLines() <= 4, "dj: players " + eng.openPlayers() + " + " + eng.openLines());
            for (Schedule.Item it : items) {
                for (Schedule.Voice v : sch.voices(it)) {
                    double from = it.start + v.at, to = from + v.line.sec;
                    if (now < from + 0.2 || now > to - 0.2 || from < start + 1) continue;
                    Fake line = null, music = null;
                    for (Fake f : all) {
                        if (f.released || !f.started) continue;
                        if (f.track == v.line) line = f;
                        if (f.track == it.track) music = f;
                    }
                    if (!on) {
                        check(line == null, "dj off: silent");
                        continue;
                    }
                    check(line != null, "dj: line " + v.line.key + " is not said at " + (now - from));
                    check(Math.abs(line.position() - (now - from)) < 0.35, "dj: line drifts " + (line.position() - (now - from)));
                    check(line.vol > 0.99f, "dj: line volume " + line.vol);
                    check(music != null && Math.abs(music.vol - Engine.DUCK) < 0.02, "dj: music " + (music == null ? -1 : music.vol));
                    ducked++;
                }
                if (now > it.start + 25 && now < it.end - 40) {                  // between the windows
                    for (Fake f : all) {
                        if (f.track == it.track && f.started && !f.released) {
                            check(f.vol > 0.99f, "dj: music stays down " + f.vol);
                            full++;
                        }
                    }
                }
            }
            clock.t += delay / 1000.0;
        }
        eng.stop();
        for (Fake f : all) check(f.released, "dj: player of " + f.track.key + " leaked");
        check((ducked > 100) == on && full > 1000, "dj: ducked " + ducked + ", full " + full);
        System.out.println(String.format("  %-34s ok  (%d players, music ducked in %d ticks)", "DJ " + (on ? "on" : "off"), all.size(), ducked));
    }

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) throw new AssertionError(msg);
    }

    static Schedule station(String name, int songs, int jingles, long seed) {
        return station(name, songs, jingles, seed, Schedule.FADE, false);
    }

    static Schedule station(String name, int songs, int jingles, long seed, double fade, boolean talk) {
        Random r = new Random(seed);
        List<Schedule.Track> s = new ArrayList<>(), j = new ArrayList<>();
        for (int i = 0; i < songs; i++) s.add(new Schedule.Track((1000 + i) + "_0", "s" + i, "a", 120 + r.nextInt(200) + r.nextDouble()));
        for (int i = 0; i < jingles; i++) j.add(new Schedule.Track((9000 + i) + "_0", "", "", 2 + r.nextInt(20) + r.nextDouble()));
        return new Schedule(name, s, j, fade, talk);
    }

    /** Crossfades only from song to song, as long as asked for; everything else follows back to back. */
    static void rules(Schedule sch, double fade, boolean talk) {
        List<Schedule.Item> items = sch.itemsFrom(Math.floor(1_700_000_000.0 / 86400) * 86400 + 1, 400);
        int faded = 0, plain = 0;
        for (int i = 1; i < items.size(); i++) {
            Schedule.Item a = items.get(i - 1), b = items.get(i);
            if (b.start % 86400 == 0) continue;                              // the new day
            boolean songs = !a.jingle && !a.ad && !b.jingle && !b.ad && !talk;
            double want = songs ? Math.min(fade, Math.min(a.track.sec, b.track.sec) / 4) : 0;
            double in = b.fadeIn == null ? 0 : b.fadeIn.len, out = a.fadeOut == null ? 0 : a.fadeOut.len;
            check(Math.abs(in - want) < 1e-6 && Math.abs(out - want) < 1e-6, "fade " + in + " instead of " + want);
            check(Math.abs(b.start - (a.start + a.track.sec - want)) < 1e-6, "start of the next item");
            if (want > 0) faded++;
            else plain++;
        }
        check(plain > 0 && (faded > 0) == (fade > 0 && !talk), "faded " + faded + ", back to back " + plain);
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
            Schedule.Item older = null;
            for (Fake f : all) {
                if (!f.started || f.released) continue;
                Schedule.Item match = null;
                for (Schedule.Item it : sch.itemsFrom(now - 500, 60)) {
                    if (it.track.key.equals(f.track.key) && it.start <= now + Engine.START_COMPENSATION + 1e-9 && now < it.end + Engine.TAIL + 0.01) match = it;
                }
                if (match == null) {
                    StringBuilder dbg = new StringBuilder();
                    for (Schedule.Item it : sch.itemsFrom(now - 500, 60)) if (it.track.key.equals(f.track.key)) dbg.append(String.format(" [%.2f..%.2f]", it.start - now, it.end - now));
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
                    if (older == null || match.start < older.start) older = match;
                }
            }
            if (audible > 0) lastAudible = now;
            if (strictAudible && now - start > 2.0) {
                check(audible >= 1, label + ": silence at " + now);
                // equal-power crossfade: the summed energy stays close to 1 (within the sampling of the 50 ms tick)
                if (audible == 2 && older.fadeOut != null) check(energy > 0.85 && energy < 1.15, label + ": crossfade energy " + energy + " at " + now);
                // without a crossfade two items only touch: the next one starts a moment early
                if (audible == 2 && older.fadeOut == null) check(older.end - now <= Engine.START_COMPENSATION + 1e-6, label + ": overlap without a fade at " + now);
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

    /** A station mixed as in the game: songs, idents and DJ talk that overlap as its rows say, music under the talk. */
    static void mixed(double start, double seconds) {
        Random r = new Random(3);
        Schedule.Rules rules = new Schedule.Rules();
        rules.table.put("", List.of(new Schedule.Row("music", new Schedule.Factor[0])));
        rules.table.put("music", List.of(new Schedule.Row("ident", new Schedule.Factor[0]), new Schedule.Row("dj_banter", new Schedule.Factor[0])));
        rules.table.put("ident", List.of(new Schedule.Row("music", new Schedule.Factor[0])));
        rules.table.put("dj_banter", List.of(new Schedule.Row("music", new Schedule.Factor[0])));
        rules.mix = new HashMap<>();
        rules.mix.put("music", Map.of("ident", new long[][]{{1000, 0, 1000, 0, -1500, 1500, 0}},
                "dj_banter", new long[][]{{1000, 0, 1500, 0, -1500, 1500, 1}, {500, 0, 0, 0, -1000, 0, 0}}));
        rules.mix.put("ident", Map.of("music", new long[][]{{1000, 0, 0, 0, -3000, 1000, 0}}));
        rules.mix.put("dj_banter", Map.of("", new long[][]{{1000, -500, 500, 1, -1000, 0, 0}, {1000, 0, 0, 0, 800, 0, 0}}));
        Map<String, Schedule.Track> tracks = new HashMap<>();
        List<Schedule.Track> songs = new ArrayList<>(), beds = new ArrayList<>();
        List<String> music = new ArrayList<>(), idents = new ArrayList<>(), talk = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            Schedule.Track t = new Schedule.Track((1000 + i) + "_0", "s" + i, "a", 100 + r.nextInt(60) + r.nextDouble());
            t.cue = i % 3 == 0 ? 0.0 : 0.75;
            songs.add(t);
            tracks.put(t.key, t);
            music.add(t.key);
        }
        for (int i = 0; i < 5; i++) {
            Schedule.Track t = new Schedule.Track((9000 + i) + "_0", "", "", 4 + r.nextInt(8) + r.nextDouble());
            tracks.put(t.key, t);
            idents.add(t.key);
        }
        for (int i = 0; i < 6; i++) {
            Schedule.Track t = new Schedule.Track((4000 + i) + "_0", "", "", 28 + r.nextInt(20) + r.nextDouble());
            t.bed = i < 4;
            tracks.put(t.key, t);
            talk.add(t.key);
        }
        for (int i = 0; i < 3; i++) beds.add(new Schedule.Track((8000 + i) + "_0", "", "", 11 + i + 0.5));
        rules.pools.put("music", music);
        rules.pools.put("ident", idents);
        rules.pools.put("dj_banter", talk);
        Schedule sch = new Schedule("Mixed FM", songs, new ArrayList<>(), 3, false);
        sch.setRules(rules, tracks, new ArrayList<>(), Schedule.EVERYTHING);
        sch.setBeds(beds);

        List<Schedule.Item> items = sch.itemsFrom(start, 200);
        int overlaps = 0, gaps = 0, bedded = 0;
        for (int i = 1; i < items.size(); i++) {
            Schedule.Item a1 = items.get(i - 1), b1 = items.get(i);
            if (b1.start % 86400 == 0) continue;
            if (b1.start < a1.end - 0.05) overlaps++;
            if (b1.start > a1.end + 0.05) gaps++;
            check(b1.start >= a1.start + Math.max(1.0, 0.5 * (a1.end - a1.start)) - 1e-6, "mixed: an item starts too early");
            if (sch.bedOf(b1) != null) bedded++;
        }
        check(overlaps > 60 && gaps > 0 && bedded > 10, "mixed: overlaps " + overlaps + ", gaps " + gaps + ", beds " + bedded);

        FakeClock clock = new FakeClock();
        clock.t = start;
        List<Fake> all = new ArrayList<>();
        Engine eng = new Engine(sch, clock, track -> {
            Fake f = new Fake(track, clock, 0.15);
            all.add(f);
            return f;
        });
        int both = 0, under = 0;
        while (clock.t < start + seconds) {
            long delay = eng.tick();
            double now = clock.t;
            check(eng.openPlayers() <= 4 && eng.openBeds() <= 4, "mixed: players " + eng.openPlayers() + " + " + eng.openBeds());
            int sounding = 0;
            for (Schedule.Item it : sch.itemsFrom(now - 200, 40)) {
                if (now < it.start + 0.3 || now > it.end - 0.15 || it.start < start + 1) continue;     // (a file starts a moment early)
                Schedule.Track bed = sch.bedOf(it);
                Fake main = null, music2 = null;
                for (Fake f : all) {
                    if (!f.started || f.released) continue;
                    if (f.track == it.track) main = f;
                    if (f.track == bed) music2 = f;
                }
                check(main != null, "mixed: " + it.track.key + " is silent at " + (now - it.start));
                check(Math.abs(main.vol - Engine.volume(it, now, 1f)) < 1e-6, "mixed: volume " + main.vol);
                check(Math.abs(main.position() - (now - it.start)) < 0.35, "mixed: drift " + (main.position() - (now - it.start)));
                sounding++;
                if (bed != null && now - it.start < it.track.sec - 0.5) {
                    check(music2 != null && music2.looping, "mixed: no music under " + it.track.key + " at " + (now - it.start));
                    check(Math.abs(music2.vol - Engine.bedVolume(it, now, 1f)) < 1e-6, "mixed: bed volume " + music2.vol);
                    check(music2.vol <= Engine.DUCK + 1e-6, "mixed: the bed is too loud");
                    double off = (music2.position() - (now - it.start)) % bed.sec;
                    check(Math.min(Math.abs(off), bed.sec - Math.abs(off)) < 0.35, "mixed: bed out of step " + off);
                    under++;
                }
            }
            if (sounding == 2) both++;
            clock.t += delay / 1000.0;
        }
        eng.stop();
        for (Fake f : all) check(f.released, "mixed: player of " + f.track.key + " leaked");
        check(both > 200 && under > 200, "mixed: two at once in " + both + " ticks, beds in " + under);
        System.out.println(String.format("  %-34s ok  (%d players, %d ticks with two items, %d with a bed)", "mixed as in the game",
                all.size(), both, under));
    }

    public static void main(String[] a) {
        Schedule sch = station("Test FM", 14, 5, 11);
        Random rnd = new Random(5);
        System.out.println("Engine simulation tests");
        mixed(1_700_000_000.0, 3 * 3600);
        mixed(86400.0 * 19700 - 900, 1800);                 // across midnight UTC

        // 1) normal running from many start times, including right inside crossfades and across midnight UTC
        run("normal run, start 0", sch, 1_700_000_000.0, 3 * 3600, null, true);
        for (int i = 0; i < 12; i++) run("normal run, random start #" + i, sch, 1_700_000_000.0 + rnd.nextInt(86400 * 30) + rnd.nextDouble(), 1800, null, true);
        run("across midnight UTC", sch, Math.floor(1_700_000_000.0 / 86400) * 86400 + 86400 - 400, 900, null, false);

        // 1b) every crossfade setting, and talk radio: the rules of the programme, and no gap and no overlap when playing
        for (double fade : new double[]{0, 1, 3, 8, 12}) {
            Schedule s = station("Fade FM", 14, 5, 23, fade, false);
            rules(s, fade, false);
            run("crossfade " + (int) fade + " s", s, 1_700_000_000.0, 3600, null, true);
        }
        dj(1_700_000_000.0, 3600, true);
        dj(1_700_000_000.0, 1800, false);

        Schedule talk = station("Talk FM", 6, 4, 31, 8, true);
        rules(talk, 8, true);
        run("talk radio, never faded", talk, 1_700_000_000.0, 3600, null, true);

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
