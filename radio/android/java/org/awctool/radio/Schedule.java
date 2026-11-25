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
 * same times, same crossfades), so that the phone and the PC play the same song at the same second. A station with
 * adverts has a second programme with advert breaks (built when a list of adverts is given). Only two songs that follow
 * each other are crossfaded; the seconds of that overlap are part of the timetable. voices() says what the DJ says over
 * an item when the listener wants the DJ; that changes nothing in the timetable.
 * Times are UNIX seconds (double), like time.time() in Python.
 */
public final class Schedule {
    public static final double DAY = 86400.0;
    public static final double FADE = 3.0;               // standard seconds of crossfade between two songs
    public static final int FADE_MAX = 12;
    private static final int AD_AFTER = 540, AD_AFTER_SPAN = 361;   // an advert break after 9 to 15 minutes of songs
    // per cent: how often the DJ talks over an intro, an outro, into an advert break
    private static final int DJ_INTRO = 50, DJ_OUTRO = 35, DJ_BEFORE_ADS = 70;

    /** Where the DJ may talk over a song (seconds into it) and the lines that fit there. */
    public static final class Window {
        public final double from, to;
        public final List<Track> lines;

        public Window(double from, double to, List<Track> lines) {
            this.from = from;
            this.to = to;
            this.lines = lines;
        }
    }

    /** A line the DJ says over an item, `at` seconds into it. */
    public static final class Voice {
        public final double at;
        public final Track line;

        Voice(double at, Track line) {
            this.at = at;
            this.line = line;
        }
    }

    public static final class Track {
        public final String key;      // "<base>_<idx>", also the file name of the audio
        public final String title;
        public final String artist;
        public final double sec;
        /** Parts of a long mix (start second, title, artist), or null. The title is empty for a part without a name. */
        public final double[] partStart;
        public final String[] partTitle, partArtist;
        public Window intro, outro;       // the DJ's windows over a song, or null

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
        public final boolean jingle, ad;
        public final Track track;

        Item(double start, double end, boolean jingle, boolean ad, Track track, double fadeIn, double fadeOut) {
            this.start = start;
            this.end = end;
            this.jingle = jingle;
            this.ad = ad;
            this.track = track;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }
    }

    private static final class Day {
        final double[] starts;
        final boolean[] jingle, ad;
        final Track[] tracks;
        final double[] fadeIn, fadeOut;

        Day(int n) {
            starts = new double[n];
            jingle = new boolean[n];
            ad = new boolean[n];
            tracks = new Track[n];
            fadeIn = new double[n];
            fadeOut = new double[n];
        }
    }

    private final String name;
    private final List<Track> songs;
    private final List<Track> jingles;
    private final List<Track> ads;
    private final Map<Integer, Day> days = new HashMap<>();
    private final double fade;
    private final boolean talk;                          // talk radio: its items are shows, never crossfaded
    private List<Track> toads = Collections.emptyList();  // the DJ's lines into an advert break

    public Schedule(String name, List<Track> songs, List<Track> jingles) {
        this(name, songs, jingles, Collections.<Track>emptyList());
    }

    /** With a non-empty list of adverts: the programme with advert breaks. */
    public Schedule(String name, List<Track> songs, List<Track> jingles, List<Track> ads) {
        this(name, songs, jingles, ads, FADE, false);
    }

    /** `fade`: seconds of crossfade between two songs (0 = none); `talk`: the items are talk shows. */
    public Schedule(String name, List<Track> songs, List<Track> jingles, List<Track> ads, double fade, boolean talk) {
        this.fade = Math.min(FADE_MAX, Math.max(0.0, fade));
        this.talk = talk;
        this.name = name;
        Comparator<Track> byKey = (a, b) -> a.key.compareTo(b.key);
        this.songs = new ArrayList<>(songs);
        this.jingles = new ArrayList<>(jingles);
        this.ads = new ArrayList<>(ads);
        Collections.sort(this.songs, byKey);
        Collections.sort(this.jingles, byKey);
        Collections.sort(this.ads, byKey);
    }

    void setLinesIntoAds(List<Track> lines) {
        toads = lines;
    }

    /**
     * What the DJ says over an item of itemsFrom() (the same as voices() in awc_radio.py): over a song in its marked intro
     * (every second time) and outro (every third time); before an advert break the line that leads into it replaces the
     * outro.
     */
    public List<Voice> voices(Item item) {
        List<Voice> out = new ArrayList<>();
        Track tr = item.track;
        if (item.jingle || item.ad || (tr.intro == null && tr.outro == null)) return out;
        long h = h(name, "dj", (long) item.start, tr.key);
        double length = item.end - item.start;
        Window window = tr.intro;
        if (window != null && h % 100 < DJ_INTRO) {
            Track line = window.lines.get((int) ((h >> 8) % window.lines.size()));
            if (window.from >= item.fadeIn && window.from + line.sec <= length) out.add(new Voice(window.from, line));
        }
        window = tr.outro;
        if (window != null) {
            List<Track> lines = window.lines;
            int chance = DJ_OUTRO;
            if (!ads.isEmpty() && !toads.isEmpty() && itemsFrom(item.start + 0.001, 2).get(1).ad) {
                List<Track> fit = new ArrayList<>();
                for (Track t : toads) if (t.sec <= window.to - window.from + 0.6) fit.add(t);
                if (!fit.isEmpty()) {
                    lines = fit;
                    chance = DJ_BEFORE_ADS;
                }
            }
            if ((h >> 16) % 100 < chance) {
                Track line = lines.get((int) ((h >> 20) % lines.size()));
                boolean free = out.isEmpty() || window.from >= out.get(0).at + out.get(0).line.sec + 1.0;
                if (free && window.from + line.sec <= length) out.add(new Voice(window.from, line));
            }
        }
        return out;
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

    /** Python: sorted(list, key=hash, reverse=True) - descending, equal hashes keep their order (stable). */
    private List<Track> shuffled(List<Track> list, int d, String tag, int pass) {
        final Map<Track, Long> hashes = new HashMap<>();
        for (Track t : list) hashes.put(t, tag == null ? h(name, d, pass, t.key) : h(name, d, tag, pass, t.key));
        List<Track> out = new ArrayList<>(list);
        Collections.sort(out, (a, b) -> Long.compare(hashes.get(b), hashes.get(a)));
        return out;
    }

    /** The next to be taken (the last of the deck) must not be the one just played. */
    private static void swapRepeat(List<Track> deck, Track last) {
        if (deck.size() > 1 && deck.get(deck.size() - 1) == last) {
            Track tmp = deck.get(deck.size() - 1);
            deck.set(deck.size() - 1, deck.get(0));
            deck.set(0, tmp);
        }
    }

    private Day day(int d) {
        Day cached = days.get(d);
        if (cached != null) return cached;

        List<Double> starts = new ArrayList<>();
        List<Boolean> kinds = new ArrayList<>();
        List<Boolean> adverts = new ArrayList<>();
        List<Track> tracks = new ArrayList<>();
        List<Double> fin = new ArrayList<>();
        List<Double> fout = new ArrayList<>();

        List<Track> deck = new ArrayList<>();
        int npass = 0, jn = 0;
        Track lastSong = null, lastJingle = null;
        double prevStart = 0.0, prevSec = 0.0;
        int count = 0;
        int target = 2 + (int) (h(name, d, "target", 0) % 3);
        double played = 0.0;                                  // seconds of songs since the last advert break
        int bn = 0, apass = 0;
        boolean afterBreak = false, prevSong = false;
        List<Track> block = new ArrayList<>(), adDeck = new ArrayList<>();
        Track lastAd = null;
        long threshold = AD_AFTER + h(name, d, "adt", 0) % AD_AFTER_SPAN;
        while (true) {
            boolean isJingle, isAd = false;
            Track tr;
            if (!ads.isEmpty() && block.isEmpty() && !afterBreak && played >= threshold) {
                bn++;
                int n = 2 + (int) (h(name, d, "adn", bn) % 2);
                for (int k = 0; k < n; k++) {
                    if (adDeck.isEmpty()) {
                        apass++;
                        adDeck = shuffled(ads, d, "ads", apass);
                        swapRepeat(adDeck, lastAd);
                    }
                    lastAd = adDeck.remove(adDeck.size() - 1);
                    block.add(lastAd);
                }
                played = 0.0;
                threshold = AD_AFTER + h(name, d, "adt", bn) % AD_AFTER_SPAN;
            }
            if (!block.isEmpty()) {
                tr = block.remove(0);
                isAd = true;
                isJingle = false;
                afterBreak = block.isEmpty() && !jingles.isEmpty();       // "you are listening to ..." after the break
            } else if (!jingles.isEmpty() && (afterBreak || count >= target)) {
                afterBreak = false;
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
                    deck = shuffled(songs, d, null, npass);
                    swapRepeat(deck, lastSong);                                       // no repeat across two passes
                }
                tr = deck.remove(deck.size() - 1);
                lastSong = tr;
                count++;
                isJingle = false;
                played += Math.max(1.0, tr.sec);
            }
            double sec = Math.max(1.0, tr.sec);
            double ov, start;
            if (!starts.isEmpty()) {
                ov = !isJingle && !isAd && prevSong && !talk                  // crossfade only from song to song
                        ? Math.min(fade, Math.min(0.25 * prevSec, 0.25 * sec)) : 0.0;
                start = prevStart + prevSec - ov;
                if (start >= DAY) break;
                fout.set(fout.size() - 1, ov);
            } else {
                ov = 0.0;
                start = 0.0;
            }
            starts.add(start);
            kinds.add(isJingle);
            adverts.add(isAd);
            prevSong = !isJingle && !isAd;
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
            out.ad[i] = adverts.get(i);
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
            out.add(new Item(d * DAY + day.starts[i], d * DAY + end, day.jingle[i], day.ad[i], tr, day.fadeIn[i], day.fadeOut[i]));
            i++;
        }
        return out;
    }
}
