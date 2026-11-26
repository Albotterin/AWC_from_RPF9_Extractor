package org.awctool.radio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The settings: how the radio plays (crossfade, content), where the packs lie, updates, the phone's battery settings and the
 * information with the log. The radio service notices a changed setting by itself.
 */
public class SettingsActivity extends Activity implements PackStore.Listener {
    private static final int REQ_PICK = 43;

    private SharedPreferences prefs;
    private TextView fadeValue, folderValue;
    private LinearLayout fadeRow;
    private SeekBar fadeBar;

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLog.init(this);
        prefs = getSharedPreferences(RadioService.PREFS, MODE_PRIVATE);
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PackStore.addListener(this);
        update();
    }

    @Override
    protected void onStop() {
        PackStore.removeListener(this);
        super.onStop();
    }

    @Override
    public void onStationsChanged() {
        update();
    }

    private void build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.NIGHT);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(10), dp(16), dp(4));
        ImageButton back = Ui.icon(this, R.drawable.ic_back, getString(R.string.act_close), 48, Ui.TEXT);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.display(this, getString(R.string.settings), 28, Ui.TEXT);
        title.setIncludeFontPadding(true);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title);
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- playback
        LinearLayout play = section(box, R.string.settings_playback);
        toggleRow(play, R.string.crossfade, R.string.crossfade_what, prefs.getBoolean(RadioService.PREF_FADE_ON, true), on -> {
            prefs.edit().putBoolean(RadioService.PREF_FADE_ON, on).apply();
            update();
        });
        fadeRow = new LinearLayout(this);
        fadeRow.setOrientation(LinearLayout.VERTICAL);
        fadeRow.setPadding(dp(18), dp(8), dp(18), dp(8));
        LinearLayout name = new LinearLayout(this);
        name.addView(Ui.text(this, getString(R.string.crossfade_length), 15, Ui.TEXT),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        fadeValue = Ui.strong(this, "", 15, Ui.SUN);
        name.addView(fadeValue);
        fadeRow.addView(name);
        fadeBar = new SeekBar(this);
        fadeBar.setMax(RadioService.FADE_MAX - 1);
        fadeBar.setProgress(RadioService.fadeSeconds(this) - 1);
        fadeBar.setProgressTintList(ColorStateList.valueOf(Ui.SUN));
        fadeBar.setThumbTintList(ColorStateList.valueOf(Ui.SUN));
        fadeBar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.MUTED));
        fadeBar.setContentDescription(getString(R.string.crossfade_length));
        fadeBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        fadeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private boolean dragging;

            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                fadeValue.setText(getString(R.string.seconds_short, p + 1));
                if (user && !dragging) keepFade(p + 1);                // set without dragging (accessibility)
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;                                      // saved once: the radio retunes once
                keepFade(s.getProgress() + 1);
            }
        });
        fadeRow.addView(fadeBar);
        play.addView(fadeRow);
        TextView sync = Ui.text(this, getString(R.string.crossfade_sync, (int) Schedule.FADE), 13, Ui.MUTED);
        sync.setPadding(dp(18), 0, dp(18), dp(10));
        sync.setLineSpacing(0, 1.12f);
        play.addView(sync);

        // ---- what comes between the songs
        LinearLayout between = section(box, R.string.content_title);
        for (int i = 0; i < Content.KINDS.length; i++) {
            int kind = Content.KINDS[i];
            toggleRow(between, Content.NAMES[i], Content.WHAT[i], (RadioService.content(this) & kind) != 0,
                    on -> RadioService.setContent(this, kind, on));
        }
        toggleRow(between, R.string.dj_switch, R.string.dj_what, RadioService.dj(this),
                on -> prefs.edit().putBoolean(RadioService.PREF_DJ, on).apply());

        // ---- the packs
        LinearLayout packs = section(box, R.string.settings_packs);
        LinearLayout folder = row(packs, R.string.settings_folder, v -> pickFolder());
        folderValue = Ui.oneLine(Ui.strong(this, "", 15, Ui.SUN));
        folderValue.setGravity(Gravity.END);
        folder.addView(folderValue, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row(packs, R.string.menu_reload, v -> reload());

        // ---- newer versions of the app
        LinearLayout updates = section(box, R.string.settings_updates);
        row(updates, R.string.update_check, v -> Updates.check(this));
        toggleRow(updates, R.string.update_auto, R.string.update_auto_what, Updates.automatic(this),
                on -> Updates.setAutomatic(this, on));

        // ---- the phone and the app
        LinearLayout more = section(box, R.string.settings_more);
        row(more, R.string.menu_battery, v -> openBatterySettings());
        row(more, R.string.menu_info, v -> showInfo());
        TextView version = Ui.text(this, getString(R.string.settings_version, version()), 13, Ui.MUTED);
        version.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(18);
        box.addView(version, vp);

        setContentView(root);
        update();
    }

    private void keepFade(int seconds) {
        prefs.edit().putInt(RadioService.PREF_FADE_SEC, seconds).apply();
    }

    private LinearLayout section(LinearLayout box, int title) {
        TextView t = Ui.strong(this, getString(title), 13, Ui.SUN);
        t.setPadding(dp(8), dp(16), 0, dp(8));
        box.addView(t);
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Ui.rounded(this, Ui.SURFACE, 24));
        g.setPadding(0, dp(6), 0, dp(6));
        Ui.clip(g, 24);
        box.addView(g);
        return g;
    }

    /** A line that is tapped: its name on the left, room for a value on the right. */
    private LinearLayout row(LinearLayout group, int name, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(18), dp(8), dp(18), dp(8));
        r.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(tap);
        TextView n = Ui.text(this, getString(name), 15, Ui.TEXT);
        n.setPadding(0, 0, dp(16), 0);
        r.addView(n);
        group.addView(r);
        return r;
    }

    private interface Switched {
        void to(boolean on);
    }

    /** A setting that is on or off, with a line that says what it does. */
    private void toggleRow(LinearLayout group, int name, int what, boolean on, Switched changed) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(62));
        r.setPadding(dp(18), dp(8), dp(14), dp(8));
        r.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        r.setClickable(true);
        r.setFocusable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, dp(12), 0);
        col.addView(Ui.text(this, getString(name), 15, Ui.TEXT));
        col.addView(Ui.text(this, getString(what), 13, Ui.MUTED));
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch s = Ui.toggle(this, on);
        s.setContentDescription(getString(name));
        s.setOnCheckedChangeListener((v, now) -> changed.to(now));
        r.addView(s);
        r.setOnClickListener(v -> s.toggle());
        group.addView(r);
    }

    private void update() {
        boolean fade = prefs.getBoolean(RadioService.PREF_FADE_ON, true);
        fadeRow.setAlpha(fade ? 1f : 0.45f);
        fadeBar.setEnabled(fade);
        fadeValue.setText(getString(R.string.seconds_short, fadeBar.getProgress() + 1));
        folderValue.setText(PackStore.folder(this) == null ? getString(R.string.settings_no_folder) : PackStore.folderName(this));
    }

    // ------------------------------------------------------------------------------------------ packs
    private void pickFolder() {
        try {
            startActivityForResult(folderPicker(), REQ_PICK);
        } catch (RuntimeException e) {
            AppLog.e("folder picker", e);
            Toast.makeText(this, getString(R.string.folder_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** Android's folder picker; the permission to read the folder is kept. */
    static Intent folderPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return i;
    }

    /** Use the folder the picker returned (data may be null: nothing was chosen). */
    static void useFolder(Context c, Intent data) {
        if (data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            c.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // some providers do not offer a persistable permission; it then works until the next restart
        }
        AppLog.d("folder chosen: " + uri);
        PackStore.setFolder(c, uri);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) useFolder(this, data);
    }

    private void reload() {
        PackStore.refresh(this, () -> {
            Catalog c = PackStore.get(this);
            Toast.makeText(this, getString(R.string.reloaded, c.stations.size(), c.podcasts.size(), c.problems.size()), Toast.LENGTH_SHORT).show();
            update();
        });
    }

    // ------------------------------------------------------------------------------------------ phone and app
    /** Opens Android's battery optimisation list so that the user can set this app to "not optimised". */
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.battery_failed, Toast.LENGTH_LONG).show();
        }
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private void showInfo() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        Catalog c = PackStore.get(this);
        int songs = 0;
        for (Library.Station s : c.stations) songs += s.songs.size();
        StringBuilder info = new StringBuilder(getString(R.string.info_body, version(), PackStore.folderName(this), c.packCount(),
                c.stations.size(), songs, f.format(new Date())));
        if (!c.podcasts.isEmpty()) info.append('\n').append(getString(R.string.info_podcasts, c.podcasts.size()));
        int pieces = 0;
        for (Library.Station g : c.scores) pieces += g.songs.size();
        if (pieces > 0) info.append('\n').append(getString(R.string.info_score, pieces));
        for (String p : c.problems) info.append('\n').append(getString(R.string.info_skipped, p));
        final String body = info.toString();
        final String log = AppLog.text();
        TextView tv = Ui.text(this, body + "\n\n" + log, 11, Ui.TEXT);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, R.style.AppDialog).setTitle(R.string.info_title).setView(sv)
                .setPositiveButton(R.string.info_close, null)
                .setNeutralButton(R.string.info_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("AWC Radio log", body + "\n\n" + log));
                    Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show();
                }).show();
    }
}
