package org.awctool.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All packs of the folder - stations (.radiopak) and podcasts (.podpak) - seen as one list of stations and one of
 * podcasts. Pure Java (no Android), see PackStore.
 *
 * Every pack normally holds one station. If two files hold a station of the same name, the pack that was made later
 * wins, so an update can simply be copied next to (or over) the old file.
 */
final class Catalog {
    static final String EXTENSION = ".radiopak", PODCAST_EXTENSION = ".podpak";
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
    /** "file name: why it could not be read" */
    final List<String> problems;
    private final Map<String, Pack> packs;                    // by Source.key()

    private Catalog(List<Library.Station> stations, List<Library.Station> podcasts, Map<String, Pack> packs, List<String> problems) {
        this.stations = stations;
        this.podcasts = podcasts;
        this.packs = packs;
        this.problems = Collections.unmodifiableList(problems);
    }

    static Catalog empty() {
        return new Catalog(Collections.emptyList(), Collections.emptyList(), new LinkedHashMap<>(), new ArrayList<>());
    }

    static boolean isPack(String fileName) {
        if (fileName == null) return false;
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(EXTENSION) || n.endsWith(PODCAST_EXTENSION);
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
        Map<String, Library.Station> byName = new HashMap<>(), podByName = new HashMap<>();
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
        }
        return new Catalog(inOrder(byName, old == null ? null : old.stations), inOrder(podByName, old == null ? null : old.podcasts),
                packs, problems);
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
            int c = sortKey(a.name).compareTo(sortKey(b.name));
            return c != 0 ? c : a.name.compareTo(b.name);
        });
        return old != null && old.equals(list) ? old : Collections.unmodifiableList(list);
    }

    /** The same station objects in the same order, and the same problems: nothing to tell anybody. */
    boolean sameAs(Catalog other) {
        // Station has no equals(): the lists are equal when they hold the same objects
        return other != null && stations.equals(other.stations) && podcasts.equals(other.podcasts) && problems.equals(other.problems);
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
