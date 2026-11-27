package org.awctool.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks on GitHub for a newer app and proposes it together with its changelog; Installer then loads and installs it. The
 * app asks the internet for nothing but that list of releases and, when the listener wants it, the new app.
 *
 * At the start of the app it asks at most once a day (unless switched off in the settings) and says nothing unless there
 * is something newer that the listener did not skip; the button in the settings always answers.
 */
final class Updates {
    private static final String PREF_AUTO = "updates_auto", PREF_ASKED = "updates_asked", PREF_SKIP = "updates_skip";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final int LIMIT = 4 * 1024 * 1024;        // more than the list of releases ever is

    /** Where the list of releases comes from; the tests put their own here. */
    interface Source {
        String read(String url) throws IOException;
    }

    static Source source = Updates::download;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean busy;

    private Updates() {
    }

    static boolean automatic(Context c) {
        return prefs(c).getBoolean(PREF_AUTO, true);
    }

    static void setAutomatic(Context c, boolean on) {
        prefs(c).edit().putBoolean(PREF_AUTO, on).apply();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(RadioService.PREFS, Context.MODE_PRIVATE);
    }

    /** The app was opened. */
    static void onStart(Activity a) {
        if (Installer.resumeAfterRestart(a) && !busy) {      // restarted while the listener allowed installing: go on
            ask(a, (all, error) -> {
                List<Releases.Release> newer = all == null ? null : Releases.newer(all, installed(a));
                if (newer != null && !newer.isEmpty()) Installer.start(a, newer.get(0));
            });
            return;
        }
        if (!automatic(a) || busy || Installer.busy()
                || Math.abs(System.currentTimeMillis() - prefs(a).getLong(PREF_ASKED, 0)) < DAY) return;
        ask(a, (all, error) -> {
            if (all == null) return;                          // no connection: ask again next time, without a word
            prefs(a).edit().putLong(PREF_ASKED, System.currentTimeMillis()).apply();
            String installed = installed(a);
            List<Releases.Release> newer = Releases.newer(all, installed);
            if (newer.isEmpty() || newer.get(0).app.equals(prefs(a).getString(PREF_SKIP, ""))) return;
            offer(a, installed, newer, true);
        });
    }

    /** The listener asked: always an answer, always with a changelog. */
    static void check(Activity a) {
        if (busy) return;
        Toast.makeText(a, R.string.update_checking, Toast.LENGTH_SHORT).show();
        ask(a, (all, error) -> {
            if (all == null) {
                boolean full = error.contains("HTTP 403") || error.contains("HTTP 429");
                new AlertDialog.Builder(a, R.style.AppDialog).setTitle(R.string.update_failed_title)
                        .setMessage(full ? R.string.update_busy : R.string.update_failed)
                        .setPositiveButton(R.string.info_close, null).show();
                return;
            }
            prefs(a).edit().putLong(PREF_ASKED, System.currentTimeMillis()).apply();
            String installed = installed(a);
            List<Releases.Release> newer = Releases.newer(all, installed);
            if (!newer.isEmpty()) {
                offer(a, installed, newer, false);
                return;
            }
            // nothing newer: what is in the version at hand (or, for one not published yet, in the last published one)
            List<Releases.Release> shown = Releases.of(all, installed);
            SpannableStringBuilder text = new SpannableStringBuilder();
            if (shown.isEmpty() && !all.isEmpty()) {
                shown = Releases.of(all, all.get(0).app);
                text.append(a.getString(R.string.update_ahead, installed, all.get(0).app)).append("\n\n");
            }
            if (all.isEmpty()) text.append(a.getString(R.string.update_none));
            changelog(a, text, shown);
            new AlertDialog.Builder(a, R.style.AppDialog).setTitle(a.getString(R.string.update_current, installed))
                    .setView(scroll(a, text)).setPositiveButton(R.string.info_close, null).show();
        });
    }

    private static void offer(Activity a, String installed, List<Releases.Release> newer, boolean unasked) {
        Releases.Release latest = newer.get(0);
        SpannableStringBuilder text = new SpannableStringBuilder(a.getString(R.string.update_installed, installed)).append("\n\n");
        changelog(a, text, newer);
        AlertDialog.Builder b = new AlertDialog.Builder(a, R.style.AppDialog)
                .setTitle(a.getString(R.string.update_available, latest.app))
                .setView(scroll(a, text))
                .setPositiveButton(R.string.update_install, (d, w) -> Installer.start(a, latest))
                .setNegativeButton(unasked ? R.string.update_later : R.string.info_close, null);
        if (unasked) {
            b.setNeutralButton(R.string.update_skip, (d, w) -> prefs(a).edit().putString(PREF_SKIP, latest.app).apply());
        }
        b.show();
    }

    /** What the releases brought, the newest first. */
    private static void changelog(Context c, SpannableStringBuilder text, List<Releases.Release> releases) {
        boolean german = "de".equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
        for (Releases.Release r : releases) {
            heading(text, c.getString(R.string.update_version, r.app, r.day()), Ui.SUN);
            for (Releases.Line line : Releases.changelog(r.notes, german)) {
                if (line.heading) heading(text, line.text, Ui.TEXT);
                else text.append(line.text).append('\n');
            }
            text.append('\n');
        }
    }

    private static void heading(SpannableStringBuilder text, String s, int color) {
        int from = text.length();
        text.append(s);
        text.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(color), from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.append('\n');
    }

    private static ScrollView scroll(Context c, CharSequence text) {
        TextView t = Ui.text(c, "", 14, Ui.TEXT);
        t.setText(text);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(Ui.dp(c, 24), Ui.dp(c, 8), Ui.dp(c, 24), Ui.dp(c, 8));
        ScrollView s = new ScrollView(c);
        s.addView(t);
        return s;
    }

    /** Hands an address to the browser. */
    static void open(Activity a, String url) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
            Toast.makeText(a, R.string.update_install_hint, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.e("open " + url, e);
            Toast.makeText(a, a.getString(R.string.update_open_failed, url), Toast.LENGTH_LONG).show();
        }
    }

    static String installed(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    private interface Answer {
        /** On the main thread: the releases, or null and what went wrong. */
        void of(List<Releases.Release> all, String error);
    }

    private static void ask(Activity a, Answer answer) {
        busy = true;
        final Source from = source;
        worker.execute(() -> {
            List<Releases.Release> all = null;
            String error = "";
            try {
                all = Releases.parse(from.read(Releases.LIST));
            } catch (IOException | JSONException | RuntimeException e) {
                error = String.valueOf(e.getMessage());
                AppLog.d("looking for updates: " + e);
            }
            final List<Releases.Release> found = all;
            final String why = error;
            main.post(() -> {
                busy = false;
                if (!a.isFinishing() && !a.isDestroyed()) answer.of(found, why);
            });
        });
    }

    private static String download(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "AWC-Radio");            // GitHub wants one; nothing about the listener
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] block = new byte[16384];
                for (int n; (n = in.read(block)) > 0; ) {
                    out.write(block, 0, n);
                    if (out.size() > LIMIT) throw new IOException("the answer is too long");
                }
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
