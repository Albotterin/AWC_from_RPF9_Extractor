package org.awctool.radio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The programme of one station: a pure function of the station and the UTC clock.
 *
 * This is a line-by-line port of class Schedule in awc_radio.py and must give exactly the same result (same order,
 * same times, same crossfades), so that the phone and the PC play the same song at the same second.
 * Times are UNIX seconds (double), like time.time() in Python.
 */
public final class Schedule {
    public static final double DAY = 86400.0;
    public static final double FADE = 3.0;

    public static final class Track {
        public final String key;      // "<base>_<idx>", also the file name of the audio
        public final String title;
        public final String artist;
        public final double sec;
        /** Parts of a long mix (start second, title, artist), or null. The title is empty for a part without a name. */
        public final double[] partStart;
        public final String[] partTitle, partArtist;

        public Track(String key, String title, String artist, double sec) {
            this(key, title, artist, sec, null, null, null);
        }

        public Track(String key, String title, String artist, double sec, double[] partStart, String[] partTitle, String[] partArtist) {
            this.key = key;
            this.title = title;
            this.artist = artist;
            this.sec = sec;
            this.partStart = partStart;
            this.partTitle = partTitle;
            this.partArtist = partArtist;
        }

        /** Index of the part that plays at `pos` seconds into the track, -1 before the first part or without parts. */
        public int partAt(double pos) {
            if (partStart == null) return -1;
            int lo = 0, hi = partStart.length - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (partStart[mid] <= pos) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found;
        }
    }

    /** One entry of the programme. [start, end) is when its audio is on air; it fades in for fadeIn seconds after
     *  start and fades out for fadeOut seconds before end (the next item starts when the fade-out begins). */
    public static final class Item {
        public final double start, end, fadeIn, fadeOut;
        public final boolean jingle;
        public final Track track;

        Item(double start, double end, boolean jingle, Track track, double fadeIn, double fadeOut) {
            this.start = start;
            this.end = end;
            this.jingle = jingle;
            this.track = track;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }
    }

    private static final class Day {
        final double[] starts;
        final boolean[] jingle;
        final Track[] tracks;
        final double[] fadeIn, fadeOut;

        Day(int n) {
            starts = new double[n];
            jingle = new boolean[n];
            tracks = new Track[n];
            fadeIn = new double[n];
            fadeOut = new double[n];
        }
    }

    private final String name;
    private final List<Track> songs;
    private final List<Track> jingles;
    private final Map<Integer, Day> days = new HashMap<>();

    public Schedule(String name, List<Track> songs, List<Track> jingles) {
        this.name = name;
        Comparator<Track> byKey = (a, b) -> a.key.compareTo(b.key);
        this.songs = new ArrayList<>(songs);
        this.jingles = new ArrayList<>(jingles);
        Collections.sort(this.songs, byKey);
        Collections.sort(this.jingles, byKey);
    }

    /** Stable hash: crc32 of the parts joined with '|' (see _h in awc_radio.py). */
    private static long h(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(parts[i]);
        }
        CRC32 crc = new CRC32();
        crc.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    private Day day(int d) {
        Day cached = days.get(d);
        if (cached != null) return cached;

        List<Double> starts = new ArrayList<>();
        List<Boolean> kinds = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        List<Double> fin = new ArrayList<>();
        List<Double> fout = new ArrayList<>();

        List<Track> deck = new ArrayList<>();
        int npass = 0, jn = 0;
        Track lastSong = null, lastJingle = null;
        double prevStart = 0.0, prevSec = 0.0;
        int count = 0;
        int target = 2 + (int) (h(name, d, "target", 0) % 3);
        while (true) {
            boolean isJingle;
            Track tr;
            if (!jingles.isEmpty() && count >= target) {
                jn++;
                count = 0;
                target = 2 + (int) (h(name, d, "target", jn) % 3);
                List<Track> pool = new ArrayList<>();
                for (Track j : jingles) if (j != lastJingle) pool.add(j);
                if (pool.isEmpty()) pool = jingles;
                tr = pool.get((int) (h(name, d, "jingle", jn) % pool.size()));
                lastJingle = tr;
                isJingle = true;
            } else {
                if (deck.isEmpty()) {
                    npass++;
                    final int pass = npass;
                    final Map<Track, Long> hashes = new HashMap<>();
                    for (Track s : songs) hashes.put(s, h(name, d, pass, s.key));
                    deck = new ArrayList<>(songs);
                    // Python: sorted(..., key=hash, reverse=True) - descending, equal keys keep their order (stable)
                    Collections.sort(deck, (a, b) -> Long.compare(hashes.get(b), hashes.get(a)));
                    if (deck.size() > 1 && deck.get(deck.size() - 1) == lastSong) {   // no repeat across two passes
                        Track tmp = deck.get(deck.size() - 1);
                        deck.set(deck.size() - 1, deck.get(0));
                        deck.set(0, tmp);
                    }
                }
                tr = deck.remove(deck.size() - 1);
                lastSong = tr;
                count++;
                isJingle = false;
            }
            double sec = Math.max(1.0, tr.sec);
            double ov, start;
            if (!starts.isEmpty()) {
                ov = Math.min(FADE, Math.min(0.25 * prevSec, 0.25 * sec));     // crossfade with the previous item
                start = prevStart + prevSec - ov;
                if (start >= DAY) break;
                fout.set(fout.size() - 1, ov);
            } else {
                ov = 0.0;
                start = 0.0;
            }
            starts.add(start);
            kinds.add(isJingle);
            tracks.add(tr);
            fin.add(ov);
            fout.add(0.0);
            prevStart = start;
            prevSec = sec;
        }
        Day out = new Day(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            out.starts[i] = starts.get(i);
            out.jingle[i] = kinds.get(i);
            out.tracks[i] = tracks.get(i);
            out.fadeIn[i] = fin.get(i);
            out.fadeOut[i] = fout.get(i);
        }
        if (days.size() > 4) days.clear();
        days.put(d, out);
        return out;
    }

    /** The item that started last at UNIX time {@code now} and the n-1 after it. */
    public List<Item> itemsFrom(double now, int n) {
        int d = (int) Math.floor(now / DAY);
        Day day = day(d);
        double local = now - d * DAY;
        // bisect_right(starts, local) - 1
        int lo = 0, hi = day.starts.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (local < day.starts[mid]) hi = mid;
            else lo = mid + 1;
        }
        int i = Math.max(0, lo - 1);
        List<Item> out = new ArrayList<>();
        while (out.size() < n) {
            if (i >= day.starts.length) {
                d++;
                i = 0;
                day = day(d);
            }
            Track tr = day.tracks[i];
            double end = Math.min(day.starts[i] + Math.max(1.0, tr.sec), DAY);
            out.add(new Item(d * DAY + day.starts[i], d * DAY + end, day.jingle[i], tr, day.fadeIn[i], day.fadeOut[i]));
            i++;
        }
        return out;
    }
}
