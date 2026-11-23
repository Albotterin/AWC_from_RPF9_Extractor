package org.awctool.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Station tiles and a compact player panel. All the playing is done by RadioService. */
public class MainActivity extends Activity {
    private static final int BG = 0xFF14161C, PANEL = 0xFF1C1F27, CARD = 0xFF232733, SEL = 0xFF2A2F45,
            FG = 0xFFECEEF4, DIM = 0xFF8B91A3, ACCENT = 0xFFFF5A36;
    private static final int REQ_PICK = 42;
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Library.Station> stations;
    private Pack pack;
    private RadioService service;
    private boolean bound;
    private GridView grid;
    private StationAdapter adapter;

    private TextView lblStation, lblTitle, lblArtist, lblNext, lblPos, lblLen, lblSleep;
    private ImageView imgLogo;
    private ProgressBar bar;
    private ImageButton btnToggle, btnPrev, btnNext, btnSleep;
    private int shownStation = -2;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((RadioService.LocalBinder) binder).get();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            try {
                update();
            } catch (RuntimeException e) {
                AppLog.e("update", e);
            }
            handler.postDelayed(this, 250);
        }
    };

    // ------------------------------------------------------------------------------------------ small view helpers
    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private TextView text(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private Button button(String s, int bg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(rounded(bg, 24));
        b.setPadding(dp(24), dp(10), dp(24), dp(10));
        return b;
    }

    private ImageButton icon(int res, int descRes, int sizeDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(FG);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(sizeDp / 4f);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(getString(descRes));
        return b;
    }

    /** An image that is as high as it is wide, with rounded corners. */
    private final class SquareImage extends ImageView {
        SquareImage(Context c) {
            super(c);
            setScaleType(ScaleType.CENTER_CROP);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
            setMeasuredDimension(getMeasuredWidth(), getMeasuredWidth());
        }
    }

    // ------------------------------------------------------------------------------------------ screens
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(PANEL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(dp(20), dp(6), dp(6), dp(2));
        headRow.addView(text(getString(R.string.app_name), 24, FG, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton btnMenu = icon(R.drawable.ic_more, R.string.act_menu, 48);
        btnMenu.setOnClickListener(v -> showMenu(v));
        headRow.addView(btnMenu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(headRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pack = PackStore.get(this);
        stations = pack != null ? pack.stations() : null;
        if (stations == null || stations.isEmpty()) {
            root.addView(buildSetup(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            setContentView(root);
            return;
        }

        grid = new GridView(this);
        grid.setNumColumns(columns());
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setPadding(dp(14), dp(10), dp(14), dp(14));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        adapter = new StationAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> play(pos));
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildPanel(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private int columns() {
        int w = getResources().getConfiguration().screenWidthDp;
        return Math.max(2, Math.min(6, w / 165));
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        if (grid != null) grid.setNumColumns(columns());
    }

    /** First start (or the file is gone): just the icon and one button. */
    private View buildSetup() {
        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setGravity(Gravity.CENTER);
        setup.setPadding(dp(32), dp(24), dp(32), dp(48));
        ImageView ico = new ImageView(this);
        ico.setImageResource(R.mipmap.ic_launcher);
        setup.addView(ico, new LinearLayout.LayoutParams(dp(104), dp(104)));
        TextView msg = text(getString(R.string.setup_hint), 15, DIM, false);
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(20), 0, dp(20));
        setup.addView(msg);
        if (pack == null && !PackStore.error.isEmpty()) {
            TextView err = text(getString(R.string.pack_open_error, PackStore.error), 13, 0xFFFF8866, false);
            err.setGravity(Gravity.CENTER);
            err.setPadding(0, 0, 0, dp(16));
            setup.addView(err);
        }
        Button pick = button(getString(R.string.choose_pack), ACCENT);
        pick.setOnClickListener(v -> pickPack());
        setup.addView(pick);
        return setup;
    }

    private View buildPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PANEL);
        bg.setCornerRadii(new float[]{dp(22), dp(22), dp(22), dp(22), 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setPadding(dp(18), dp(16), dp(18), dp(8));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        imgLogo = new SquareImage(this);
        top.addView(imgLogo, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        lblStation = text("", 11, ACCENT, true);
        lblTitle = text(getString(R.string.tap_station), 17, FG, true);
        lblTitle.setSingleLine(true);
        lblTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lblArtist = text("", 13, DIM, false);
        lblArtist.setSingleLine(true);
        lblArtist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(lblStation);
        col.addView(lblTitle);
        col.addView(lblArtist);
        top.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(top);

        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setPadding(0, dp(12), 0, 0);
        lblPos = text("0:00", 11, DIM, false);
        lblLen = text("0:00", 11, DIM, false);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD));
        barRow.addView(lblPos);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        blp.leftMargin = dp(8);
        blp.rightMargin = dp(8);
        barRow.addView(bar, blp);
        barRow.addView(lblLen);
        panel.addView(barRow);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        btnPrev = icon(R.drawable.ic_prev, R.string.act_prev, 56);
        btnToggle = icon(R.drawable.ic_pause, R.string.act_pause, 72);
        btnNext = icon(R.drawable.ic_next, R.string.act_next, 56);
        btnSleep = icon(R.drawable.ic_timer, R.string.menu_sleep, 44);
        btnPrev.setOnClickListener(v -> command(RadioService.ACTION_PREV));
        btnNext.setOnClickListener(v -> command(RadioService.ACTION_NEXT));
        btnToggle.setOnClickListener(v -> toggle());
        btnSleep.setOnClickListener(v -> showSleepDialog());
        ctl.addView(btnPrev, new LinearLayout.LayoutParams(dp(56), dp(56)));
        ctl.addView(btnToggle, new LinearLayout.LayoutParams(dp(72), dp(72)));
        ctl.addView(btnNext, new LinearLayout.LayoutParams(dp(56), dp(56)));
        panel.addView(ctl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        lblNext = text("", 12, DIM, false);
        lblNext.setSingleLine(true);
        lblNext.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bottom.addView(lblNext, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lblSleep = text("", 11, ACCENT, false);
        bottom.addView(lblSleep);
        bottom.addView(btnSleep, new LinearLayout.LayoutParams(dp(44), dp(44)));
        panel.addView(bottom);
        return panel;
    }

    // ------------------------------------------------------------------------------------------ lifecycle
    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        if (bound) {
            try {
                unbindService(conn);
            } catch (IllegalArgumentException ignored) {
            }
            bound = false;
        }
        service = null;
        super.onStop();
    }

    // ------------------------------------------------------------------------------------------ commands
    private void play(int index) {
        Intent i = new Intent(this, RadioService.class).setAction(RadioService.ACTION_PLAY).putExtra(RadioService.EXTRA_STATION, index);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void command(String action) {
        if (service == null || service.state == RadioService.STOPPED) return;
        startService(new Intent(this, RadioService.class).setAction(action));
    }

    private void toggle() {
        if (service != null && service.state != RadioService.STOPPED) {
            command(RadioService.ACTION_TOGGLE);
        } else if (stations != null && !stations.isEmpty()) {
            play(getSharedPreferences("radio", MODE_PRIVATE).getInt("last", 0));      // continue with the last station
        }
    }

    private void setSleep(int minutes) {
        if (service == null || service.state == RadioService.STOPPED) {
            Toast.makeText(this, R.string.sleep_needs_play, Toast.LENGTH_SHORT).show();
            return;
        }
        startService(new Intent(this, RadioService.class).setAction(RadioService.ACTION_SLEEP).putExtra(RadioService.EXTRA_MINUTES, minutes));
        Toast.makeText(this, minutes > 0 ? getString(R.string.sleep_set, minutes) : getString(R.string.sleep_cleared), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------------------------------ menu and dialogs
    private void showMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 1, R.string.menu_sleep);
        m.getMenu().add(0, 2, 2, R.string.menu_pack);
        m.getMenu().add(0, 3, 3, R.string.menu_battery);
        m.getMenu().add(0, 4, 4, R.string.menu_info);
        m.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    showSleepDialog();
                    break;
                case 2:
                    pickPack();
                    break;
                case 3:
                    openBatterySettings();
                    break;
                case 4:
                    showInfo();
                    break;
                default:
                    break;
            }
            return true;
        });
        m.show();
    }

    private void showSleepDialog() {
        String[] labels = new String[SLEEP_MINUTES.length];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = SLEEP_MINUTES[i] == 0 ? getString(R.string.sleep_off) : getString(R.string.sleep_minutes, SLEEP_MINUTES[i]);
        }
        new AlertDialog.Builder(this).setTitle(R.string.sleep_title)
                .setItems(labels, (d, which) -> setSleep(SLEEP_MINUTES[which])).show();
    }

    /** Opens Android's battery optimisation list so that the user can set this app to "not optimised". */
    private void openBatterySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.battery_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void showInfo() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        int songs = 0;
        if (stations != null) for (Library.Station s : stations) songs += s.songs.size();
        final String body = getString(R.string.info_body, version, pack != null ? "OK" : "-", pack != null ? pack.created : "-",
                stations != null ? stations.size() : 0, songs, f.format(new Date()));
        final String log = AppLog.text();
        TextView tv = text(body + "\n\n" + log, 11, FG, false);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(R.string.info_title).setView(sv)
                .setPositiveButton(R.string.info_close, null)
                .setNeutralButton(R.string.info_copy, (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("AWC Radio log", body + "\n\n" + log));
                    Toast.makeText(this, R.string.info_copied, Toast.LENGTH_SHORT).show();
                }).show();
    }

    /** Let the user pick the station pack file (Android's file picker; the permission to read it is kept). */
    private void pickPack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // some providers do not offer a persistable permission; it then works until the next restart
        }
        try {
            if (service != null && service.state != RadioService.STOPPED) command(RadioService.ACTION_STOP);
            PackStore.set(this, uri);
            Logos.clear();
            AppLog.d("station pack chosen: " + uri);
            recreate();
        } catch (Exception e) {
            AppLog.d("pack rejected: " + e);
            Toast.makeText(this, getString(R.string.pack_invalid, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------------------------------ refresh
    private static String fmt(double sec) {
        int s = (int) Math.max(0, sec);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void update() {
        if (lblTitle == null) return;
        RadioService s = service;
        int st = s == null ? RadioService.STOPPED : s.state;
        boolean active = s != null && st != RadioService.STOPPED;
        int cur = active ? s.stationIndex : -1;
        if (cur != shownStation) {                                      // station changed: logo, keep it in view
            shownStation = cur;
            int idx = cur >= 0 ? cur : getSharedPreferences("radio", MODE_PRIVATE).getInt("last", -1);
            if (idx >= 0 && idx < stations.size()) imgLogo.setImageBitmap(Logos.get(pack, stations.get(idx), 256));
            else imgLogo.setImageDrawable(null);
            if (cur >= 0 && (cur < grid.getFirstVisiblePosition() || cur > grid.getLastVisiblePosition())) grid.smoothScrollToPosition(cur);
        }
        if (!active) {
            lblStation.setText("");
            lblTitle.setText(R.string.tap_station);
            lblArtist.setText("");
            lblNext.setText("");
            lblSleep.setText("");
            bar.setProgress(0);
            lblPos.setText(fmt(0));
            lblLen.setText(fmt(0));
            btnPrev.setEnabled(false);
            btnNext.setEnabled(false);
            btnPrev.setAlpha(0.35f);
            btnNext.setAlpha(0.35f);
            btnToggle.setImageResource(R.drawable.ic_play);
            btnToggle.setContentDescription(getString(R.string.act_play));
        } else {
            lblStation.setText(getString(R.string.station_live, s.stationName.toUpperCase(Locale.getDefault())));
            lblTitle.setText(s.title.isEmpty() ? getString(R.string.loading) : s.title);
            lblArtist.setText(s.artist);
            lblNext.setText(s.nextText.isEmpty() ? "" : getString(R.string.up_next, s.nextText));
            double now = System.currentTimeMillis() / 1000.0;
            double len = Math.max(1.0, s.itemEnd - s.itemStart);
            double pos = s.itemStart > 0 ? Math.max(0, now - s.itemStart) : 0;
            bar.setProgress((int) Math.min(1000, 1000 * pos / len));
            lblPos.setText(fmt(pos));
            lblLen.setText(fmt(s.itemStart > 0 ? len : 0));
            btnPrev.setEnabled(true);
            btnNext.setEnabled(true);
            btnPrev.setAlpha(1f);
            btnNext.setAlpha(1f);
            boolean paused = st == RadioService.PAUSED;
            btnToggle.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
            btnToggle.setContentDescription(getString(paused ? R.string.act_play : R.string.act_pause));
            long left = s.sleepAt - System.currentTimeMillis();
            lblSleep.setText(s.sleepAt > 0 && left > 0 ? getString(R.string.sleep_active, (int) ((left + 59_999) / 60_000)) + "  " : "");
        }
        if (adapter != null) adapter.setCurrent(cur);
    }

    // ------------------------------------------------------------------------------------------ station tiles
    private final class StationAdapter extends BaseAdapter {
        private int current = -1;

        void setCurrent(int c) {
            if (c != current) {
                current = c;
                notifyDataSetChanged();
            }
        }

        @Override
        public int getCount() {
            return stations.size();
        }

        @Override
        public Object getItem(int i) {
            return stations.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Library.Station st = stations.get(i);
            boolean on = i == current;
            LinearLayout card = new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable bg = rounded(on ? SEL : CARD, 16);
            if (on) bg.setStroke(dp(2), ACCENT);
            card.setBackground(bg);
            card.setPadding(dp(8), dp(8), dp(8), dp(10));
            card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout logoBox = new FrameLayout(MainActivity.this);
            ImageView img = new SquareImage(MainActivity.this);
            img.setImageBitmap(Logos.get(pack, st, 256));
            logoBox.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (on) {
                TextView live = text(getString(R.string.live), 10, Color.WHITE, true);
                live.setBackground(rounded(ACCENT, 8));
                live.setPadding(dp(7), dp(2), dp(7), dp(2));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.END;
                lp.setMargins(0, dp(6), dp(6), 0);
                logoBox.addView(live, lp);
            }
            card.addView(logoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = text(st.name, 14, on ? ACCENT : FG, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(8), dp(2), 0);
            card.addView(name);
            TextView sub = text(getString(R.string.songs_count, st.songs.size()), 11, DIM, false);
            sub.setPadding(dp(2), dp(1), dp(2), 0);
            card.addView(sub);
            return card;
        }
    }
}
