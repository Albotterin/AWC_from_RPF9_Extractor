package org.awctool.radio;

import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;

import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.FileNotFoundException;
import java.time.Duration;

/** What the tests of the Android screens and the alarm share: a folder of real station packs behind Android's file API. */
final class Harness {
    static final String AUTHORITY = "org.awctool.test.docs";

    private Harness() {
    }

    static File folder() {
        return new File(System.getProperty("awc.packs"));
    }

    /** Stands in for the phone's file system: lists and opens the pack files of folder(). */
    public static final class Docs extends ContentProvider {
        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
            MatrixCursor c = new MatrixCursor(columns);
            File[] files = folder().listFiles();
            if (files != null) {
                for (File f : files) c.addRow(new Object[]{f.getName(), f.getName(), f.length(), f.lastModified()});
            }
            return c;
        }

        @Override
        public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(new File(folder(), DocumentsContract.getDocumentId(uri)), ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public String getType(Uri uri) {
            return Document.MIME_TYPE_DIR;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int delete(Uri uri, String s, String[] a) {
            return 0;
        }

        @Override
        public int update(Uri uri, ContentValues v, String s, String[] a) {
            return 0;
        }
    }

    /** Forgets everything an earlier test left in the static fields. */
    static void reset() {
        Updates.source = url -> {                                  // no test goes to the internet
            throw new java.io.IOException("offline in the tests");
        };
        ReflectionHelpers.setStaticField(Updates.class, "busy", false);
        ReflectionHelpers.setStaticField(PackStore.class, "catalog", null);
        ReflectionHelpers.setStaticField(PackStore.class, "ready", false);
        ReflectionHelpers.setStaticField(Widgets.class, "now", null);
        ReflectionHelpers.setStaticField(Widgets.class, "shown", "");
        AlarmService.running = null;
        Logos.clear();
        Ui.displayFont = null;
    }

    static Context app() {
        return RuntimeEnvironment.getApplication();
    }

    static void choosePacks() {
        Robolectric.buildContentProvider(Docs.class).create(AUTHORITY);
        Uri tree = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "primary:AWC-Radio");
        app().getSharedPreferences("pack", Context.MODE_PRIVATE).edit().putString("folder", tree.toString()).commit();
    }

    /** The screen's connection to the radio: Android would make it, here a real RadioService stands ready. */
    static RadioService radio() {
        RadioService s = Robolectric.buildService(RadioService.class).create().get();
        ComponentName name = new ComponentName(app(), RadioService.class);
        Shadows.shadowOf(application()).setComponentNameAndServiceForBindServiceForIntent(
                new Intent(app(), RadioService.class), name, s.onBind(new Intent()));
        return s;
    }

    /** Players "work": they prepare at once and play. */
    static void workingPlayers() {
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(600_000, 0));
    }

    static Application application() {
        return RuntimeEnvironment.getApplication();
    }

    /** Lets background work and due messages of the main thread happen. */
    static void settle() {
        for (int i = 0; i < 40; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lets the phone's clock run on. */
    static void pass(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
