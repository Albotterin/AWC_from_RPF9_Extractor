package org.awctool.radio;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * The widgets of the home screen: a player (what is on, previous, pause, next) and a tile per chosen station that plays
 * it with one tap. The radio tells them what it does; while it is off they show the station heard last, or their own,
 * from pictures kept in the app's folder, so that the packs need not be read for that.
 */
final class Widgets {
    private static final String PREFS = "widgets", LAST_NAME = "last", LAST_TINT = "lastTint";
    private static final String STATION = "station_", TINT = "tint_";
    private static final int PICTURE = 192;                  // pixels of a logo in a widget
    // sizes in dp from which the player shows more
    private static final int WITH_TEXT = 180, WITH_SKIP = 290, LARGE_HEIGHT = 110, TILE_NAME_HEIGHT = 96;

    /** What the radio is doing. */
    private static final class Now {
        final String radio;                                  // the radio station on air, "" for something on demand
        final String label, title, artist;
        final boolean playing;
        final Bitmap logo;
        final int tint;

        Now(String radio, String label, String title, String artist, boolean playing, Bitmap logo, int tint) {
            this.radio = radio;
            this.label = label;
            this.title = title;
            this.artist = artist;
            this.playing = playing;
            this.logo = logo;
            this.tint = tint;
        }
    }

    private static Now now;                                  // null: the radio is off
    private static String shown = "";                        // what the widgets were told last

    private Widgets() {
    }

    // ------------------------------------------------------------------------------------------ what the radio says
    /** The radio is on. `cover`: whose picture it wears; `radio`: the station's name, "" for something on demand. */
    static void on(Context c, Library.Station cover, String radio, String label, String title, String artist, boolean playing) {
        try {
            String say = playing + "\n" + radio + "\n" + label + "\n" + title + "\n" + artist + "\n" + cover.name + "\n"
                    + System.identityHashCode(cover.pack);
            if (say.equals(shown)) return;
            boolean other = now == null || !now.radio.equals(radio);
            now = new Now(radio, label, title, artist, playing, rounded(Logos.get(cover, 256)), Logos.color(cover).tint);
            shown = say;
            if (other && !radio.isEmpty()) remember(c, radio, now.logo, now.tint);
            update(c);
        } catch (RuntimeException e) {
            AppLog.e("widgets", e);                           // a widget never stops the radio
        }
    }

    /** The radio is off. */
    static void off(Context c) {
        if (now == null && "off".equals(shown)) return;
        now = null;
        shown = "off";
        update(c);
    }

    /** Bring every widget on the home screen up to date. */
    static void update(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            if (m == null) return;
            for (int id : ids(c, m, PlayerWidget.class)) m.updateAppWidget(id, player(c, m.getAppWidgetOptions(id)));
            for (int id : ids(c, m, StationWidget.class)) m.updateAppWidget(id, tile(c, id, m.getAppWidgetOptions(id)));
        } catch (RuntimeException e) {
            AppLog.e("widgets", e);
        }
    }

    private static int[] ids(Context c, AppWidgetManager m, Class<?> widget) {
        int[] ids = m.getAppWidgetIds(new ComponentName(c, widget));
        return ids == null ? new int[0] : ids;
    }

    // ------------------------------------------------------------------------------------------ the player
    private static RemoteViews player(Context c, Bundle size) {
        // upright a widget is as narrow as its smallest width and as high as its greatest height; sideways the reverse
        int minW = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
        int maxW = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH);
        int minH = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        int maxH = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        return new RemoteViews(player(c, maxW, minH), player(c, minW, maxH));
    }

    /** The player in a widget of `w` x `h` dp (0: not known). */
    static RemoteViews player(Context c, int w, int h) {
        boolean large = h >= LARGE_HEIGHT && (w == 0 || w >= WITH_TEXT);
        RemoteViews v = new RemoteViews(c.getPackageName(), large ? R.layout.widget_player_large : R.layout.widget_player);
        SharedPreferences p = prefs(c);
        String station = now != null ? now.label : p.getString(LAST_NAME, c.getString(R.string.app_name));
        String title = now == null ? "" : now.title.isEmpty() ? c.getString(R.string.loading) : now.title;
        String artist = now == null ? "" : now.artist;
        boolean playing = now != null && now.playing;
        v.setInt(R.id.w_bg, "setColorFilter", now != null ? now.tint : p.getInt(LAST_TINT, StationColor.plain().tint));
        picture(v, now != null ? now.logo : read(last(c)));
        if (large) {
            text(v, R.id.w_station, station);
            text(v, R.id.w_title, now != null ? title : c.getString(R.string.widget_tap));
            text(v, R.id.w_artist, artist);
        } else if (now == null) {
            text(v, R.id.w_title, station);
            text(v, R.id.w_artist, c.getString(R.string.widget_tap));
        } else {
            text(v, R.id.w_title, title);
            text(v, R.id.w_artist, artist.isEmpty() ? station : artist + " • " + station);
        }
        v.setViewVisibility(R.id.w_texts, w == 0 || w >= WITH_TEXT ? View.VISIBLE : View.INVISIBLE);
        int skip = large || w == 0 || w >= WITH_SKIP ? View.VISIBLE : View.GONE;
        v.setViewVisibility(R.id.w_prev, skip);
        v.setViewVisibility(R.id.w_next, skip);
        v.setImageViewResource(R.id.w_play, playing ? R.drawable.ic_pause : R.drawable.ic_play);
        v.setContentDescription(R.id.w_play, c.getString(playing ? R.string.act_pause : R.string.act_play));
        v.setOnClickPendingIntent(R.id.w_play, control(c, RadioService.WIDGET_TOGGLE, null));
        v.setOnClickPendingIntent(R.id.w_prev, control(c, RadioService.WIDGET_PREV, null));
        v.setOnClickPendingIntent(R.id.w_next, control(c, RadioService.WIDGET_NEXT, null));
        PendingIntent open = open(c);
        v.setOnClickPendingIntent(R.id.w_logo, open);
        v.setOnClickPendingIntent(R.id.w_texts, open);
        return v;
    }

    // ------------------------------------------------------------------------------------------ the station tiles
    private static RemoteViews tile(Context c, int id, Bundle size) {
        int minH = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT);
        int maxH = size == null ? 0 : size.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
        return new RemoteViews(tileOf(c, id, minH), tileOf(c, id, maxH));
    }

    /** The tile of widget `id`, `h` dp high (0: not known). */
    static RemoteViews tileOf(Context c, int id, int h) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_station);
        SharedPreferences p = prefs(c);
        String name = p.getString(STATION + id, "");
        v.setInt(R.id.w_bg, "setColorFilter", p.getInt(TINT + id, StationColor.plain().tint));
        picture(v, name.isEmpty() ? null : read(file(c, id)));
        text(v, R.id.w_station, name.isEmpty() ? c.getString(R.string.widget_choose) : name);
        v.setViewVisibility(R.id.w_station, name.isEmpty() || h >= TILE_NAME_HEIGHT ? View.VISIBLE : View.GONE);
        boolean here = now != null && !name.isEmpty() && name.equals(now.radio);
        v.setViewVisibility(R.id.w_badge, here ? View.VISIBLE : View.GONE);
        if (here) v.setImageViewResource(R.id.w_badge, now.playing ? R.drawable.ic_pause : R.drawable.ic_play);
        if (name.isEmpty()) {
            v.setContentDescription(R.id.w_logo, c.getString(R.string.widget_choose));
            v.setOnClickPendingIntent(android.R.id.background, choose(c, id));
        } else {
            v.setContentDescription(R.id.w_logo, here && now.playing ? c.getString(R.string.act_pause) + ": " + name
                    : c.getString(R.string.widget_play_station, name));
            v.setOnClickPendingIntent(android.R.id.background, control(c, RadioService.WIDGET_TOGGLE, name));
        }
        return v;
    }

    /** The station of a tile, "" while none is chosen. */
    static String stationOf(Context c, int id) {
        return prefs(c).getString(STATION + id, "");
    }

    /** A tile gets its station. */
    static void choose(Context c, int id, Library.Station st) {
        Bitmap logo = rounded(Logos.get(st, 256));
        write(file(c, id), logo);
        prefs(c).edit().putString(STATION + id, st.name).putInt(TINT + id, Logos.color(st).tint).apply();
        update(c);
    }

    /** Tiles were taken off the home screen. */
    static void forget(Context c, int[] ids) {
        SharedPreferences.Editor e = prefs(c).edit();
        for (int id : ids) {
            e.remove(STATION + id).remove(TINT + id);
            File f = file(c, id);
            if (f.exists() && !f.delete()) AppLog.d("widget picture stays: " + f.getName());
        }
        e.apply();
    }

    // ------------------------------------------------------------------------------------------ taps
    /** A tap that tells the radio what to do; `station`: for a tile, the station it stands for. */
    private static PendingIntent control(Context c, int what, String station) {
        Intent i = new Intent(c, RadioService.class).setAction(RadioService.ACTION_WIDGET).putExtra(RadioService.EXTRA_DO, what)
                // the address only tells these intents apart: each tap has its own
                .setData(Uri.parse("awcradio://widget/" + what + (station == null ? "" : "/" + Uri.encode(station))));
        if (station != null) i.putExtra(RadioService.EXTRA_STATION, station);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return Build.VERSION.SDK_INT >= 26 ? PendingIntent.getForegroundService(c, 0, i, flags) : PendingIntent.getService(c, 0, i, flags);
    }

    private static PendingIntent open(Context c) {
        Intent i = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent choose(Context c, int id) {
        Intent i = new Intent(c, StationWidgetActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .setData(Uri.parse("awcradio://widget/choose/" + id)).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    // ------------------------------------------------------------------------------------------ pictures
    private static void text(RemoteViews v, int id, String s) {
        v.setTextViewText(id, s);
        v.setViewVisibility(id, s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static void picture(RemoteViews v, Bitmap logo) {
        if (logo != null) v.setImageViewBitmap(R.id.w_logo, logo);
        else v.setImageViewResource(R.id.w_logo, R.mipmap.ic_launcher);
    }

    private static Bitmap rounded(Bitmap logo) {
        Bitmap out = Bitmap.createBitmap(PICTURE, PICTURE, Bitmap.Config.ARGB_8888);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(Bitmap.createScaledBitmap(logo, PICTURE, PICTURE, true), Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP));
        new Canvas(out).drawRoundRect(new RectF(0, 0, PICTURE, PICTURE), PICTURE * 0.16f, PICTURE * 0.16f, p);
        return out;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static File last(Context c) {
        return new File(c.getFilesDir(), "widget_last.png");
    }

    private static File file(Context c, int id) {
        return new File(c.getFilesDir(), "widget_" + id + ".png");
    }

    /** Keeps what the widgets show of a station while the radio is off; its tiles get the newest picture too. */
    private static void remember(Context c, String station, Bitmap logo, int tint) {
        SharedPreferences p = prefs(c);
        write(last(c), logo);
        SharedPreferences.Editor e = p.edit().putString(LAST_NAME, station).putInt(LAST_TINT, tint);
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        for (int id : m == null ? new int[0] : ids(c, m, StationWidget.class)) {
            if (!station.equals(p.getString(STATION + id, ""))) continue;
            write(file(c, id), logo);
            e.putInt(TINT + id, tint);
        }
        e.apply();
    }

    private static void write(File f, Bitmap b) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (IOException e) {
            AppLog.e("widget picture", e);
        }
    }

    private static Bitmap read(File f) {
        return f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
    }
}
