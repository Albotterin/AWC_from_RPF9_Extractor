package org.awctool.radio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The search in the score: the sub groups (a mission, an area) whose name holds every word that is searched, and the
 * pieces whose title and sub group together hold them. Compared as in the On-Demand search (see Music.plain). Pure Java
 * (tested on the PC).
 */
final class ScoreSearch {
    /** A sub group of a group: its first and its end in the group's list. */
    static final class Sub {
        final Library.Station group;
        final String name;
        final int from, to;
        private final String words;

        private Sub(Library.Station group, String name, int from, int to) {
            this.group = group;
            this.name = name;
            this.from = from;
            this.to = to;
            this.words = " " + Music.plain(name) + " ";
        }
    }

    /** A piece: its place in the list of its group. */
    static final class Piece {
        final Library.Station group;
        final int index;
        private final String title, words;

        private Piece(Library.Station group, int index) {
            this.group = group;
            this.index = index;
            Schedule.Track t = group.songs.get(index);
            this.title = " " + Music.plain(t.title) + " ";
            this.words = title + Music.plain(t.artist) + " ";
        }

        Schedule.Track track() {
            return group.songs.get(index);
        }
    }

    static final class Found {
        final List<Sub> subs;
        final List<Piece> pieces;

        private Found(List<Sub> subs, List<Piece> pieces) {
            this.subs = Collections.unmodifiableList(subs);
            this.pieces = Collections.unmodifiableList(pieces);
        }
    }

    private final List<Sub> subs = new ArrayList<>();
    private final List<Piece> pieces = new ArrayList<>();

    ScoreSearch(List<Library.Station> scores) {
        for (Library.Station g : scores) {
            int from = 0;
            for (int i = 0; i < g.songs.size(); i++) {
                pieces.add(new Piece(g, i));
                // the pieces of a sub group follow each other in the pack
                if (i + 1 == g.songs.size() || !g.songs.get(i + 1).artist.equals(g.songs.get(i).artist)) {
                    subs.add(new Sub(g, g.songs.get(i).artist, from, i + 1));
                    from = i + 1;
                }
            }
        }
    }

    private static boolean matches(String words, String[] wanted) {
        for (String w : wanted) if (!words.contains(w)) return false;
        return true;
    }

    /** What begins with the searched words comes first (sub groups by name, pieces by title), else the pack's order. */
    Found find(String query) {
        String q = Music.plain(query);
        List<Sub> subStart = new ArrayList<>(), subOther = new ArrayList<>();
        List<Piece> start = new ArrayList<>(), other = new ArrayList<>();
        if (!q.isEmpty()) {
            String[] words = q.split(" ");
            for (Sub s : subs) {
                if (matches(s.words, words)) (s.words.startsWith(" " + q) ? subStart : subOther).add(s);
            }
            for (Piece p : pieces) {
                if (matches(p.words, words)) (p.title.startsWith(" " + q) ? start : other).add(p);
            }
        }
        subStart.addAll(subOther);
        start.addAll(other);
        return new Found(subStart, start);
    }
}
