package org.awctool.radio;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintStream;

/**
 * Writes the programme of every station of a pack folder, as the app calculates it, for a set of moments, with and
 * without adverts. run_tests.py calculates the same with the PC code (awc_radio.Schedule) and compares line by line.
 *
 *   java ProgramDump <pack folder> <output file> <unix time> [<unix time> ...]
 */
public final class ProgramDump {
    // {content switches, a crossfade, another one}: the same list as in run_tests.py
    private static final int[][] CHOICES = {{20, 0, 3}, {31, 3, 8}, {0, 3, 3}, {1, 3, 3}, {26, 3, 3}, {6, 3, 3}};

    private static String fade(Schedule.Fade f) {
        return f == null ? "-" : String.format(java.util.Locale.ROOT, "%.6f,%.6f,%d", f.at, f.len, f.shape);
    }

    public static void main(String[] args) throws Exception {
        File[] files = new File(args[0]).listFiles((d, n) -> n.endsWith(".radiopak"));
        java.util.Arrays.sort(files);
        Pack news = null;
        for (File f : new File(args[0]).listFiles((d, n) -> n.endsWith(".newspak"))) {
            FileInputStream in = new FileInputStream(f);
            news = new Pack(in.getChannel(), in.getFD(), in);
        }
        try (PrintStream out = new PrintStream(args[1], "UTF-8")) {
            for (File f : files) {
                FileInputStream in = new FileInputStream(f);
                Pack pack = new Pack(in.getChannel(), in.getFD(), in);
                for (Library.Station s : pack.stations()) {
                    if (news != null) s.setNews(news.news(), news);
                    for (int a = 2; a < args.length; a++) {
                        double now = Double.parseDouble(args[a]);
                        for (int[] chosen : CHOICES) {
                            int content = chosen[0];
                            for (int fade : new int[]{chosen[1], chosen[2]}) {
                                for (Schedule.Item it : s.programme(content, fade).itemsFrom(now, 40)) {
                                    out.printf(java.util.Locale.ROOT, "%s|%d|%s%d|%s|%s|%.6f|%.6f|%s|%s%n", s.name, a - 2,
                                            "c" + content + "f", fade, it.kind, it.track.key,
                                            it.start, it.end, fade(it.fadeIn), fade(it.fadeOut));
                                    for (double[] span : s.programme(content, fade).talkOver(it, true)) {
                                        out.printf(java.util.Locale.ROOT, "%s|%d|%s%d|over|%s|%.6f|%.6f|%.6f%n", s.name, a - 2,
                                                "c" + content + "f", fade, it.track.key, it.start, span[0], span[1]);
                                    }
                                    Schedule.Track bed = s.programme(content, fade).bedOf(it);
                                    if (bed != null) {
                                        out.printf(java.util.Locale.ROOT, "%s|%d|%s%d|bed|%s|%.6f%n", s.name, a - 2,
                                                "c" + content + "f", fade, bed.key, it.start);
                                    }
                                    for (Schedule.Voice v : s.programme(content, fade).voices(it)) {
                                        out.printf(java.util.Locale.ROOT, "%s|%d|%s%d|voice|%s|%.6f|%.6f%n", s.name, a - 2,
                                                "c" + content + "f", fade, v.line.key, it.start, v.at);
                                    }
                                }
                            }
                        }
                    }
                }
                pack.close();
            }
        }
    }
}
