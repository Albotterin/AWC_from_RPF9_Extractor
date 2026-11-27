package org.awctool.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brings a newer app onto the phone without a browser: loads the APK of a release, checks it (length and checksum as
 * GitHub lists them, and that it is this app, not an older build) and hands it to Android's package installer, which asks
 * the listener before it installs. Android only takes it when it is signed with the same key as the installed app.
 *
 * Once, Android also has to be told that this app may install apps (from Android 8 on, in a setting of the phone).
 * Loading goes on while no screen of the app is shown; the question to install waits until one is.
 */
final class Installer {
    private static final String PREF_WAITING = "updates_waiting";    // when the listener went to allow installing
    private static final long WAIT = 10L * 60 * 1000;
    private static final long LIMIT = 64L * 1024 * 1024;             // far more than the app will ever be

    /** Where the file comes from; the tests put their own here. */
    interface Source {
        InputStream open(String url) throws IOException;
    }

    /** One update on its way: loading, then loaded (file set), then handed to Android. */
    private static final class Job {
        final Releases.Release release;
        volatile boolean cancelled;
        int percent;
        File file;                                                   // loaded and checked
        boolean handing;

        Job(Releases.Release release) {
            this.release = release;
        }
    }

    /** What went wrong, as the string that says it. */
    private static final class Failure extends Exception {
        final int message;

        Failure(int message, String detail) {
            super(detail);
            this.message = message;
        }
    }

    static Source source = Installer::open;
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Activity screen;                    // the screen of the app that is shown, or null
    private static Job job;
    private static Releases.Release asked;             // to be loaded once installing is allowed
    private static AlertDialog progress;
    private static ProgressBar bar;

    private Installer() {
    }

    /** The listener wants this release. */
    static void start(Activity a, Releases.Release release) {
        screen = a;
        if (release.apk.isEmpty()) {
            Updates.open(a, release.download());                     // nothing to load: show its page
        } else if (job != null) {
            go(a);
        } else if (!allowed(a)) {
            askToAllow(a, release);
        } else {
            load(a.getApplicationContext(), release);
        }
    }

    /** A screen of the app is shown: it takes over what is going on. */
    static void attach(Activity a) {
        screen = a;
        if (asked != null) {                                         // back from the phone's setting
            Releases.Release release = asked;
            asked = null;
            waiting(a, false);
            if (allowed(a)) start(a, release);
            else Toast.makeText(a, R.string.update_not_allowed, Toast.LENGTH_LONG).show();
        } else if (job != null) {
            go(a);
        }
    }

    /** The screen goes away; loading goes on. */
    static void detach(Activity a) {
        if (screen != a) return;
        dismiss();
        screen = null;
    }

    /** Whether the app was restarted while the listener allowed installing: then the update is to go on. */
    static boolean resumeAfterRestart(Context c) {
        long since = Updates.prefs(c).getLong(PREF_WAITING, 0);
        if (since == 0 || asked != null) return false;
        waiting(c, false);
        return Math.abs(System.currentTimeMillis() - since) < WAIT && allowed(c) && job == null;
    }

    static boolean busy() {
        return job != null;
    }

    private static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    private static void waiting(Context c, boolean on) {
        Updates.prefs(c).edit().putLong(PREF_WAITING, on ? System.currentTimeMillis() : 0).commit();
    }

    private static void askToAllow(final Activity a, final Releases.Release release) {
        new AlertDialog.Builder(a, R.style.AppDialog).setTitle(R.string.update_allow_title).setMessage(R.string.update_allow)
                .setPositiveButton(R.string.update_allow_open, (d, w) -> {
                    try {
                        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + a.getPackageName())));
                        asked = release;
                        waiting(a, true);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        AppLog.e("setting for unknown apps", e);
                        Updates.open(a, release.download());
                    }
                })
                .setNeutralButton(R.string.update_browser, (d, w) -> Updates.open(a, release.download()))
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    /** Shows how far the job is, or asks Android to install what is loaded. */
    private static void go(Activity a) {
        if (job.file == null) show(a);
        else hand(a.getApplicationContext(), job);
    }

    private static void show(Activity a) {
        dismiss();
        final Job mine = job;
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(a, 24), Ui.dp(a, 12), Ui.dp(a, 24), 0);
        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(mine.percent);
        bar.setProgressDrawable(Ui.progressLine(a, Ui.RAISED, Ui.SUN));
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(a, 6)));
        TextView then = Ui.text(a, a.getString(R.string.update_closes), 14, Ui.MUTED);
        then.setPadding(0, Ui.dp(a, 16), 0, 0);
        box.addView(then);
        progress = new AlertDialog.Builder(a, R.style.AppDialog)
                .setTitle(a.getString(R.string.update_loading, mine.release.app))
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    mine.cancelled = true;
                    if (job == mine) job = null;
                })
                .show();
    }

    private static void dismiss() {
        if (progress != null) {
            try {
                progress.dismiss();
            } catch (IllegalArgumentException ignored) {             // its window is gone already
            }
        }
        progress = null;
        bar = null;
    }

    private static File place(Context c) {
        return new File(c.getCacheDir(), "update.apk");
    }

    private static void load(final Context c, Releases.Release release) {
        final Job mine = new Job(release);
        job = mine;
        if (screen != null) show(screen);
        final Source from = source;
        worker.execute(() -> {
            File file = place(c);
            Failure failed = null;
            try {
                fetch(from, mine, file);
                check(c, mine.release, file);
            } catch (Failure e) {
                failed = e;
            } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
                failed = new Failure(R.string.update_load_failed, String.valueOf(e.getMessage()));
            }
            final Failure why = failed;
            if (why != null || mine.cancelled) file.delete();
            main.post(() -> {
                if (job != mine || mine.cancelled) return;
                if (why != null) {
                    AppLog.d("update " + mine.release.app + ": " + why.getMessage());
                    job = null;
                    dismiss();
                    failed(c, c.getString(why.message), mine.release.download());
                    return;
                }
                mine.file = file;
                if (screen != null) hand(c, mine);                   // else when a screen comes back
            });
        });
    }

    /** Loads the release's APK into the file, checking the length and checksum GitHub lists for it. */
    private static void fetch(Source from, Job mine, File to) throws IOException, NoSuchAlgorithmException, Failure {
        Releases.Release r = mine.release;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long done = 0;
        try (InputStream in = from.open(r.apk); OutputStream out = new FileOutputStream(to)) {
            byte[] block = new byte[16384];
            for (int n; (n = in.read(block)) > 0; ) {
                if (mine.cancelled) return;
                done += n;
                if (done > LIMIT || r.size > 0 && done > r.size) throw new Failure(R.string.update_broken, "longer than listed");
                out.write(block, 0, n);
                sha.update(block, 0, n);
                final int percent = r.size > 0 ? (int) (done * 100 / r.size) : 0;
                if (percent != mine.percent) {
                    mine.percent = percent;
                    main.post(() -> {
                        if (job == mine && bar != null) bar.setProgress(percent);
                    });
                }
            }
        }
        if (r.size > 0 && done != r.size) throw new Failure(R.string.update_load_failed, "only " + done + " of " + r.size + " bytes");
        if (!r.sha256.isEmpty()) {
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!r.sha256.equalsIgnoreCase(hex.toString())) throw new Failure(R.string.update_broken, "checksum differs");
        }
    }

    /** The file has to be this app, and not an older build than the installed one. */
    @SuppressWarnings("deprecation")                                  // versionCode: getLongVersionCode needs Android 9
    private static void check(Context c, Releases.Release r, File file) throws Failure {
        PackageInfo found = c.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (found == null || !c.getPackageName().equals(found.packageName)) throw new Failure(R.string.update_broken, "not this app");
        try {
            if (found.versionCode < c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode) {
                throw new Failure(R.string.update_broken, "an older build: " + found.versionName);
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new Failure(R.string.update_broken, "own package unknown");
        }
    }

    /** Gives the loaded file to Android's installer; InstallReceiver hears what becomes of it. */
    private static void hand(final Context c, final Job mine) {
        dismiss();
        if (mine.handing) return;
        mine.handing = true;
        worker.execute(() -> {
            String error = null;
            try {
                session(c, mine);
            } catch (IOException | RuntimeException e) {
                AppLog.e("install " + mine.release.app, e);
                error = String.valueOf(e.getMessage());
            }
            mine.file.delete();
            final String why = error;
            main.post(() -> {
                if (job == mine) job = null;
                if (why != null) failed(c, c.getString(R.string.update_refused, why), mine.release.download());
            });
        });
    }

    private static void session(Context c, Job mine) throws IOException {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        for (PackageInstaller.SessionInfo old : installer.getMySessions()) {       // left over from an earlier try
            try {
                installer.abandonSession(old.getSessionId());
            } catch (RuntimeException ignored) {
            }
        }
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        params.setSize(mine.file.length());
        int id = installer.createSession(params);
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(mine.file); OutputStream out = session.openWrite("app.apk", 0, mine.file.length())) {
                byte[] block = new byte[65536];
                for (int n; (n = in.read(block)) > 0; ) out.write(block, 0, n);
                session.fsync(out);
            }
            Intent answer = new Intent(c, InstallReceiver.class).putExtra(InstallReceiver.EXTRA_PAGE, mine.release.download());
            // Android writes its answer into the intent, so it must be mutable; it names its receiver
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            session.commit(PendingIntent.getBroadcast(c, id, answer, flags).getIntentSender());
            committed = true;
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(id);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** What Android's installer answered (see InstallReceiver). */
    static void answered(Context c, Intent answer, String page) {
        if (!answer.hasExtra(PackageInstaller.EXTRA_STATUS)) return;          // says nothing about the installation
        int status = answer.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent question = answer.getParcelableExtra(Intent.EXTRA_INTENT);
            try {
                if (screen != null) screen.startActivity(question);
                else c.startActivity(question.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {
                AppLog.e("question to install", e);
                failed(c, c.getString(R.string.update_refused, String.valueOf(e.getMessage())), page);
            }
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            Toast.makeText(c, R.string.update_aborted, Toast.LENGTH_LONG).show();
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String why = answer.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            AppLog.d("install refused: " + status + " " + why);
            failed(c, c.getString(R.string.update_refused, why == null || why.isEmpty() ? String.valueOf(status) : why), page);
        }
    }

    /** Says what went wrong and offers the browser, the way that was there before. */
    private static void failed(Context c, String message, String page) {
        final Activity a = screen;
        if (a == null || a.isFinishing() || a.isDestroyed()) {
            Toast.makeText(c, message, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(a, R.style.AppDialog).setTitle(R.string.update_not_done).setMessage(message)
                .setPositiveButton(R.string.update_browser, (d, w) -> Updates.open(a, page))
                .setNegativeButton(R.string.info_close, null).show();
    }

    private static InputStream open(String url) throws IOException {
        final HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        boolean opened = false;
        try {
            c.setConnectTimeout(15_000);
            c.setReadTimeout(30_000);
            c.setRequestProperty("User-Agent", "AWC-Radio");
            int code = c.getResponseCode();                           // GitHub sends on to where the file lies
            if (code != 200) throw new IOException("HTTP " + code);
            if (!"https".equals(c.getURL().getProtocol())) throw new IOException("not a secure address");
            InputStream in = new FilterInputStream(c.getInputStream()) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        c.disconnect();
                    }
                }
            };
            opened = true;
            return in;
        } finally {
            if (!opened) c.disconnect();
        }
    }
}
