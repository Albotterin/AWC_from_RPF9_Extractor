package org.awctool.radio;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The programme of one station: a pure function of the station and the UTC clock.
 *
 * This is a line-by-line port of class Schedule in awc_radio.py and must give exactly the same result (same order,
 * same times, same crossfades), so that the phone and the PC play the same item at the same second. A station that
 * brings the game's programme rules follows them (setRules): with adverts, news and talk if wanted, else songs and
 * jingles; what comes at which hour is asked of the phone's clock. A station without rules plays its songs shuffled with
 * jingles. How an item hands over to the next is part of the timetable: where the pack has the game's mixing rules
 * they say by the two categories how early the next one starts and how both are faded; otherwise only two songs that
 * follow each other are crossfaded. Under the DJ's talk a station with music beds plays one of them (bedOf).
 * voices() says what the DJ says over an item when the listener wants the DJ; that changes nothing in the timetable.
 * Times are UNIX seconds (double), like time.time() in Python.
 */
public final class Schedule {
    public static final double DAY = 86400.0;
    public static final double FADE = 3.0;               // standard seconds of crossfade between two songs
    public static final int FADE_MAX = 12;
    // per cent: how often the DJ talks over an intro, an outro, into an advert break
    private static final int DJ_INTRO = 50, DJ_OUTRO = 35, DJ_BEFORE_ADS = 70;
    private static final double SPOKEN_FROM = 25.0;      // a longer spoken item is talk, a shorter one a jingle
    static final String SONG = "song", JINGLE = "jingle", AD = "ad", NEWS = "news", TALK = "talk";
    private static final List<String> SONG_CATEGORIES = java.util.Arrays.asList("music", "music_night", "music_night2", "music_dj2");
    private static final List<String> TALK_CATEGORIES =
            java.util.Arrays.asList("talk", "talk_night", "showintro", "showintro_night", "showoutro_night", "segment");

    /** One factor of a rule: a number, or a curve over the seconds since a category was last heard ('s'), the number
     *  of its items in a row ('c') or the hour ('h'). A curve is straight lines between points (xs, ys), or a table by
     *  the number of items in a row (xs null). */
    static final class Factor {
        final char what;                                  // 0: the number
        final double number;
        final String category;
        final double[] xs, ys;

        Factor(double number) {
            this((char) 0, number, "", null, null);
        }

        Factor(char what, String category, double[] xs, double[] ys) {
            this(what, 0, category, xs, ys);
        }

        private Factor(char what, double number, String category, double[] xs, double[] ys) {
            this.what = what;
            this.number = number;
            this.category = category;
            this.xs = xs;
            this.ys = ys;
        }

        double at(double x) {
            if (xs == null) return ys[Math.min(Math.max((int) x - 1, 0), ys.length - 1)];
            if (x < xs[0]) return ys[0];
            int i = 0;
            while (i + 1 < xs.length && xs[i + 1] <= x) i++;
            if (i + 1 == xs.length) return ys[i];
            return ys[i] + (ys[i + 1] - ys[i]) * (x - xs[i]) / (xs[i + 1] - xs[i]);
        }
    }

    /** What may follow: a category and the factors of its weight. */
    static final class Row {
        final String next;
        final Factor[] factors;

        Row(String next, Factor[] factors) {
            this.next = next;
            this.factors = factors;
        }
    }

    /** The game's programme rules of a station, as its pack holds them. */
    static final class Rules {
        final Map<String, List<Row>> table = new HashMap<>();      // after which category ("": the beginning)
        final Map<String, List<String>> pools = new HashMap<>();   // category -> the keys of its recordings
        final Map<String, Integer> history = new HashMap<>();      // how many of a category do not come back
        final Set<String> sequence = new HashSet<>();              // categories played in their order
        final Set<String> ads = new HashSet<>();                   // adverts that lie in other categories
        boolean talkRadio;
        // the game's mixing: category of the item that ends -> category of the next ("": any) -> rows of {weight, move,
        // fade-out, its shape, start, fade-in, its shape}, times in milliseconds (awc_gamedata.parse_rules); null: none
        Map<String, Map<String, long[][]>> mix;
    }

    /** A fade of an item: from second `at` of it, `len` seconds long; shape 0 straight, 1 equal power. */
    public static final class Fade {
        public final double at, len;
        public final int shape;

        Fade(double at, double len, int shape) {
            this.at = at;
            this.len = len;
            this.shape = shape;
        }

        /** The loudness at `x` of the way from silent (0) to full (1). */
        double gain(double x) {
            return shape == 1 ? Math.sin(Math.PI / 2 * x) : x;
        }
    }

    /** One recording of a category and its kind. */
    private static final class Entry {
        final String kind;
        final Track track;

        Entry(String kind, Track track) {
            this.kind = kind;
            this.track = track;
        }
    }

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
        public double cue;                // second of a song at which it is cued in (the silence before it)
        public boolean bed;               // talk of the DJ that gets a music bed under it

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

    /** One entry of the programme. [start, end) is when its audio is on air; fadeIn and fadeOut say how it comes in
     *  and goes (null: at full volume). The next item may start before that end or a little after it. */
    public static final class Item {
        public final double start, end;
        public final Fade fadeIn, fadeOut;
        public final String kind;                       // SONG, JINGLE, AD, NEWS or TALK
        public final boolean jingle, ad;
        public final Track track;

        Item(double start, double end, String kind, Track track, Fade fadeIn, Fade fadeOut) {
            this.start = start;
            this.end = end;
            this.kind = kind;
            this.jingle = JINGLE.equals(kind);
            this.ad = AD.equals(kind);
            this.track = track;
            this.fadeIn = fadeIn;
            this.fadeOut = fadeOut;
        }
    }

    private static final class Day {
        final double[] starts;
        final String[] kinds;
        final Track[] tracks;
        final Fade[] fadeIn, fadeOut;
        final double[] play;                              // seconds on air

        Day(int n) {
            starts = new double[n];
            kinds = new String[n];
            tracks = new Track[n];
            fadeIn = new Fade[n];
            fadeOut = new Fade[n];
            play = new double[n];
        }
    }

    /** A day while it is being filled. */
    private final class Filling {
        final List<Double> starts = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        final List<Track> tracks = new ArrayList<>();
        final List<Fade> fin = new ArrayList<>();
        final List<Fade> fout = new ArrayList<>();
        final List<Double> plays = new ArrayList<>();

        /** Put an item behind the last one; false when it would start after the day (see _add in awc_radio.py). */
        boolean add(String kind, Track tr, int d, String beforeCategory, String category) {
            double sec = Math.max(1.0, tr.sec);
            double start = 0.0;
            Fade fadeIn = null;
            if (!starts.isEmpty()) {
                int last = starts.size() - 1;
                double before = plays.get(last);
                double began = starts.get(last);
                start = began + before;
                double play = before;
                Fade fadeOut = null;
                if (rules != null && rules.mix != null) {
                    long[] v = mixing(d, starts.size(), beforeCategory, category);
                    if (v != null) {
                        long move = v[1], outMs = v[2], startMs = v[4], inMs = v[5];
                        double end = began + Math.max(0.5 * before, before + move / 1000.0);
                        double cue = tr.cue;
                        // never more than half of either item over the other, and never before the one that ends began
                        double lead = Math.max(startMs / 1000.0, -0.5 * sec);
                        start = Math.max(end + lead - cue, began + Math.max(1.0, 0.5 * before));
                        play = end - began;
                        if (outMs > 0) {
                            double length = Math.min(outMs / 1000.0, play);
                            fadeOut = new Fade(play - length, length, (int) v[3]);
                        }
                        if (inMs > 0) fadeIn = new Fade(cue, Math.min(inMs / 1000.0, 0.5 * sec), (int) v[6]);
                    }
                } else if (SONG.equals(kind) && SONG.equals(kinds.get(last)) && !talk) {   // crossfade song to song
                    double overlap = Math.min(fade, Math.min(0.25 * before, 0.25 * sec));
                    if (overlap > 0) {
                        start = began + before - overlap;
                        fadeOut = new Fade(before - overlap, overlap, 1);
                        fadeIn = new Fade(0.0, overlap, 1);
                    }
                }
                if (start >= DAY) return false;
                fout.set(last, fadeOut);
                plays.set(last, play);
            }
            starts.add(start);
            kinds.add(kind);
            tracks.add(tr);
            fin.add(fadeIn);
            fout.add(null);
            plays.add(sec);
            return true;
        }

        Day done() {
            Day out = new Day(starts.size());
            for (int i = 0; i < starts.size(); i++) {
                out.starts[i] = starts.get(i);
                out.kinds[i] = kinds.get(i);
                out.tracks[i] = tracks.get(i);
                out.fadeIn[i] = fin.get(i);
                out.fadeOut[i] = fout.get(i);
                out.play[i] = plays.get(i);
            }
            return out;
        }
    }

    private final String name;
    private final List<Track> songs;
    private final List<Track> jingles;
    private final Map<Integer, Day> days = new HashMap<>();
    private final double fade;
    private final boolean talk;                          // items that are whole talk shows: never faded
    private List<Track> toads = Collections.emptyList();  // the DJ's lines into an advert break
    private List<Track> tonews = Collections.emptyList(); // ... and into the news
    private Rules rules;                                  // null: songs and jingles
    private boolean extras;
    private final Map<String, List<Entry>> pools = new HashMap<>();
    private final Set<String> passed = new HashSet<>();   // kinds that are stepped over
    private final Set<String> hosted = new HashSet<>();   // songs of a night show: it has a host of its own
    private String zone = "";                             // the time zone the days were calculated for
    private List<Track> beds = Collections.emptyList();   // the music that lies under the DJ's talk

    void setBeds(List<Track> music) {
        beds = sorted(music);
    }

    /** The music bed under an item of itemsFrom(), or null. The same for everybody at the same second. */
    public Track bedOf(Item item) {
        if (beds.isEmpty() || !item.track.bed || !(TALK.equals(item.kind) || JINGLE.equals(item.kind))) return null;
        return beds.get((int) (h(name, "bed", (long) item.start, item.track.key) % beds.size()));
    }

    /** The row of the mixing rules for the end of a `before` item and an `after` item, or null. */
    private long[] mixing(int d, int step, String before, String after) {
        Map<String, long[][]> by = rules.mix.get(before);
        if (by == null) return null;
        long[][] found = by.get(after);
        if (found == null) found = by.get("");
        if (found == null || found.length == 0) return null;
        long total = 0;
        for (long[] v : found) total += v[0];
        long at = h(name, d, "mix", step) % total;
        for (long[] v : found) {
            at -= v[0];
            if (at < 0) return v;
        }
        return found[found.length - 1];
    }

    public Schedule(String name, List<Track> songs, List<Track> jingles) {
        this(name, songs, jingles, FADE, false);
    }

    /** `fade`: seconds of crossfade between two songs (0 = none); `talk`: the items are talk shows. */
    public Schedule(String name, List<Track> songs, List<Track> jingles, double fade, boolean talk) {
        this.fade = Math.min(FADE_MAX, Math.max(0.0, fade));
        this.talk = talk;
        this.name = name;
        this.songs = sorted(songs);
        this.jingles = sorted(jingles);
    }

    private static List<Track> sorted(List<Track> list) {
        List<Track> out = new ArrayList<>(list);
        Collections.sort(out, (a, b) -> a.key.compareTo(b.key));
        return out;
    }

    void setLinesIntoAds(List<Track> lines) {
        toads = lines;
    }

    void setLinesIntoNews(List<Track> lines) {
        tonews = lines;
    }

    /**
     * The programme follows the game's rules (set before the first use). `tracks`: the station's recordings by key;
     * `bulletins`: the news it plays (empty: none); `extras`: with adverts, news and talk, else songs and jingles only
     * (talk radio is talk in both; there the adverts are stepped over without them).
     */
    void setRules(Rules rules, Map<String, Track> tracks, List<Track> bulletins, boolean extras) {
        this.rules = rules;
        this.extras = extras;
        pools.clear();
        for (Map.Entry<String, List<String>> pool : rules.pools.entrySet()) {
            String category = pool.getKey();
            List<Entry> own = new ArrayList<>();
            for (String key : pool.getValue()) {
                Track t = tracks.get(key);
                if (t == null) continue;
                String kind = SONG_CATEGORIES.contains(category) ? SONG : category.equals("advert") || rules.ads.contains(key) ? AD
                        : TALK_CATEGORIES.contains(category) || t.sec > SPOKEN_FROM ? TALK : JINGLE;
                if (!extras && !rules.talkRadio && !SONG.equals(kind) && !JINGLE.equals(kind)) continue;
                own.add(new Entry(kind, t));
            }
            if (!own.isEmpty()) pools.put(category, own);
        }
        if (!bulletins.isEmpty() && (extras || rules.talkRadio)) {
            List<Entry> own = new ArrayList<>();
            for (Track t : sorted(bulletins)) own.add(new Entry(NEWS, t));
            pools.put("news", own);
        }
        passed.clear();
        if (!extras && rules.talkRadio) passed.add(AD);
        hosted.clear();
        if (rules.pools.containsKey("showintro_night")) {
            for (String category : new String[]{"music_night", "music_night2"}) {
                List<String> keys = rules.pools.get(category);
                if (keys != null) hosted.addAll(keys);
            }
        }
    }

    /** The hour of the phone's clock as the curves are asked for it, in half hours (see hour_of in awc_radio.py). */
    static double hourOf(double now) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis((long) Math.floor(now) * 1000L);
        return c.get(java.util.Calendar.HOUR_OF_DAY) + (c.get(java.util.Calendar.MINUTE) >= 30 ? 0.5 : 0.0);
    }

    /**
     * What the DJ says over an item of itemsFrom() (the same as voices() in awc_radio.py): over a song in its marked intro
     * (every second time) and outro (every third time); before an advert break or the news the line that leads into it
     * replaces the outro.
     */
    public List<Voice> voices(Item item) {
        List<Voice> out = new ArrayList<>();
        Track tr = item.track;
        if (!SONG.equals(item.kind) || (tr.intro == null && tr.outro == null)) return out;
        long h = h(name, "dj", (long) item.start, tr.key);
        double length = item.end - item.start;
        Window window = tr.intro;
        if (window != null && h % 100 < DJ_INTRO) {
            Track line = window.lines.get((int) ((h >> 8) % window.lines.size()));
            double in = item.fadeIn == null ? 0.0 : item.fadeIn.at + item.fadeIn.len;
            if (window.from >= in && window.from + line.sec <= length) out.add(new Voice(window.from, line));
        }
        window = tr.outro;
        if (window != null) {
            List<Track> lines = window.lines;
            int chance = DJ_OUTRO;
            if (extras && !hosted.contains(tr.key)) {
                String following = itemsFrom(item.start + 0.001, 2).get(1).kind;
                List<Track> lead = AD.equals(following) ? toads : NEWS.equals(following) ? tonews : Collections.<Track>emptyList();
                List<Track> fit = new ArrayList<>();
                for (Track t : lead) if (t.sec <= window.to - window.from + 0.6) fit.add(t);
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
    private List<Track> shuffled(List<Track> list, int d, int pass) {
        final Map<Track, Long> hashes = new HashMap<>();
        for (Track t : list) hashes.put(t, h(name, d, pass, t.key));
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
        String here = java.util.TimeZone.getDefault().getID();
        if (!here.equals(zone)) {                              // another time zone, other hours
            days.clear();
            zone = here;
        }
        Day cached = days.get(d);
        if (cached != null) return cached;
        Day out = rules == null ? plainDay(d) : ruledDay(d);
        if (days.size() > 4) days.clear();
        days.put(d, out);
        return out;
    }

    private Day plainDay(int d) {
        Filling day = new Filling();
        List<Track> deck = new ArrayList<>();
        int npass = 0, jn = 0, count = 0;
        Track lastSong = null, lastJingle = null;
        int target = 2 + (int) (h(name, d, "target", 0) % 3);
        while (true) {
            Track tr;
            String kind;
            if (!jingles.isEmpty() && count >= target) {
                jn++;
                count = 0;
                target = 2 + (int) (h(name, d, "target", jn) % 3);
                List<Track> pool = new ArrayList<>();
                for (Track j : jingles) if (j != lastJingle) pool.add(j);
                if (pool.isEmpty()) pool = jingles;
                tr = pool.get((int) (h(name, d, "jingle", jn) % pool.size()));
                lastJingle = tr;
                kind = JINGLE;
            } else {
                if (deck.isEmpty()) {
                    npass++;
                    deck = shuffled(songs, d, npass);
                    swapRepeat(deck, lastSong);                                       // no repeat across two passes
                }
                tr = deck.remove(deck.size() - 1);
                lastSong = tr;
                count++;
                kind = SONG;
            }
            if (!day.add(kind, tr, d, "", "")) return day.done();
        }
    }

    /** The rows that are allowed now, with their weights. */
    private List<Object[]> weigh(List<Row> rows, String current, int run, Map<String, Double> heard, double now, double hour) {
        List<Object[]> found = new ArrayList<>();
        if (rows == null) return found;
        for (Row row : rows) {
            if (!pools.containsKey(row.next)) continue;
            double weight = 1.0;
            for (Factor f : row.factors) {
                double value;
                if (f.what == 0) {
                    value = f.number;
                } else if (f.what == 'h') {
                    value = f.at(hour);
                } else if (f.what == 's') {                       // seconds since it was last heard
                    Double last = heard.get(f.category);
                    value = f.at(now - (last == null ? -1e9 : last));
                } else {
                    value = f.at(f.category.equals(current) ? run : 0);
                }
                weight *= value;
                if (weight <= 0) break;
            }
            if (weight > 0) found.add(new Object[]{row.next, weight});
        }
        return found;
    }

    /** The category that follows, as the rules weigh them at second `now` of the day. */
    private String nextCategory(int d, int step, String current, int run, Map<String, Double> heard, double now) {
        double hour = hourOf(d * DAY + now);
        List<Object[]> found = weigh(current.isEmpty() ? null : rules.table.get(current), current, run, heard, now, hour);
        if (found.isEmpty()) found = weigh(rules.table.get(""), current, run, heard, now, hour);
        if (found.isEmpty()) {                                     // nothing is allowed: music (else whatever there is)
            for (String category : new String[]{"music", "music_night", "music_dj2"}) {
                if (pools.containsKey(category)) return category;
            }
            List<String> ordered = new ArrayList<>(pools.keySet());
            Collections.sort(ordered);
            return ordered.get((int) (h(name, d, "any", step) % ordered.size()));
        }
        double total = 0;
        for (Object[] f : found) total += (Double) f[1];
        double at = h(name, d, "next", step) / 4294967296.0 * total;
        for (Object[] f : found) {
            at -= (Double) f[1];
            if (at < 0) return (String) f[0];
        }
        return (String) found.get(found.size() - 1)[0];
    }

    private Day ruledDay(int d) {
        Filling day = new Filling();
        Map<String, Double> heard = new HashMap<>();              // category -> second of the day at which it last ended
        for (List<Row> rows : rules.table.values()) {
            for (Row row : rows) {
                for (Factor f : row.factors) {                     // the day begins in the middle of a programme
                    if (f.what == 's') heard.put(f.category, -(double) (h(name, d, "heard", f.category) % 3600));
                }
            }
        }
        Map<String, ArrayList<String>> recent = new HashMap<>();
        Map<String, Long> place = new HashMap<>();
        String current = "", previous = "";
        String aired = "";                                        // the category of the last item that went on air
        int run = 0, step = 0, stepped = 0;
        double now = 0.0;
        while (!pools.isEmpty()) {
            String category = nextCategory(d, step, current, run, heard, now);
            List<Entry> pool = pools.get(category);
            Entry chosen;
            if (rules.sequence.contains(category)) {
                Long at = place.get(category);
                if (at == null) at = h(name, d, "order", category) % pool.size();
                chosen = pool.get((int) (at % pool.size()));
                place.put(category, at + 1);
            } else {
                Integer given = rules.history.get(category);
                int keep = Math.min(given != null ? given : pool.size() * 2 / 3, pool.size() - 1);
                ArrayList<String> last = recent.get(category);
                if (last == null) recent.put(category, last = new ArrayList<>());
                List<Entry> free = new ArrayList<>(), other = new ArrayList<>();
                Set<String> just = new HashSet<>(last);
                for (Entry e : pool) {
                    if (just.contains(e.track.key)) continue;
                    free.add(e);
                    if (!e.track.key.equals(previous)) other.add(e);
                }
                if (!other.isEmpty()) free = other;
                chosen = free.get((int) (h(name, d, "item", category, step) % free.size()));
                last.add(chosen.track.key);
                while (last.size() > Math.max(0, keep)) last.remove(0);
            }
            step++;
            if (passed.contains(chosen.kind) && stepped < 50) {   // talk radio without adverts: stepped over
                stepped++;
            } else {
                stepped = 0;
                if (!day.add(chosen.kind, chosen.track, d, aired, category)) break;
                now = day.starts.get(day.starts.size() - 1) + Math.max(1.0, chosen.track.sec);
                previous = chosen.track.key;
                aired = category;
                if (now >= DAY) break;
            }
            run = category.equals(current) ? run + 1 : 1;
            current = category;
            heard.put(category, now);
        }
        return day.done();
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
            double end = Math.min(day.starts[i] + day.play[i], DAY);
            out.add(new Item(d * DAY + day.starts[i], d * DAY + end, day.kinds[i], tr, day.fadeIn[i], day.fadeOut[i]));
            i++;
        }
        return out;
    }
}
