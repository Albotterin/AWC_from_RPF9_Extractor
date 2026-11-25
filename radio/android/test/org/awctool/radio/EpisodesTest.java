package org.awctool.radio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** The playback of a podcast (Episodes) with fake players and a fake memory. */
public final class EpisodesTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final class Fake implements Episodes.Player {
        static int live, made;
        final Schedule.Track track;
        boolean prepared, failed, finished, running, released;
        double pos;
        float volume;

        Fake(Schedule.Track t) {
            track = t;
            live++;
            made++;
        }

        public boolean isPrepared() {
            return prepared && !released;
        }

        public boolean isFailed() {
            return failed;
        }

        public boolean isFinished() {
            return finished;
        }

        public void startAt(double p, float v) {
            check(!released && prepared, "start of a player that is not ready");
            pos = p;
            volume = v;
            running = true;
        }

        public void pause() {
            running = false;
        }

        public void resume() {
            running = true;
        }

        public void seekTo(double p) {
            check(p >= 0 && p <= track.sec, "seek outside the episode " + p);
            pos = p;
            finished = false;
        }

        public double position() {
            return pos;
        }

        public void setVolume(float v) {
            volume = v;
        }

        public void release() {
            check(!released, "released twice");
            released = true;
            running = false;
            live--;
        }

        void run(double seconds) {
            if (!running) return;
            pos += seconds;
            if (pos >= track.sec) {
                pos = track.sec;
                finished = true;
                running = false;
            }
        }
    }

    private static final class Notes implements Episodes.Memory {
        final Map<String, Double> at = new HashMap<>();
        final Map<String, Boolean> heard = new HashMap<>();
        int writes;

        public double position(String key) {
            return at.getOrDefault(key, 0.0);
        }

        public void remember(String key, double position, boolean done) {
            writes++;
            at.put(key, done ? 0.0 : position);
            if (done) heard.put(key, true);
        }
    }

    private static List<Schedule.Track> episodes(double... seconds) {
        List<Schedule.Track> out = new ArrayList<>();
        for (int i = 0; i < seconds.length; i++) out.add(new Schedule.Track("e" + i, "", "Show", seconds[i]));
        return out;
    }

    private static Fake last;

    private static Episodes make(List<Schedule.Track> list, Notes notes) {
        return new Episodes(list, t -> last = new Fake(t), notes);
    }

    private static void ready(Episodes e) {
        last.prepared = true;
        e.tick();
    }

    private static void play(Episodes e, double seconds) {
        for (double t = 0; t < seconds; t += 0.25) {
            last.run(0.25);
            e.tick();
            if (!last.prepared) last.prepared = true;
        }
    }

    public static void main(String[] args) {
        Notes notes = new Notes();
        Episodes e = make(episodes(600, 700, 300), notes);
        check(e.index() == -1 && e.position() == 0 && !e.next() && !e.previous(), "before anything is open");
        e.open(0, true);
        check(e.isLoading() && !e.tick() && !last.running, "waits for the player");
        ready(e);
        check(!e.isLoading() && last.running && last.pos == 0, "starts at the beginning");
        play(e, 60);
        check(Math.abs(e.position() - 60) < 0.01, "position " + e.position());
        check(notes.position("e0") >= 55, "the place is noted while playing: " + notes.position("e0"));
        check(notes.writes <= 14, "but not at every tick: " + notes.writes);
        e.pause();
        check(!last.running && !e.isPlaying() && Math.abs(notes.position("e0") - 60) < 0.01, "pause notes the place");
        play(e, 10);
        check(Math.abs(e.position() - 60) < 0.01, "paused stays");
        e.play();
        check(last.running, "goes on");
        e.jump(-15);
        check(Math.abs(e.position() - 45) < 0.01, "back 15");
        e.jump(-100);
        check(e.position() == 0, "not before the start");
        e.jump(30);
        check(Math.abs(e.position() - 30) < 0.01, "forward 30");
        e.seekTo(9999);
        check(Math.abs(e.position() - 599) < 0.01, "not behind the end");
        check(notes.heard.containsKey("e0") && notes.position("e0") == 0, "close to the end counts as heard");
        play(e, 2);
        check(e.index() == 1 && e.isPlaying(), "the next episode follows");
        ready(e);
        check(last.track.key.equals("e1") && last.running && last.pos < 2, "it starts at the beginning: " + last.pos);
        check(Fake.live == 1, "one player at a time");

        e.seekTo(200);
        e.open(2, true);
        check(Math.abs(notes.position("e1") - 200) < 0.01, "leaving an episode notes the place");
        ready(e);
        e.open(1, true);
        ready(e);
        check(Math.abs(last.pos - 200) < 0.01, "and coming back goes on there");

        check(!e.previous() && e.position() == 0 && e.index() == 1, "previous goes to the start first");
        check(e.previous() && e.index() == 0, "then to the episode before");
        ready(e);
        check(e.next() && e.index() == 1, "next");

        e.open(2, true);
        e.seekTo(100);                                           // before the player is ready
        check(Math.abs(e.position() - 100) < 0.01, "seek while loading");
        ready(e);
        check(Math.abs(last.pos - 100) < 0.01, "starts there");
        check(!e.next(), "no episode after the last");
        play(e, 250);
        check(e.hasEnded() && !e.isPlaying() && Fake.live == 0, "the end");
        check(e.position() == 300 && notes.position("e2") == 0 && notes.heard.containsKey("e2"), "heard");
        e.save();
        check(notes.position("e2") == 0, "the end is not noted as a place");
        e.play();
        check(!e.hasEnded() && e.isPlaying() && e.position() == 0, "play again starts over");
        ready(e);

        e.open(0, true);
        last.failed = true;
        e.tick();
        check(e.hasFailed() && !e.isPlaying() && !e.isLoading(), "a broken episode");
        int before = Fake.made;
        e.play();
        check(!e.hasFailed() && Fake.made == before + 1, "play tries again");
        ready(e);
        e.setVolume(0.25f);
        check(last.volume == 0.25f, "volume");

        e.open(1, false);
        ready(e);
        check(!last.running, "open without playing");
        e.play();
        e.tick();
        check(last.running, "then play");
        e.seekTo(321);
        e.release();
        check(Fake.live == 0 && Math.abs(notes.position("e1") - 321) < 0.01, "release notes the place");
        e.release();

        // an episode left just before its end starts over
        notes.at.put("e0", 595.0);
        e = make(episodes(600), notes);
        e.open(0, true);
        ready(e);
        check(last.pos == 0, "starts over near the end");
        e.release();

        Random r = new Random(3);
        e = make(episodes(40, 25, 90, 61), new Notes());
        e.open(0, true);
        for (int step = 0; step < 200000; step++) {
            switch (r.nextInt(14)) {
                case 0: e.open(r.nextInt(6) - 1, r.nextBoolean()); break;
                case 1: e.play(); break;
                case 2: e.pause(); break;
                case 3: e.jump(r.nextInt(100) - 50); break;
                case 4: e.seekTo(r.nextDouble() * 120 - 10); break;
                case 5: e.next(); break;
                case 6: e.previous(); break;
                case 7: if (r.nextInt(20) == 0) last.failed = true; break;
                case 8: last.prepared = true; break;
                default: last.run(r.nextDouble() * 3);
            }
            e.tick();
            check(Fake.live <= 1, "players " + Fake.live);
            check(e.position() >= 0 && e.position() <= e.duration(), "position " + e.position() + " of " + e.duration());
            check(!(e.hasEnded() && e.isPlaying()) && !(e.hasFailed() && e.isPlaying()), "state");
        }
        e.release();
        check(Fake.live == 0, "all released");
        System.out.println("Episodes: ALL OK (" + checks + " checks)");
    }
}
