package org.awctool.radio;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The songs of all packs as a music app sees them: a playlist for every station (and those that playlist packs bring,
 * like the album of the game), the artists, a search, and the queues that play them on demand. Only real songs count:
 * no talk, no DJ mixes, nothing without a name. Pure Java (tested on the PC).
 *
 * A queue is a Library.Station that is played like a podcast. It is named by what it holds, so that it can be built
 * again after new packs have come (or after a restart):
 * <pre>
 *   music:all              every song                 music:artist:&lt;name&gt;   the songs of an artist
 *   music:pl:&lt;name&gt;        a playlist                 music:search:&lt;words&gt;  what a search found
 * </pre>
 * followed, when shuffled, by SHUFFLED + a number (the order depends only on it) and optionally ":" + the key of the
 * song that comes first.
 */
final class Music {
    static final String PREFIX = "music:", ALL = PREFIX + "all", LIST = PREFIX + "pl:", ARTIST = PREFIX + "artist:",
            SEARCH = PREFIX + "search:";
    static final String SHUFFLED = "\u0001s";
    static final double LONGEST_SONG = 1200;                 // seconds; anything longer is a mix
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern OTHERS = Pattern.compile("[^\\p{L}\\p{Nd}]+");
    private static final Pattern FEATURING = Pattern.compile("(?i)\\s+(?:ft\\.?|feat\\.?|featuring)\\s+");

    /** A song and where it comes from: the station (or playlist) whose pack holds it and whose picture it wears. */
    static final class Song {
        final Schedule.Track track;
        final Library.Station from;
        private final String words;                          // what a search looks through

        Song(Schedule.Track track, Library.Station from) {
            this.track = track;
            this.from = from;
            this.words = " " + plain(track.title) + " " + plain(track.artist) + " ";
        }

        /** Two recordings of the same name count as the same song. */
        String name() {
            return plain(track.title) + "|" + plain(track.artist);
        }
    }

    /** A list of songs with a name and a picture: a station's songs, or a playlist that a pack brought. */
    static final class Playlist {
        final String name;
        final Library.Station cover;
        final boolean ordered;                               // its order is its own (an album), not by artist
        final List<Song> songs;

        Playlist(String name, Library.Station cover, boolean ordered, List<Song> songs) {
            this.name = name;
            this.cover = cover;
            this.ordered = ordered;
            this.songs = Collections.unmodifiableList(songs);
        }
    }

    static final class Artist {
        final String name;
        final List<Song> songs;

        Artist(String name, List<Song> songs) {
            this.name = name;
            this.songs = Collections.unmodifiableList(songs);
        }
    }

    /** The playlists that came with a pack of their own first, then the stations in their order. */
    final List<Playlist> playlists;
    /** Every song once, by title. */
    final List<Song> songs;
    /** By name. */
    final List<Artist> artists;
    private final Map<String, Playlist> byName = new HashMap<>();
    private final Map<String, Artist> byArtist = new HashMap<>();
    private final Map<String, Library.Station> queues = new HashMap<>();

    Music(List<Library.Station> stations, List<Library.Station> extra) {
        List<Playlist> lists = new ArrayList<>();
        Map<String, Song> once = new LinkedHashMap<>();
        for (Library.Station st : stations) {
            if (st.talk || (st.rules != null && st.rules.talkRadio)) continue;
            List<Song> own = songsOf(st);
            if (own.isEmpty()) continue;
            Collections.sort(own, BY_ARTIST);
            lists.add(new Playlist(st.name, st, false, own));
            for (Song s : own) if (!once.containsKey(s.name())) once.put(s.name(), s);
        }
        List<Playlist> first = new ArrayList<>();
        for (Library.Station st : extra) {
            List<Song> own = songsOf(st);
            if (own.isEmpty()) continue;
            first.add(new Playlist(st.name, st, true, own));
            for (Song s : own) if (!once.containsKey(s.name())) once.put(s.name(), s);
        }
        lists.addAll(0, first);
        playlists = Collections.unmodifiableList(lists);
        for (Playlist p : playlists) if (!byName.containsKey(p.name)) byName.put(p.name, p);

        List<Song> all = new ArrayList<>(once.values());
        Collections.sort(all, BY_TITLE);
        songs = Collections.unmodifiableList(all);

        // an artist is a name as it is credited: the main one, and whoever is featured
        Map<String, String> spelled = new LinkedHashMap<>();
        for (Song s : all) {
            for (String credit : credits(s.track.artist)) {
                String key = plain(credit);
                if (!key.isEmpty() && !spelled.containsKey(key)) spelled.put(key, credit);
            }
        }
        List<Artist> names = new ArrayList<>();
        for (Map.Entry<String, String> e : spelled.entrySet()) {
            String needle = " " + e.getKey() + " ";
            List<Song> own = new ArrayList<>();
            for (Song s : all) if (s.words.contains(needle)) own.add(s);      // also where the name only helps out
            if (own.isEmpty()) continue;
            Artist a = new Artist(e.getValue(), own);
            names.add(a);
            byArtist.put(a.name, a);
        }
        Collections.sort(names, (a, b) -> sortName(a.name).compareTo(sortName(b.name)));
        artists = Collections.unmodifiableList(names);
    }

    private static final java.util.Comparator<Song> BY_TITLE = (a, b) -> {
        int c = sortName(a.track.title).compareTo(sortName(b.track.title));
        return c != 0 ? c : sortName(a.track.artist).compareTo(sortName(b.track.artist));
    };
    private static final java.util.Comparator<Song> BY_ARTIST = (a, b) -> {
        int c = sortName(a.track.artist).compareTo(sortName(b.track.artist));
        return c != 0 ? c : sortName(a.track.title).compareTo(sortName(b.track.title));
    };

    /** Whether a recording of a pack is a song one can look for: named, and no mix. */
    static boolean isSong(Schedule.Track t) {
        return t.partStart == null && t.sec <= LONGEST_SONG && !t.title.isEmpty() && !t.artist.isEmpty()
                && !t.title.equals("Unknown track");
    }

    private static List<Song> songsOf(Library.Station st) {
        List<Song> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Schedule.Track t : st.songs) {
            if (!isSong(t)) continue;
            Song s = new Song(t, st);
            if (seen.add(s.name())) out.add(s);
        }
        return out;
    }

    /** "A ft. B" names A and B; "A & B" is one credit, as bands carry such names too. */
    static List<String> credits(String artist) {
        List<String> out = new ArrayList<>();
        for (String part : FEATURING.split(artist)) {
            String name = part.trim();
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /** Lower case without accents and punctuation: how names are compared. */
    static String plain(String s) {
        String t = Normalizer.normalize(s.replace('$', 's'), Normalizer.Form.NFD);
        t = MARKS.matcher(t).replaceAll("").replace("'", "").replace("’", "").toLowerCase(Locale.ROOT);
        String words = OTHERS.matcher(t).replaceAll(" ").trim();
        return words.isEmpty() ? t.trim() : words;           // a band called "!!!" keeps its name
    }

    private static String sortName(String s) {
        String p = plain(s);
        return p.startsWith("the ") ? p.substring(4) : p;
    }

    private static boolean matches(String words, String[] wanted) {
        for (String w : wanted) if (!words.contains(w)) return false;
        return true;
    }

    /** The songs whose title or artist holds every word; those whose title begins with the first one come first. */
    List<Song> search(String query) {
        String q = plain(query);
        List<Song> start = new ArrayList<>(), other = new ArrayList<>();
        if (q.isEmpty()) return start;
        String[] words = q.split(" ");
        for (Song s : songs) {
            if (!matches(s.words, words)) continue;
            (s.words.startsWith(" " + q) ? start : other).add(s);
        }
        start.addAll(other);
        return start;
    }

    List<Artist> searchArtists(String query) {
        String q = plain(query);
        List<Artist> out = new ArrayList<>();
        if (q.isEmpty()) return out;
        String[] words = q.split(" ");
        for (Artist a : artists) if (matches(" " + plain(a.name) + " ", words)) out.add(a);
        return out;
    }

    Playlist playlist(String name) {
        return byName.get(name);
    }

    Artist artist(String name) {
        return byArtist.get(name);
    }

    /** The queue's name without the shuffling. */
    static String listOf(String queue) {
        int at = queue.indexOf(SHUFFLED);
        return at < 0 ? queue : queue.substring(0, at);
    }

    static boolean isShuffled(String queue) {
        return queue.indexOf(SHUFFLED) >= 0;
    }

    /** The name of a shuffled queue; `first` (a song's key, or null) comes at its beginning. */
    static String shuffled(String queue, long seed, String first) {
        return listOf(queue) + SHUFFLED + seed + (first == null ? "" : ":" + first);
    }

    /** The songs of a list ("music:..." without shuffling), in their own order; null for an unknown one. */
    List<Song> songsIn(String list) {
        if (list.equals(ALL)) return songs;
        if (list.startsWith(LIST)) {
            Playlist p = playlist(list.substring(LIST.length()));
            return p == null ? null : p.songs;
        }
        if (list.startsWith(ARTIST)) {
            Artist a = artist(list.substring(ARTIST.length()));
            return a == null ? null : a.songs;
        }
        if (list.startsWith(SEARCH)) return search(list.substring(SEARCH.length()));
        return null;
    }

    /** The queue of that name as a station that is played on demand, or null if it would hold nothing. */
    synchronized Library.Station queue(String name) {
        Library.Station made = queues.get(name);
        if (made != null) return made;
        List<Song> list = songsIn(listOf(name));
        if (list == null || list.isEmpty()) return null;
        List<Song> order = new ArrayList<>(list);
        if (isShuffled(name)) {
            String rest = name.substring(name.indexOf(SHUFFLED) + SHUFFLED.length());
            int colon = rest.indexOf(':');
            long seed;
            try {
                seed = Long.parseLong(colon < 0 ? rest : rest.substring(0, colon));
            } catch (NumberFormatException e) {
                seed = 0;
            }
            Collections.shuffle(order, new Random(seed));
            if (colon >= 0) {
                String key = rest.substring(colon + 1);
                for (int i = 0; i < order.size(); i++) {
                    if (order.get(i).track.key.equals(key)) {
                        order.add(0, order.remove(i));
                        break;
                    }
                }
            }
        }
        made = Library.Station.ofQueue(name, order);
        queues.put(name, made);
        return made;
    }
}
