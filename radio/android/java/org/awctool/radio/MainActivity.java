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
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The two tabs of the app (the stations and the alarms), the small player at the bottom and the large one over the
 * whole screen. All the playing is done by RadioService.
 */
public class MainActivity extends Activity implements PackStore.Listener {
    static final String ACTION_SHOW_ALARMS = "org.awctool.radio.SHOW_ALARMS";
    private static final int REQ_PICK = 42;
    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60, 90};
    private static final int TAB_RADIO = 0, TAB_ALARMS = 1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Library.Station> stations;
    private RadioService service;
    private boolean bound;
    private int tab = TAB_RADIO;
    private boolean playerOpen;

    private FrameLayout root;
    private GridView grid;
    private StationAdapter adapter;
    private AlarmList alarms;
    private PlayerScreen player;
    private TextView headline, alarmHint;
    private LinearLayout alarmChip;

    private LinearLayout mini;
    private ImageView miniLogo;
    private TextView miniTitle, miniSub;
    private ImageButton miniToggle, miniNext;
    private ProgressBar miniBar;
    private Object miniShown;
    private int scrolledTo = -1, ticks;

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

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    // ------------------------------------------------------------------------------------------ screens
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            tab = state.getInt("tab", TAB_RADIO);
            playerOpen = state.getBoolean("player", false);
        }
        if (ACTION_SHOW_ALARMS.equals(getIntent().getAction())) tab = TAB_ALARMS;
        buildScreen();
        askForNotifications();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putBoolean("player", playerOpen);
    }

    private void askForNotifications() {
        if (!stations.isEmpty() || !Alarms.list(this).isEmpty()) AlarmList.askForNotifications(this);
    }

    /** The whole screen for the current station list; called again when packs come or go. */
    private void buildScreen() {
        grid = null;
        adapter = null;
        alarms = null;
        mini = null;
        miniShown = null;
        player = null;
        alarmChip = null;
        scrolledTo = -1;
        stations = PackStore.get(this).stations;

        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.NIGHT);
        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        root.addView(main);
        boolean low = getResources().getConfiguration().screenHeightDp < 480;           // a phone held sideways

        // header: the name in the lettering of the icon; under it the next alarm, if one is set
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(low ? 4 : 14), dp(8), dp(low ? 2 : 8));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(low ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        titles.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        headline = Ui.display(this, getString(tab == TAB_ALARMS ? R.string.tab_alarms : R.string.app_name), low ? 26 : 34, Ui.TEXT);
        headline.setIncludeFontPadding(true);
        titles.addView(headline);
        if (tab == TAB_RADIO) {
            alarmChip = new LinearLayout(this);
            alarmChip.setGravity(Gravity.CENTER_VERTICAL);
            alarmChip.setPadding(0, dp(4), dp(12), dp(4));
            if (low) alarmChip.setPadding(dp(20), dp(4), dp(12), dp(4));
            ImageView bell = new ImageView(this);
            bell.setImageResource(R.drawable.ic_alarm);
            bell.setColorFilter(Ui.SUN);
            alarmChip.addView(bell, new LinearLayout.LayoutParams(dp(16), dp(16)));
            alarmHint = Ui.text(this, "", 13, Ui.MUTED);
            alarmHint.setPadding(dp(6), 0, 0, 0);
            alarmChip.addView(alarmHint);
            alarmChip.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
            alarmChip.setClickable(true);
            alarmChip.setFocusable(true);
            alarmChip.setOnClickListener(v -> showTab(TAB_ALARMS));
            alarmChip.setVisibility(View.GONE);
            titles.addView(alarmChip);
        }
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton menu = Ui.icon(this, R.drawable.ic_more, getString(R.string.act_menu), 48, Ui.TEXT);
        menu.setOnClickListener(v -> showMenu());
        head.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        main.addView(head);

        LinearLayout.LayoutParams fill = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (tab == TAB_ALARMS) {
            alarms = new AlarmList(this);
            main.addView(alarms, fill);
        } else if (stations.isEmpty()) {
            main.addView(buildSetup(), fill);
        } else {
            grid = new GridView(this);
            grid.setNumColumns(columns());
            grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(8));
            grid.setVerticalSpacing(dp(10));
            grid.setPadding(dp(12), dp(2), dp(12), dp(12));
            grid.setClipToPadding(false);
            grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
            grid.setVerticalScrollBarEnabled(false);
            grid.setVerticalFadingEdgeEnabled(true);
            grid.setFadingEdgeLength(dp(18));
            adapter = new StationAdapter();
            grid.setAdapter(adapter);
            grid.setOnItemClickListener((p, v, pos, id) -> play(pos));
            main.addView(grid, fill);
        }
        if (!stations.isEmpty()) main.addView(buildMini());
        main.addView(buildTabs(low));

        if (!stations.isEmpty()) {
            player = new PlayerScreen(this, low, new PlayerScreen.Actions() {
                @Override
                public void close() {
                    openPlayer(false);
                }

                @Override
                public void toggle() {
                    MainActivity.this.toggle();
                }

                @Override
                public void skip(int delta) {
                    MainActivity.this.skip(delta);
                }

                @Override
                public void sleep() {
                    showSleepSheet();
                }

                @Override
                public void alarm() {
                    Library.Station st = shownStation();
                    AlarmEditActivity.open(MainActivity.this, 0, st == null ? null : st.name);
                }
            });
            player.setVisibility(playerOpen ? View.VISIBLE : View.GONE);
            root.addView(player);
        } else {
            playerOpen = false;
        }
        setContentView(root);
        update();
        getWindow().setStatusBarColor(playerOpen && player != null ? player.color() : Ui.NIGHT);
        getWindow().setNavigationBarColor(playerOpen ? Ui.NIGHT : Ui.SURFACE);
    }

    private void showTab(int which) {
        if (tab == which) return;
        tab = which;
        buildScreen();
    }

    private View buildTabs(boolean low) {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(Ui.SURFACE);
        bar.addView(tabButton(TAB_RADIO, R.drawable.ic_radio, R.string.tab_radio, low), new LinearLayout.LayoutParams(0, dp(low ? 48 : 68), 1f));
        bar.addView(tabButton(TAB_ALARMS, R.drawable.ic_alarm, R.string.tab_alarms, low), new LinearLayout.LayoutParams(0, dp(low ? 48 : 68), 1f));
        return bar;
    }

    private View tabButton(final int which, int icon, int label, boolean low) {
        boolean on = tab == which;
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(low ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        ImageView i = new ImageView(this);
        i.setImageResource(icon);
        i.setColorFilter(on ? Ui.SUN : Ui.MUTED);
        i.setPadding(dp(18), dp(4), dp(18), dp(4));
        if (on) i.setBackground(Ui.rounded(this, Ui.RAISED, 16));
        t.addView(i, new LinearLayout.LayoutParams(dp(60), dp(32)));
        TextView n = Ui.strong(this, getString(label), 12, on ? Ui.TEXT : Ui.MUTED);
        n.setPadding(low ? dp(6) : 0, low ? 0 : dp(3), 0, 0);
        t.addView(n, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        t.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        t.setClickable(true);
        t.setFocusable(true);
        t.setSelected(on);
        t.setContentDescription(getString(label));
        t.setOnClickListener(v -> showTab(which));
        return t;
    }

    /** The small player: what is on (or what was on last), play/pause, next; a tap opens the large one. */
    private View buildMini() {
        mini = new LinearLayout(this);
        mini.setOrientation(LinearLayout.VERTICAL);
        Ui.clip(mini, 20);
        mini.setClickable(true);
        mini.setFocusable(true);
        mini.setOnClickListener(v -> openPlayer(true));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(10), dp(9), dp(4), dp(7));
        miniLogo = new Ui.SquareImage(this, 12);
        line.addView(miniLogo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(4), 0);
        miniTitle = Ui.oneLine(Ui.strong(this, "", 15, Color.WHITE));
        miniSub = Ui.oneLine(Ui.text(this, "", 12, 0xCCFFFFFF));
        col.addView(miniTitle);
        col.addView(miniSub);
        line.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        miniToggle = Ui.icon(this, R.drawable.ic_play, getString(R.string.act_play), 48, Color.WHITE);
        miniToggle.setOnClickListener(v -> toggle());
        line.addView(miniToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        miniNext = Ui.icon(this, R.drawable.ic_next, getString(R.string.act_next), 48, Color.WHITE);
        miniNext.setOnClickListener(v -> skip(1));
        line.addView(miniNext, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mini.addView(line);
        miniBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniBar.setMax(1000);
        miniBar.setProgressDrawable(Ui.progressLine(this, 0x33FFFFFF, Color.WHITE));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        bp.setMargins(dp(14), 0, dp(14), dp(1));
        mini.addView(miniBar, bp);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(10), dp(4), dp(10), dp(8));
        mini.setLayoutParams(lp);
        return mini;
    }

    private void openPlayer(boolean open) {
        if (player == null || open == playerOpen) return;
        playerOpen = open;
        player.animate().cancel();
        if (open) {
            player.setVisibility(View.VISIBLE);
            player.setTranslationY(root.getHeight());
            player.animate().translationY(0).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            update();
            getWindow().setStatusBarColor(player.color());
            getWindow().setNavigationBarColor(Ui.NIGHT);
        } else {
            player.animate().translationY(root.getHeight()).setDuration(200).withEndAction(() -> {
                if (!playerOpen && player != null) player.setVisibility(View.GONE);
            }).start();
            getWindow().setStatusBarColor(Ui.NIGHT);
            getWindow().setNavigationBarColor(Ui.SURFACE);
        }
    }

    @Override
    public void onBackPressed() {
        if (playerOpen) openPlayer(false);
        else if (tab != TAB_RADIO) showTab(TAB_RADIO);
        else super.onBackPressed();
    }

    @Override
    public void onStationsChanged() {
        int top = grid != null ? grid.getFirstVisiblePosition() : 0;
        buildScreen();
        if (grid != null) grid.setSelection(top);
        askForNotifications();
        handleVoice(getIntent());
    }

    private int columns() {
        int w = getResources().getConfiguration().screenWidthDp;
        return Math.max(3, Math.min(8, w / 118));
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        int top = grid != null ? grid.getFirstVisiblePosition() : 0;
        buildScreen();
        if (grid != null) grid.setSelection(top);
    }

    /** No station yet (first start, or an empty folder): the icon, what to do and the button. */
    private View buildSetup() {
        LinearLayout setup = new LinearLayout(this);
        setup.setOrientation(LinearLayout.VERTICAL);
        setup.setGravity(Gravity.CENTER);
        setup.setPadding(dp(32), dp(16), dp(32), dp(32));
        ImageView ico = new ImageView(this);
        ico.setImageResource(R.drawable.logo);
        Ui.clip(ico, 30);
        ico.setElevation(dp(10));
        setup.addView(ico, new LinearLayout.LayoutParams(dp(128), dp(128)));
        boolean chosen = PackStore.folder(this) != null;
        TextView head = Ui.display(this, getString(chosen ? R.string.folder_empty_title : R.string.setup_title), 30, Ui.TEXT);
        head.setIncludeFontPadding(true);
        head.setGravity(Gravity.CENTER);
        head.setPadding(0, dp(20), 0, dp(8));
        setup.addView(head);
        TextView msg = Ui.text(this, chosen ? getString(R.string.folder_empty, PackStore.folderName(this)) : getString(R.string.setup_hint), 15, Ui.MUTED);
        msg.setGravity(Gravity.CENTER);
        msg.setLineSpacing(0, 1.15f);
        msg.setPadding(0, 0, 0, dp(24));
        setup.addView(msg);
        List<String> wrong = new ArrayList<>(PackStore.get(this).problems);
        if (!PackStore.error.isEmpty()) wrong.add(0, getString(R.string.folder_error, PackStore.error));
        if (!wrong.isEmpty()) {
            TextView err = Ui.text(this, android.text.TextUtils.join("\n", wrong.subList(0, Math.min(5, wrong.size()))), 13, Ui.WARN);
            err.setGravity(Gravity.CENTER);
            err.setPadding(0, 0, 0, dp(16));
            setup.addView(err);
        }
        TextView pick = Ui.button(this, getString(R.string.choose_folder), !chosen);
        pick.setOnClickListener(v -> pickFolder());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (chosen) {
            TextView again = Ui.button(this, getString(R.string.menu_reload), true);
            again.setOnClickListener(v -> reload());
            setup.addView(again, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            lp.topMargin = dp(12);
        }
        setup.addView(pick, lp);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(setup);
        return sv;
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
        if (ACTION_SHOW_ALARMS.equals(in.getAction())) {
            playerOpen = false;
            tab = TAB_ALARMS;
            buildScreen();
        }
        handleVoice(in);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, RadioService.class), conn, Context.BIND_AUTO_CREATE);
        PackStore.addListener(this);
        if (stations != PackStore.get(this).stations) buildScreen();      // changed while we were away
        else if (alarms != null) alarms.refresh();                         // back from the editor or the settings
        PackStore.refresh(this, null);                                     // new files in the folder?
        handler.post(refresh);
        handleVoice(getIntent());
        // an alarm that is ringing comes first (also when Android could not show its screen by itself)
        if (AlarmService.running != null) startActivity(new Intent(this, AlarmActivity.class));
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        PackStore.removeListener(this);
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

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (alarms != null) alarms.refresh();
    }

    // ------------------------------------------------------------------------------------------ commands
    private void play(int index) {
        if (index < 0 || index >= stations.size()) return;
        Intent i = new Intent(this, RadioService.class).setAction(RadioService.ACTION_PLAY)
                .putExtra(RadioService.EXTRA_STATION, stations.get(index).name);
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
            play(Math.max(0, lastIndex()));                                            // continue with the last station
        }
    }

    /** Previous or next station; with a stopped radio it starts the neighbour of the last one. */
    private void skip(int delta) {
        if (service != null && service.state != RadioService.STOPPED) {
            command(delta < 0 ? RadioService.ACTION_PREV : RadioService.ACTION_NEXT);
        } else if (stations != null && !stations.isEmpty()) {
            int n = stations.size();
            play(((Math.max(0, lastIndex()) + delta) % n + n) % n);
        }
    }

    private int lastIndex() {
        return Catalog.indexOf(stations, getSharedPreferences(RadioService.PREFS, MODE_PRIVATE).getString(RadioService.PREF_LAST, ""));
    }

    /** The station on air, else the one that "play" would start. */
    private Library.Station shownStation() {
        if (stations == null || stations.isEmpty()) return null;
        int i = service != null && service.state != RadioService.STOPPED ? Catalog.indexOf(stations, service.stationName) : -1;
        if (i < 0) i = Math.max(0, lastIndex());
        return stations.get(i);
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
    private void showMenu() {
        List<Ui.Choice> m = new ArrayList<>();
        m.add(new Ui.Choice(getString(R.string.menu_sleep), null, false, this::showSleepSheet));
        m.add(new Ui.Choice(getString(R.string.menu_reload), null, false, this::reload));
        m.add(new Ui.Choice(getString(R.string.menu_folder), null, false, this::pickFolder));
        m.add(new Ui.Choice(getString(R.string.menu_battery), null, false, this::openBatterySettings));
        m.add(new Ui.Choice(getString(R.string.menu_info), null, false, this::showInfo));
        Ui.sheet(this, getString(R.string.app_name), m);
    }

    private void showSleepSheet() {
        List<Ui.Choice> out = new ArrayList<>();
        boolean off = service == null || service.sleepAt <= System.currentTimeMillis();
        for (int min : SLEEP_MINUTES) {
            out.add(new Ui.Choice(min == 0 ? getString(R.string.sleep_off) : getString(R.string.sleep_minutes, min), null,
                    min == 0 && off, () -> setSleep(min)));
        }
        Ui.sheet(this, getString(R.string.sleep_title), out);
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
        for (Library.Station s : stations) songs += s.songs.size();
        Catalog c = PackStore.get(this);
        StringBuilder info = new StringBuilder(getString(R.string.info_body, version, PackStore.folderName(this), c.packCount(),
                stations.size(), songs, f.format(new Date())));
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

    /** Let the user pick the folder with the station packs (Android's folder picker; the permission is kept). */
    private void pickFolder() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (RuntimeException e) {
            AppLog.e("folder picker", e);
            Toast.makeText(this, getString(R.string.folder_error, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void reload() {
        PackStore.refresh(this, () -> {
            Catalog c = PackStore.get(this);
            Toast.makeText(this, getString(R.string.reloaded, c.stations.size(), c.problems.size()), Toast.LENGTH_SHORT).show();
        });
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
        AppLog.d("folder chosen: " + uri);
        PackStore.setFolder(this, uri);
    }

    // ------------------------------------------------------------------------------------------ refresh
    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    private void update() {
        ticks++;
        if (alarms != null && ticks % 20 == 0) alarms.tick();
        if (alarmChip != null && (ticks % 20 == 0 || alarmChip.getVisibility() == View.GONE)) {
            long at = Alarms.nextAt(this);
            alarmChip.setVisibility(at == 0 ? View.GONE : View.VISIBLE);
            if (at != 0) set(alarmHint, getString(R.string.alarm_chip, Ui.until(this, at, System.currentTimeMillis())));
        }
        if (stations == null || stations.isEmpty()) return;
        RadioService s = service;
        int state = s == null ? RadioService.STOPPED : s.state;
        boolean active = s != null && state != RadioService.STOPPED;
        boolean playing = active && state == RadioService.PLAYING;
        int cur = active ? Catalog.indexOf(stations, s.stationName) : -1;
        Library.Station st = shownStation();
        if (mini != null && st != null) {
            if (st != miniShown) {
                miniShown = st;
                miniLogo.setImageBitmap(Logos.get(st, 128));
                GradientDrawable bg = Ui.rounded(this, Logos.color(st).tint, 20);
                mini.setBackground(Ui.ripple(bg, Ui.rounded(this, Color.WHITE, 20)));
            }
            set(miniTitle, !active ? st.name : s.title.isEmpty() ? getString(R.string.loading) : s.title);
            set(miniSub, !active ? getString(R.string.tap_play) : s.artist.isEmpty() ? st.name : st.name + " • " + s.artist);
            miniToggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            miniToggle.setContentDescription(getString(playing ? R.string.act_pause : R.string.act_play));
            double len = active && s.itemStart > 0 ? Math.max(1.0, s.itemEnd - s.itemStart) : 0;
            miniBar.setProgress(len > 0 ? (int) Math.min(1000, 1000 * Math.max(0, System.currentTimeMillis() / 1000.0 - s.itemStart) / len) : 0);
            miniBar.setVisibility(active ? View.VISIBLE : View.INVISIBLE);
        }
        if (player != null && playerOpen) player.show(st, s);
        if (adapter != null) {
            adapter.setCurrent(cur, playing);
            if (cur >= 0 && cur != scrolledTo) {                       // keep the station on air in view
                if (cur < grid.getFirstVisiblePosition() || cur > grid.getLastVisiblePosition()) grid.smoothScrollToPosition(cur);
                scrolledTo = cur;
            }
        }
    }

    // ------------------------------------------------------------------------------------------ station tiles
    /** The logos are the stations; a frame in the station's colour and dancing bars mark the one on air. */
    private final class StationAdapter extends BaseAdapter {
        private int current = -1;
        private boolean playing;
        private Ui.EqView eq;                                          // the dancing bars of the station on air

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
            LinearLayout tile = new LinearLayout(MainActivity.this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(0, 0, 0, dp(2));
            tile.setContentDescription(st.name);
            tile.setBackground(Ui.ripple(null, Ui.rounded(MainActivity.this, Color.WHITE, 20)));
            tile.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout box = new FrameLayout(MainActivity.this);
            box.setPadding(dp(4), dp(4), dp(4), dp(4));
            if (on) {
                GradientDrawable ring = Ui.rounded(MainActivity.this, Color.TRANSPARENT, 20);
                ring.setStroke(dp(3), Logos.color(st).light);
                box.setBackground(ring);
            }
            box.addView(Ui.logo(MainActivity.this, Logos.get(st, 256), 16),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            if (on) {
                FrameLayout badge = new FrameLayout(MainActivity.this);
                badge.setBackground(Ui.rounded(MainActivity.this, 0xE615122A, 9));
                badge.setPadding(dp(7), dp(6), dp(7), dp(6));
                eq = new Ui.EqView(MainActivity.this, Logos.color(st).light);
                eq.setDancing(playing);
                badge.addView(eq, new FrameLayout.LayoutParams(dp(14), dp(12)));
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                lp.setMargins(0, 0, dp(6), dp(6));
                box.addView(badge, lp);
            }
            tile.addView(box);

            TextView name = Ui.strong(MainActivity.this, st.name, 12, on ? Logos.color(st).light : Ui.TEXT);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(4), dp(3), dp(4), 0);
            tile.addView(name);
            return tile;
        }
    }
}
