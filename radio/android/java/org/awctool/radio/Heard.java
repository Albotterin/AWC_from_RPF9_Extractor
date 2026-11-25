package org.awctool.radio;

import android.content.Context;
import android.content.SharedPreferences;

/** What the listener has heard of the podcasts: the place in every episode, the heard ones and the last one played. */
final class Heard implements Episodes.Memory {
    private final SharedPreferences prefs;

    Heard(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("podcasts", Context.MODE_PRIVATE);
    }

    @Override
    public double position(String key) {
        return prefs.getFloat("at:" + key, 0f);
    }

    @Override
    public void remember(String key, double position, boolean heard) {
        SharedPreferences.Editor e = prefs.edit().putFloat("at:" + key, heard ? 0f : (float) position);
        if (heard) e.putBoolean("done:" + key, true);
        e.apply();
    }

    /** Heard to the end at least once. */
    boolean done(String key) {
        return prefs.getBoolean("done:" + key, false);
    }

    /** The episode of a podcast played last, or -1. */
    int last(String podcast) {
        return prefs.getInt("last:" + podcast, -1);
    }

    void setLast(String podcast, int episode) {
        prefs.edit().putInt("last:" + podcast, episode).putString("show", podcast).apply();
    }

    /** The podcast played last, or "". */
    String lastShow() {
        return prefs.getString("show", "");
    }

    /** The episode to go on with: the one played last, else the next one not heard; -1 if all are heard. */
    int resumeAt(Library.Station podcast) {
        int last = last(podcast.name), n = podcast.songs.size();
        if (last < 0 || last >= n) return -1;
        for (int k = 0; k < n; k++) {
            int i = (last + k) % n;
            String key = podcast.songs.get(i).key;
            if (!done(key) || position(key) > 0) return i;
        }
        return -1;
    }
}
