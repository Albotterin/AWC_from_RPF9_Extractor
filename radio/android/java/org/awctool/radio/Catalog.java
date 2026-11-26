package org.awctool.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All packs of the folder - stations (.radiopak), podcasts (.podpak), the news (.newspak) and the score (.scorepak) -
 * seen as one list of stations (those that play the news have them from the news pack), one of podcasts and one of the
 * score's groups. Pure Java (no Android), see PackStore.
 *
 * Every pack normally holds one station. If two files hold a station of the same name, the pack that was made later
 * wins, so an update can simply be copied next to (or over) the old file.
 */
final class Catalog {
    static final String EXTENSION = ".radiopak", PODCAST_EXTENSION = ".podpak", SCORE_EXTENSION = ".scorepak";
    static final String NEWS_EXTENSION = ".newspak";
    private static final String[] SCORE_ORDER = {"openworld", "mission", "compound", "activity", "places"};
    static final String CLUB_STATION = "Clubmixes";

    /** A pack file of the folder; a file whose size or time changed counts as a new one. */
    static final class Source {
        final String id, name;
        final long size, modified;

        Source(String id, String name, long size, long modified) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.modified = modified;
        }

        String key() {
            return id + "|" + size + "|" + modified;
        }
    }

    interface Opener {
        Pack open(Source source) throws Exception;
    }

    final List<Library.Station> stations;
    final List<Library.Station> podcasts;
    final List<Library.Station> scores;
    /** "file name: why it could not be read" */
    final List<String> problems;
    private final Map<String, Pack> packs;                    // by Source.key()
    private Pack newsPack;                                    // the news the stations play, or null

    private Catalog(List<Library.Station> stations, List<Library.Station> podcasts, List<Library.Station> scores,
                    Map<String, Pack> packs, List<String> problems) {
        this.stations = stations;
        this.podcasts = podcasts;
        this.scores = scores;
        this.packs = packs;
        this.problems = Collections.unmodifiableList(problems);
    }

    static Catalog empty() {
        return new Catalog(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), new LinkedHashMap<>(),
                new ArrayList<>());
    }

    static boolean isPack(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(EXTENSION) || n.endsWith(PODCAST_EXTENSION) || n.endsWith(SCORE_EXTENSION)
                || n.endsWith(NEWS_EXTENSION);
    }

    /**
     * Reads the pack files. Packs that did not change since `old` are taken over (open, with their stations), and if the
     * stations are all the same, so is the list object.
     */
    static Catalog load(List<Source> files, Catalog old, Opener opener) {
        List<Source> sorted = new ArrayList<>(files);
        Collections.sort(sorted, (a, b) -> a.name.compareTo(b.name));
        Map<String, Pack> packs = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        Map<String, Library.Station> byName = new HashMap<>(), podByName = new HashMap<>(), scoreByName = new HashMap<>();
        Pack news = null;
        for (Source s : sorted) {
            if (!isPack(s.name)) continue;
            String key = s.key();
            Pack p = old == null ? null : old.packs.get(key);
            if (p == null) {
                try {
                    p = opener.open(s);
                } catch (Exception e) {
                    problems.add(s.name + ": " + e.getMessage());
                    // probably being overwritten just now: stay with what was read from this file before
                    key = old == null ? null : old.keyOfFile(s.id);
                    if (key == null) continue;
                    p = old.packs.get(key);
                }
            }
            packs.put(key, p);
            take(p.stations(), p, byName);
            take(p.podcasts(), p, podByName);
            take(p.scores(), p, scoreByName);
            if (!p.news().isEmpty() && (news == null || p.created.compareTo(news.created) >= 0)) news = p;
        }
        for (Library.Station st : byName.values()) {
            st.setNews(news == null ? Collections.<Schedule.Track>emptyList() : news.news(), news);
        }
        Catalog out = new Catalog(inOrder(byName, old == null ? null : old.stations),
                inOrder(podByName, old == null ? null : old.podcasts), inOrder(scoreByName, old == null ? null : old.scores),
                packs, problems);
        out.newsPack = news;
        return out;
    }

    /** A podcast or a group of the score by its name, or null: what is played on demand. */
    Library.Station onDemand(String name) {
        int i = indexOf(podcasts, name);
        if (i >= 0) return podcasts.get(i);
        i = indexOf(scores, name);
        return i < 0 ? null : scores.get(i);
    }

    private static void take(List<Library.Station> from, Pack p, Map<String, Library.Station> byName) {
        for (Library.Station st : from) {
            Library.Station other = byName.get(st.name);
            // "created" is an ISO time, so the texts compare like the times; equal: the later file name wins
            if (other == null || p.created.compareTo(other.pack.created) >= 0) byName.put(st.name, st);
        }
    }

    /** Sorted by name; the old list object if nothing in it changed (Station has no equals(): same objects). */
    private static List<Library.Station> inOrder(Map<String, Library.Station> byName, List<Library.Station> old) {
        List<Library.Station> list = new ArrayList<>(byName.values());
        Collections.sort(list, (a, b) -> {
            if (a.score != null && b.score != null && scoreRank(a.score) != scoreRank(b.score)) {
                return scoreRank(a.score) - scoreRank(b.score);
            }
            int c = sortKey(a.name).compareTo(sortKey(b.name));
            return c != 0 ? c : a.name.compareTo(b.name);
        });
        return old != null && old.equals(list) ? old : Collections.unmodifiableList(list);
    }

    private static int scoreRank(String group) {
        for (int i = 0; i < SCORE_ORDER.length; i++) {
            if (SCORE_ORDER[i].equals(group)) return i;
        }
        return SCORE_ORDER.length;
    }

    /** The same station objects in the same order, and the same problems: nothing to tell anybody. */
    boolean sameAs(Catalog other) {
        // Station has no equals(): the lists are equal when they hold the same objects
        return other != null && stations.equals(other.stations) && podcasts.equals(other.podcasts) && scores.equals(other.scores)
                && problems.equals(other.problems) && newsPack == other.newsPack;
    }

    private String keyOfFile(String id) {
        for (String key : packs.keySet()) {
            if (key.startsWith(id + "|")) return key;
        }
        return null;
    }

    int packCount() {
        return packs.size();
    }

    /** Close the packs that the newer catalog no longer uses. */
    void closeUnused(Catalog newer) {
        for (Map.Entry<String, Pack> e : packs.entrySet()) {
            if (newer == null || newer.packs.get(e.getKey()) != e.getValue()) e.getValue().close();
        }
    }

    /** The order of the PC program: by name without a leading "The", the club mixes last. */
    static String sortKey(String name) {
        if (CLUB_STATION.equals(name)) return "￿";
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("the ") ? n.substring(4) : n;
    }

    static int indexOf(List<Library.Station> stations, String name) {
        if (stations != null && name != null) {
            for (int i = 0; i < stations.size(); i++) {
                if (stations.get(i).name.equals(name)) return i;
            }
        }
        return -1;
    }
}
