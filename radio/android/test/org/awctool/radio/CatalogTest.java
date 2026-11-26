package org.awctool.radio;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the real Pack and Catalog code on a folder of station packs (no Android needed).
 *
 *   java CatalogTest synthetic <folder A> <folder B>   the folders written by run_tests.py: merging, updating, damage
 *   java CatalogTest real <folder>                     every pack of a folder must load and hold all its files
 */
public final class CatalogTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final List<File> opened = new ArrayList<>();

    private static Catalog load(File folder, Catalog old) {
        List<Catalog.Source> files = new ArrayList<>();
        File[] list = folder.listFiles();
        if (list != null) {
            for (File f : list) files.add(new Catalog.Source(f.getPath(), f.getName(), f.length(), f.lastModified()));
        }
        return Catalog.load(files, old, source -> {
            File f = new File(source.id);
            opened.add(f);
            FileInputStream in = new FileInputStream(f);
            try {
                return new Pack(in.getChannel(), in.getFD(), in);
            } catch (Exception e) {
                in.close();
                throw e;
            }
        });
    }

    private static String names(Catalog c) {
        StringBuilder b = new StringBuilder();
        for (Library.Station s : c.stations) b.append(s.name).append('|');
        return b.toString();
    }

    private static Library.Station station(Catalog c, String name) {
        int i = Catalog.indexOf(c.stations, name);
        check(i >= 0, "station " + name + " missing in " + names(c));
        return c.stations.get(i);
    }

    private static void synthetic(File a, File b) throws Exception {
        Catalog first = load(a, null);
        // order like the PC program: without "The", club mixes last; the newer of two Alpha packs wins; .part, .dat and a
        // half copied pack are not stations
        check(names(first).equals("Alpha|The Bravo|Charlie Talk|Clubmixes|"), "order " + names(first));
        check(station(first, "Alpha").songs.get(0).title.equals("new song"), "the pack made later wins");
        check(station(first, "Charlie Talk").talk && !station(first, "Alpha").talk, "talk flag");
        check(first.problems.size() == 1 && first.problems.get(0).startsWith("broken.radiopak: "), "problems " + first.problems);
        check(first.packCount() == 5, "packs " + first.packCount());
        for (Library.Station s : first.stations) {
            check(s.pack != null && s.programme(false, Schedule.FADE) != null, "pack and programme of " + s.name);
            check(s.pack.read(s.logo)[1] == 'P', "logo of " + s.name);
            for (Schedule.Track t : s.songs) check(s.pack.read("t/" + t.key).length > 0, "song file " + t.key);
        }

        // nothing changed: nothing is opened again (only the broken file is tried again), same list object
        opened.clear();
        Catalog again = load(a, first);
        check(again.sameAs(first) && again.stations == first.stations, "unchanged folder");
        check(opened.size() == 1 && opened.get(0).getName().equals("broken.radiopak"), "reopened " + opened);

        // a pack that is being overwritten (changed, but unreadable): the station stays as it was
        List<Catalog.Source> copying = new ArrayList<>();
        for (File f : a.listFiles()) {
            long size = f.length() - (f.getName().equals("alpha.radiopak") ? 7 : 0);
            copying.add(new Catalog.Source(f.getPath(), f.getName(), size, f.lastModified()));
        }
        Catalog during = Catalog.load(copying, first, s -> {
            throw new java.io.IOException("the pack file is incomplete");
        });
        check(names(during).equals(names(first)), "stations during a copy " + names(during));
        check(station(during, "Alpha") == station(first, "Alpha") && during.stations == first.stations, "old pack kept");
        check(during.problems.size() == 2 && !during.sameAs(first), "the problem is reported");
        first.closeUnused(during);
        check(station(during, "Alpha").pack.read(station(during, "Alpha").logo)[1] == 'P', "kept pack stays open");
        first = during;

        // folder B: Alpha updated, Bravo removed, Delta added, Charlie untouched (same file)
        File keep = new File(b, "charlie_talk.radiopak");
        List<Catalog.Source> files = new ArrayList<>();
        for (File f : b.listFiles()) files.add(new Catalog.Source(f.getName(), f.getName(), f.length(), 1000));
        List<Catalog.Source> before = new ArrayList<>();
        for (File f : a.listFiles()) {
            if (f.getName().equals(keep.getName())) before.add(new Catalog.Source(f.getName(), f.getName(), keep.length(), 1000));
        }
        Catalog.Opener inB = s -> {
            FileInputStream in = new FileInputStream(new File(b, s.name));
            return new Pack(in.getChannel(), in.getFD(), in);
        };
        Catalog one = Catalog.load(before, null, inB);
        Library.Station charlie = station(one, "Charlie Talk");
        Catalog two = Catalog.load(files, one, inB);
        check(names(two).equals("Alpha|Charlie Talk|Delta|"), "after the update " + names(two));
        check(!two.sameAs(one), "change is noticed");
        check(station(two, "Charlie Talk") == charlie, "an untouched pack keeps its station (the radio plays on)");
        check(station(two, "Alpha").songs.get(0).title.equals("updated song"), "updated pack");
        one.closeUnused(two);
        check(charlie.pack.read(charlie.logo).length > 0, "a pack that is still used stays open");
        two.closeUnused(null);
        boolean closed = false;
        try {
            charlie.pack.read(charlie.logo);
        } catch (Exception e) {
            closed = true;
        }
        check(closed, "closeUnused(null) closes everything");
        check(Catalog.empty().stations.isEmpty() && Catalog.isPack("X.RadioPak") && Catalog.isPack("a.PodPak") && !Catalog.isPack("x.dat")
                && !Catalog.isPack("x.radiopak.part"), "file names");
    }

    /** Score packs: their groups in the fixed order, apart from stations and podcasts, the newer pack wins. */
    private static void score(File folder) throws Exception {
        Catalog c = load(folder, null);
        check(c.problems.isEmpty(), "problems " + c.problems);
        check(c.stations.isEmpty() && c.podcasts.isEmpty() && c.scores.size() == 2, "only the score: " + c.scores.size());
        check(c.scores.get(0).name.equals("score:openworld") && c.scores.get(1).name.equals("score:mission"), "order of the groups");
        Library.Station m = c.onDemand("score:mission");
        check(m != null && m.podcast && "mission".equals(m.score) && c.onDemand("score:none") == null, "on demand");
        check(m.songs.size() == 2 && m.songs.get(0).title.equals("new piece") && m.songs.get(0).artist.equals("ROB Bank"), "pieces");
        for (Schedule.Track t : m.songs) check(m.pack.read("t/" + t.key).length > 0, "file " + t.key);
        check(Catalog.isPack("Score_Missions.ScorePak") && !Catalog.isPack("a.scorepak.part"), "score file names");
        c.closeUnused(null);
    }

    private static void real(File folder) throws Exception {
        Catalog c = load(folder, null);
        check(c.problems.isEmpty(), "problems " + c.problems);
        check(!c.stations.isEmpty(), "no station");
        for (Library.Station p : c.podcasts) {
            check(p.podcast && !p.songs.isEmpty(), "podcast " + p.name);
            check(Catalog.indexOf(c.stations, p.name) < 0, "a podcast is no station: " + p.name);
            check(p.logo.isEmpty() || p.pack.read(p.logo)[1] == 'P', "cover of " + p.name);
            for (Schedule.Track t : p.songs) {
                Pack.Entry e = p.pack.entry("t/" + t.key);
                check(e != null && e.length > 100000 && t.sec > 60, p.name + ": file of " + t.key);
            }
            System.out.println("  podcast " + p.name + (p.logo.isEmpty() ? " [no cover]" : "") + ": " + p.songs.size() + " episodes");
        }
        for (Library.Station g : c.scores) {
            check(g.score != null && !g.songs.isEmpty(), "score group " + g.name);
            double seconds = 0;
            for (Schedule.Track t : g.songs) {
                Pack.Entry e = g.pack.entry("t/" + t.key);
                // quiet pieces are small (variable bit rate); a file far smaller than that lost its audio
                check(e != null && e.length > 20000 && e.length > t.sec * 1500 && t.sec >= 1 && !t.title.isEmpty() && !t.artist.isEmpty(),
                        g.name + ": " + t.title);
                seconds += t.sec;
            }
            System.out.println("  " + g.name + ": " + g.songs.size() + " pieces, " + Math.round(seconds / 360) / 10.0 + " h");
        }
        long items = 0, djWindows = 0;
        for (Library.Station s : c.stations) {
            check(s.logo.isEmpty() || s.pack.read(s.logo)[1] == 'P', "logo of " + s.name);      // not every station has one
            List<Schedule.Track> all = new ArrayList<>(s.songs);
            all.addAll(s.jingles);
            all.addAll(s.ads);
            all.addAll(s.toads);
            int talked = 0;
            for (Schedule.Track t : s.songs) {                       // every line the DJ may say is in the pack
                for (Schedule.Window w : new Schedule.Window[]{t.intro, t.outro}) {
                    if (w == null) continue;
                    check(w.to > w.from && !w.lines.isEmpty() && w.from >= 0 && w.from < t.sec, "window of " + t.title);
                    all.addAll(w.lines);
                    talked++;
                }
            }
            djWindows += talked;
            Schedule plain = s.programme(false, Schedule.FADE);
            check(plain == s.programme(false, Schedule.FADE) && (s.programme(true, Schedule.FADE) == plain) == s.ads.isEmpty()
                    && (s.programme(false, 0) == plain) == s.mixedAsInGame(), "programmes of " + s.name);
            for (Schedule.Track t : all) {
                Pack.Entry e = s.pack.entry("t/" + t.key);
                check(e != null && e.length > 500, s.name + ": file of " + t.key);
                items++;
            }
            check(plain.itemsFrom(System.currentTimeMillis() / 1000.0, 5).size() == 5, "programme of " + s.name);
            System.out.println("  " + s.name + (s.talk ? " [talk]" : "") + (s.logo.isEmpty() ? " [no logo]" : "") + ": " + s.songs.size() + " + " + s.jingles.size() + " + " + s.ads.size() + " adverts");
        }
        System.out.println(c.stations.size() + " stations in " + c.packCount() + " packs, " + items + " recordings, "
                + djWindows + " DJ windows");
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("real")) real(new File(args[1]));
        else if (args[0].equals("score")) score(new File(args[1]));
        else synthetic(new File(args[1]), new File(args[2]));
        System.out.println("Catalog: ALL OK (" + checks + " checks)");
    }
}
