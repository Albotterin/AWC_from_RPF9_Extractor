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
    public static void main(String[] args) throws Exception {
        File[] files = new File(args[0]).listFiles((d, n) -> n.endsWith(".radiopak"));
        java.util.Arrays.sort(files);
        try (PrintStream out = new PrintStream(args[1], "UTF-8")) {
            for (File f : files) {
                FileInputStream in = new FileInputStream(f);
                Pack pack = new Pack(in.getChannel(), in.getFD(), in);
                for (Library.Station s : pack.stations()) {
                    for (int a = 2; a < args.length; a++) {
                        double now = Double.parseDouble(args[a]);
                        for (boolean adverts : new boolean[]{false, true}) {
                            for (Schedule.Item it : s.programme(adverts).itemsFrom(now, 40)) {
                                out.printf(java.util.Locale.ROOT, "%s|%d|%s|%s|%s|%.6f|%.6f|%.6f|%.6f%n", s.name, a - 2, adverts ? "ads" : "no",
                                        it.ad ? "ad" : it.jingle ? "jingle" : "song", it.track.key, it.start, it.end, it.fadeIn, it.fadeOut);
                            }
                        }
                    }
                }
                pack.close();
            }
        }
    }
}
