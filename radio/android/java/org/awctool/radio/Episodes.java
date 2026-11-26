package org.awctool.radio;

import java.util.List;

/**
 * A podcast played on demand: unlike the radio it waits for the listener. One episode after the other, with pause,
 * seeking and a remembered position in every episode. The score and queues of songs are played the same way; songs can
 * also be repeated (setRepeat) and their order changed while one plays (reorder). Pure Java (tested on the PC);
 * RadioService drives it with tick().
 */
final class Episodes {
    /** What it needs from a player (PackPlayer). */
    interface Player {
        boolean isPrepared();

        boolean isFailed();

        boolean isFinished();

        void startAt(double posSec, float volume);

        void pause();

        void resume();

        void seekTo(double posSec);

        /** Seconds from the start of the episode; only asked once it has started. */
        double position();

        void setVolume(float volume);

        void release();
    }

    interface Factory {
        Player create(Schedule.Track episode);
    }

    /** Where the listener was in each episode. */
    interface Memory {
        /** 0 for an episode that was never started or was heard to the end. */
        double position(String key);

        void remember(String key, double position, boolean heard);
    }

    static final int REPEAT_OFF = 0, REPEAT_ALL = 1, REPEAT_ONE = 2;
    static final double END_ZONE = 15;           // an episode left this close to its end counts as heard
    private static final double SAVE_EVERY = 5;

    private List<Schedule.Track> list;
    private int repeat;
    private final Factory factory;
    private final Memory memory;
    private Player player;
    private int index = -1;
    private boolean wanted, started, failed, ended;
    private double target;                       // where the episode starts, while it has not started yet
    private double saved;
    private float volume = 1f;

    Episodes(List<Schedule.Track> list, Factory factory, Memory memory) {
        this.list = list;
        this.factory = factory;
        this.memory = memory;
    }

    int index() {
        return index;
    }

    int count() {
        return list.size();
    }

    Schedule.Track current() {
        return index < 0 ? null : list.get(index);
    }

    /** The listener wants to hear it (it may still be loading). */
    boolean isPlaying() {
        return wanted;
    }

    boolean isLoading() {
        return wanted && !started && !failed;
    }

    boolean hasFailed() {
        return failed;
    }

    /** The last episode was heard to its end. */
    boolean hasEnded() {
        return ended;
    }

    double duration() {
        return index < 0 ? 0 : current().sec;
    }

    double position() {
        if (index < 0) return 0;
        if (ended) return duration();
        double p = started && player != null ? player.position() : target;
        return Math.max(0, Math.min(duration(), p));
    }

    /** Go to an episode, at the place where the listener left it. */
    void open(int i, boolean play) {
        save();
        load(Math.max(0, Math.min(list.size() - 1, i)), play);
    }

    private void load(int i, boolean play) {
        drop();
        index = i;
        Schedule.Track t = list.get(i);
        target = memory.position(t.key);
        if (target < 0 || target > t.sec - END_ZONE) target = 0;
        saved = target;
        wanted = play;
        started = failed = ended = false;
        player = factory.create(t);
    }

    /** Starts the player when it is ready and moves on at the end; true when another episode began. */
    boolean tick() {
        if (player == null) return false;
        if (player.isFailed()) {
            failed = true;
            wanted = false;
            return false;
        }
        if (!started) {
            if (wanted && player.isPrepared()) {
                player.startAt(target, volume);
                started = true;
            }
            return false;
        }
        if (player.isFinished()) {
            memory.remember(current().key, 0, true);
            if (repeat == REPEAT_ONE) {
                load(index, wanted);
                return true;
            }
            if (index + 1 < list.size() || repeat == REPEAT_ALL) {
                load((index + 1) % list.size(), wanted);
                return true;
            }
            ended = true;
            wanted = false;
            drop();
            return false;
        }
        if (Math.abs(position() - saved) >= SAVE_EVERY) save();
        return false;
    }

    void play() {
        if (index < 0) return;
        if (ended || failed) {
            load(index, true);
            return;
        }
        wanted = true;
        if (started) player.resume();
    }

    void pause() {
        wanted = false;
        if (started && player != null) player.pause();
        save();
    }

    void seekTo(double sec) {
        if (index < 0 || failed) return;
        if (ended) load(index, false);
        double to = Math.max(0, Math.min(duration() - 1, sec));
        if (started) player.seekTo(to);
        else target = to;
        saved = to;
        memory.remember(current().key, to, to >= duration() - END_ZONE);
    }

    void jump(double seconds) {
        seekTo(position() + seconds);
    }

    /** The next episode; false at the last one (unless everything repeats). */
    boolean next() {
        if (index < 0 || (index + 1 >= list.size() && repeat != REPEAT_ALL)) return false;
        open((index + 1) % list.size(), wanted);
        return true;
    }

    /** Back to the start of this episode, or from its first seconds to the one before; true if the episode changed. */
    boolean previous() {
        if (index < 0) return false;
        if (position() > 3 || (index == 0 && repeat != REPEAT_ALL)) {
            seekTo(0);
            return false;
        }
        open((index + list.size() - 1) % list.size(), wanted);
        return true;
    }

    void setRepeat(int how) {
        repeat = how;
    }

    /** The same entry that is on now at another place of another order: it plays on. */
    void reorder(List<Schedule.Track> order, int at) {
        list = order;
        index = at;
    }

    void setVolume(float v) {
        volume = v;
        if (started && player != null) player.setVolume(v);
    }

    /** Notes where the listener is. */
    void save() {
        if (index < 0 || player == null || ended || failed) return;
        double p = position();
        boolean heard = p >= duration() - END_ZONE;
        memory.remember(current().key, heard ? 0 : p, heard);
        saved = p;
    }

    private void drop() {
        if (player != null) player.release();
        player = null;
    }

    void release() {
        save();
        drop();
        wanted = started = false;
    }
}
