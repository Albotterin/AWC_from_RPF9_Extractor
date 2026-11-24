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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
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
    private static final int BG = 0xFF12141B, PANEL = 0xFF1A1D27, CARD = 0xFF212532, SEL = 0xFF2B2F47,
            FG = 0xFFF1F2F7, DIM = 0xFF9096A8, ACCENT = 0xFFFF5A36, HEAD = 0xFF2E1B52, RIPPLE = 0x33FFFFFF;
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
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setStateListAnimator(null);
        b.setBackground(ripple(rounded(bg, 26), rounded(Color.WHITE, 26)));
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        return b;
    }

    /** Three bars that dance while the station plays (and rest while it is paused): the "this is on air" sign. */
    private final class EqView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean dancing = true;

        EqView(Context c) {
            super(c);
            paint.setColor(ACCENT);
        }

        void setDancing(boolean on) {
            if (on != dancing) {
                dancing = on;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), bw = w / 5f;
            long t = SystemClock.uptimeMillis();
            for (int i = 0; i < 3; i++) {
                double f = dancing ? 0.3 + 0.7 * Math.abs(Math.sin(t / 240.0 * (1 + 0.43 * i) + i * 1.7)) : 0.3;
                float bh = (float) (h * f);
                c.drawRoundRect(i * 2 * bw, h - bh, i * 2 * bw + bw, h, bw / 2, bw / 2, paint);
            }
            if (dancing) postInvalidateOnAnimation();
        }
    }

    /** The thin progress line of the song: rounded, the accent colour on a dark track. */
    private Drawable progressLine() {
        GradientDrawable track = rounded(CARD, 3);
        ClipDrawable fill = new ClipDrawable(rounded(ACCENT, 3), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{track, fill});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    /** The touch feedback that every modern Android control has: a ripple that stays inside the shape. */
    private Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
    }

    private Drawable circleMask() {
        return new ShapeDrawable(new OvalShape());
    }

    private ImageButton icon(int res, int descRes, int sizeDp) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(res);
        b.setColorFilter(FG);
        b.setBackground(ripple(null, circleMask()));
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
        getWindow().setStatusBarColor(HEAD);
        getWindow().setNavigationBarColor(PANEL);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        pack = PackStore.get(this);
        stations = pack != null ? pack.stations() : null;

        // header: a soft sunset gradient (the colours of the icon) with the logo, the name and what is on offer
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        headRow.setPadding(dp(16), dp(10), dp(6), dp(14));
        headRow.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{HEAD, BG}));
        ImageView mark = new ImageView(this);
        mark.setImageResource(R.drawable.logo);
        mark.setClipToOutline(true);
        mark.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        headRow.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text(getString(R.string.app_name), 22, FG, true));
        if (stations != null && !stations.isEmpty()) {
            titles.addView(text(getString(R.string.header_sub, stations.size()), 12, DIM, false));
        }
        headRow.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton btnMenu = icon(R.drawable.ic_more, R.string.act_menu, 48);
        btnMenu.setOnClickListener(v -> showMenu(v));
        headRow.addView(btnMenu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(headRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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
        ico.setImageResource(R.drawable.logo);
        ico.setClipToOutline(true);
        ico.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(26));
            }
        });
        ico.setElevation(dp(6));
        setup.addView(ico, new LinearLayout.LayoutParams(dp(112), dp(112)));
        TextView head = text(getString(R.string.setup_title), 22, FG, true);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(24), 0, dp(8));
        setup.addView(head);
        TextView msg = text(getString(R.string.setup_hint), 15, DIM, false);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.15f);
        msg.setPadding(0, 0, 0, dp(24));
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
        bg.setCornerRadii(new float[]{dp(26), dp(26), dp(26), dp(26), 0, 0, 0, 0});
        panel.setBackground(bg);
        panel.setElevation(dp(14));                                    // lifts the player off the station tiles
        panel.setPadding(dp(20), dp(18), dp(20), dp(6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        imgLogo = new SquareImage(this);
        top.addView(imgLogo, new LinearLayout.LayoutParams(dp(64), dp(64)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        lblStation = text("", 11, ACCENT, true);
        lblStation.setLetterSpacing(0.08f);
        lblTitle = text(getString(R.string.tap_station), 18, FG, true);
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
        bar.setProgressDrawable(progressLine());
        barRow.addView(lblPos);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(5), 1f);
        blp.leftMargin = dp(10);
        blp.rightMargin = dp(10);
        barRow.addView(bar, blp);
        barRow.addView(lblLen);
        panel.addView(barRow);

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        btnPrev = icon(R.drawable.ic_prev, R.string.act_prev, 56);
        btnToggle = icon(R.drawable.ic_pause, R.string.act_pause, 64);
        btnToggle.setBackground(ripple(rounded(ACCENT, 40), circleMask()));      // the one filled button: play / pause
        btnToggle.setPadding(dp(16), dp(16), dp(16), dp(16));
        btnToggle.setElevation(dp(4));
        btnNext = icon(R.drawable.ic_next, R.string.act_next, 56);
        btnSleep = icon(R.drawable.ic_timer, R.string.menu_sleep, 44);
        btnPrev.setOnClickListener(v -> command(RadioService.ACTION_PREV));
        btnNext.setOnClickListener(v -> command(RadioService.ACTION_NEXT));
        btnToggle.setOnClickListener(v -> toggle());
        btnSleep.setOnClickListener(v -> showSleepDialog());
        LinearLayout.LayoutParams cm = new LinearLayout.LayoutParams(dp(64), dp(64));
        cm.setMargins(dp(18), dp(6), dp(18), dp(6));
        ctl.addView(btnPrev, new LinearLayout.LayoutParams(dp(56), dp(56)));
        ctl.addView(btnToggle, cm);
        ctl.addView(btnNext, new LinearLayout.LayoutParams(dp(56), dp(56)));
        panel.addView(ctl);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        lblNext = text("", 12, DIM, false);
        lblNext.setPadding(dp(2), 0, 0, 0);
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
    /** "Hey Google, play <station> on AWC Radio" starts the app with this intent (the query is empty for "play something"). */
    private void handleVoice(Intent in) {
        if (in == null || !"android.media.action.MEDIA_PLAY_FROM_SEARCH".equals(in.getAction())) return;
        in.setAction(Intent.ACTION_MAIN);                                  // handle it once
        if (stations == null || stations.isEmpty()) return;
        String q = in.getStringExtra(android.app.SearchManager.QUERY);
        Intent i = new Intent(this, RadioService.class).setAction(RadioService.ACTION_PLAY).putExtra(RadioService.EXTRA_QUERY, q == null ? "" : q);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    @Override
    protected void onNewIntent(Intent in) {
        super.onNewIntent(in);
        setIntent(in);
        handleVoice(in);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        handler.post(refresh);
        handleVoice(getIntent());
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
        btnSleep.setColorFilter(active && s.sleepAt > System.currentTimeMillis() ? ACCENT : DIM);
        if (adapter != null) adapter.setCurrent(cur, active && st == RadioService.PLAYING);
    }

    // ------------------------------------------------------------------------------------------ station tiles
    private final class StationAdapter extends BaseAdapter {
        private int current = -1;
        private boolean playing;
        private EqView eq;                                             // the dancing bars of the station on air

        void setCurrent(int c, boolean isPlaying) {
            if (c != current) {
                current = c;
                playing = isPlaying;
                notifyDataSetChanged();
            } else if (isPlaying != playing) {
                playing = isPlaying;
                if (eq != null) eq.setDancing(isPlaying);
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
            GradientDrawable bg = rounded(on ? SEL : CARD, 18);
            if (on) bg.setStroke(dp(2), ACCENT);
            card.setBackground(ripple(bg, rounded(Color.WHITE, 18)));
            card.setPadding(dp(9), dp(9), dp(9), dp(11));
            card.setContentDescription(st.name);
            card.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout logoBox = new FrameLayout(MainActivity.this);
            ImageView img = new SquareImage(MainActivity.this);
            img.setImageBitmap(Logos.get(pack, st, 256));
            logoBox.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (on) {
                LinearLayout pill = new LinearLayout(MainActivity.this);
                pill.setOrientation(LinearLayout.HORIZONTAL);
                pill.setGravity(Gravity.CENTER_VERTICAL);
                pill.setBackground(rounded(0xCC0B0C11, 10));
                pill.setPadding(dp(8), dp(4), dp(9), dp(4));
                eq = new EqView(MainActivity.this);
                eq.setDancing(playing);
                pill.addView(eq, new LinearLayout.LayoutParams(dp(13), dp(11)));
                TextView live = text(getString(R.string.live), 10, Color.WHITE, true);
                live.setLetterSpacing(0.1f);
                live.setPadding(dp(6), 0, 0, 0);
                pill.addView(live);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.setMargins(dp(7), dp(7), 0, 0);
                logoBox.addView(pill, lp);
            }
            card.addView(logoBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView name = text(st.name, 14, on ? ACCENT : FG, true);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(2), dp(9), dp(2), 0);
            card.addView(name);
            TextView sub = text(getString(R.string.songs_count, st.songs.size()), 11, DIM, false);
            sub.setPadding(dp(2), dp(1), dp(2), 0);
            card.addView(sub);
            return card;
        }
    }
}
