package org.awctool.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The folder with the station packs, picked once by the user (Android's folder picker; the permission to read it is
 * kept). All .radiopak files in it are loaded; refresh() looks again and tells the listeners when something changed.
 */
final class PackStore {
    private static final String PREFS = "pack";
    private static final String KEY_FOLDER = "folder";

    interface Listener {
        /** The station list changed (on the main thread). */
        void onStationsChanged();
    }

    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static Catalog catalog;
    static volatile String error = "";             // why the folder could not be read, for the UI

    private PackStore() {
    }

    static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The chosen folder, or null. */
    static Uri folder(Context ctx) {
        String s = prefs(ctx).getString(KEY_FOLDER, null);
        return s == null ? null : Uri.parse(s);
    }

    /** The folder as a readable path ("AWC-Radio"), for the screen. */
    static String folderName(Context ctx) {
        Uri u = folder(ctx);
        if (u == null) return "-";
        try {
            String id = DocumentsContract.getTreeDocumentId(u);
            int c = id.indexOf(':');
            return c >= 0 && c + 1 < id.length() ? id.substring(c + 1) : id;
        } catch (RuntimeException e) {
            return u.toString();
        }
    }

    /** The loaded packs; the folder is read on the first call. Never null. */
    static synchronized Catalog get(Context ctx) {
        if (catalog == null) catalog = scan(ctx.getApplicationContext(), null);
        return catalog;
    }

    /** Use another folder; the listeners are told once it is read. */
    static void setFolder(Context ctx, Uri tree) {
        prefs(ctx).edit().putString(KEY_FOLDER, tree.toString()).apply();
        reload(ctx, true, null);
    }

    /** Look into the folder again, in the background. `done` runs on the main thread afterwards (may be null). */
    static void refresh(Context ctx, Runnable done) {
        reload(ctx, false, done);
    }

    private static void reload(Context ctx, final boolean fresh, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        worker.execute(() -> {
            if (!fresh) get(app);                                  // the very first reading happens only once
            final Catalog old;
            synchronized (PackStore.class) {
                old = catalog;
            }
            Catalog loaded;
            try {
                loaded = scan(app, fresh ? null : old);
            } catch (RuntimeException e) {
                AppLog.e("reading the folder", e);
                loaded = old != null ? old : Catalog.empty();
            }
            final Catalog next = loaded;
            main.post(() -> {
                boolean changed;
                synchronized (PackStore.class) {
                    changed = fresh || !next.sameAs(catalog);
                    Catalog before = catalog;
                    catalog = next;
                    if (changed) {
                        Logos.clear();
                        for (Listener l : listeners) l.onStationsChanged();
                    }
                    if (before != null && before != next) before.closeUnused(next);       // after the radio has switched
                }
                if (done != null) done.run();
            });
        });
    }

    private static Catalog scan(Context ctx, Catalog old) {
        Uri tree = folder(ctx);
        if (tree == null) {
            error = "";
            return Catalog.empty();
        }
        List<Catalog.Source> files = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED};
            try (Cursor c = ctx.getContentResolver().query(children, columns, null, null, null)) {
                if (c == null) throw new IllegalStateException("no answer from the file system");
                while (c.moveToNext()) {
                    if (Catalog.isPack(c.getString(1))) {
                        files.add(new Catalog.Source(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)));
                    }
                }
            }
            error = "";
        } catch (RuntimeException e) {
            error = String.valueOf(e.getMessage());
            AppLog.d("folder cannot be read: " + e);
            return old != null ? old : Catalog.empty();       // a passing problem must not stop a running station
        }
        Catalog result = Catalog.load(files, old, source -> open(ctx, DocumentsContract.buildDocumentUriUsingTree(tree, source.id)));
        if (old == null || !result.sameAs(old)) {
            AppLog.d("folder read: " + files.size() + " pack file(s), " + result.stations.size() + " station(s), "
                    + result.podcasts.size() + " podcast(s), " + result.scores.size() + " score group(s)");
            for (String p : result.problems) AppLog.d("skipped " + p);
        }
        return result;
    }

    private static Pack open(Context ctx, Uri uri) throws Exception {
        ParcelFileDescriptor pfd = ctx.getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new java.io.IOException("cannot open the file");
        try {
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
            return new Pack(in.getChannel(), pfd.getFileDescriptor(), pfd);
        } catch (Exception e) {
            try {
                pfd.close();
            } catch (java.io.IOException ignored) {
            }
            throw e;
        }
    }
}
