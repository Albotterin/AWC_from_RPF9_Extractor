package org.awctool.radio;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A small log of what the app did (kept in memory and in files/log.txt) that the "Info & log" screen shows. It is how
 * a problem on a phone can be looked at afterwards, including crashes (see App).
 */
final class AppLog {
    private static final int MAX_LINES = 300;
    private static final long MAX_FILE = 100 * 1024;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static File file;

    private AppLog() {
    }

    static synchronized void init(Context ctx) {
        if (file != null) return;
        // device protected storage: the alarm writes here also before the phone is unlocked after a restart
        File dir = ctx.createDeviceProtectedStorageContext().getFilesDir();
        file = new File(dir, "log.txt");
        if (file.length() > MAX_FILE) {
            File old = new File(dir, "log.old.txt");
            //noinspection ResultOfMethodCallIgnored
            old.delete();
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(old);
            file = new File(dir, "log.txt");
        }
    }

    static synchronized void d(String msg) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        String line = f.format(new Date()) + "  " + msg;
        LINES.addLast(line);
        while (LINES.size() > MAX_LINES) LINES.removeFirst();
        if (file != null) {
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((line + "\n").getBytes("UTF-8"));
            } catch (IOException ignored) {
            }
        }
    }

    static synchronized void e(String msg, Throwable t) {
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        d("ERROR " + msg + ": " + sw.toString().replace("\n", "\n    "));
    }

    /** The log as text: the lines of this run, preceded by the end of the earlier runs if there is room. */
    static synchronized String text() {
        StringBuilder sb = new StringBuilder();
        if (file != null && file.exists()) {
            try {
                byte[] b = new byte[(int) Math.min(file.length(), 24 * 1024)];
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                    raf.seek(Math.max(0, file.length() - b.length));
                    raf.readFully(b);
                }
                sb.append(new String(b, "UTF-8"));
            } catch (IOException ignored) {
            }
        }
        if (sb.length() == 0) for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }
}
