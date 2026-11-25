package org.awctool.radio;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;

/** Remembers which pack file the user picked (a content:// URI with persistable permission) and keeps it open. */
final class PackStore {
    private static final String PREFS = "pack";
    private static final String KEY_URI = "uri";

    private static Pack pack;
    static String error = "";             // why the saved pack could not be opened, for the UI

    private PackStore() {
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

    /** The pack the user picked earlier, or null (then {@link #error} may say why). */
    static synchronized Pack get(Context ctx) {
        if (pack != null) return pack;
        SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String s = sp.getString(KEY_URI, null);
        if (s == null) return null;
        try {
            pack = open(ctx.getApplicationContext(), Uri.parse(s));
            error = "";
        } catch (Exception e) {
            error = String.valueOf(e.getMessage());
            pack = null;
        }
        return pack;
    }

    /** Use a newly picked file. Throws if it is not a valid pack (the old one stays). */
    static synchronized void set(Context ctx, Uri uri) throws Exception {
        Pack p = open(ctx.getApplicationContext(), uri);
        if (pack != null) pack.close();
        pack = p;
        error = "";
        ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply();
    }
}
