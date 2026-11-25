package org.awctool.radio;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** The "Alarms" tab: what rings next, one card per alarm with its switch, and what Android still has to allow. */
final class AlarmList extends ScrollView {
    static final int REQ_NOTIFICATIONS = 7;

    private final Activity activity;
    private final LinearLayout box;
    private TextView countdown;

    AlarmList(Activity a) {
        super(a);
        activity = a;
        setVerticalScrollBarEnabled(false);
        setFillViewport(true);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(4), dp(16), dp(24));
        addView(box);
        refresh();
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    void refresh() {
        box.removeAllViews();
        countdown = null;
        List<Alarm> alarms = Alarms.list(activity);
        if (!alarms.isEmpty()) warnings(box);
        if (alarms.isEmpty()) {
            box.setGravity(Gravity.CENTER);
            ImageView bell = new ImageView(activity);
            bell.setImageResource(R.drawable.ic_alarm);
            bell.setColorFilter(Ui.SUN);
            box.addView(bell, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView head = Ui.display(activity, activity.getString(R.string.alarm_empty_title), 28, Ui.TEXT);
            head.setIncludeFontPadding(true);
            head.setGravity(Gravity.CENTER);
            box.addView(head, row(20));
            TextView hint = Ui.text(activity, activity.getString(R.string.alarm_empty_hint), 15, Ui.MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setLineSpacing(0, 1.15f);
            hint.setPadding(dp(16), 0, dp(16), 0);
            box.addView(hint, row(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(28);
            box.addView(add(), lp);
            return;
        }
        box.setGravity(Gravity.TOP);
        countdown = Ui.text(activity, "", 15, Ui.MUTED);
        countdown.setPadding(dp(8), 0, 0, 0);
        box.addView(countdown, row(4));
        for (Alarm a : alarms) box.addView(card(a), row(12));
        box.addView(add(), row(20));
        tick();
    }

    /** Keeps "rings in 7 h 12 min" up to date. */
    void tick() {
        if (countdown == null) return;
        long at = Alarms.nextAt(activity);
        String s = at == 0 ? activity.getString(R.string.alarm_all_off)
                : activity.getString(R.string.alarm_next, Ui.until(activity, at, System.currentTimeMillis()));
        if (!s.contentEquals(countdown.getText())) countdown.setText(s);
    }

    private View add() {
        TextView b = Ui.button(activity, activity.getString(R.string.alarm_new), true);
        b.setOnClickListener(v -> AlarmEditActivity.open(activity, 0, null));
        return b;
    }

    private View card(final Alarm a) {
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(20), dp(16), dp(14), dp(16));
        card.setBackground(Ui.ripple(Ui.rounded(activity, Ui.SURFACE, 24), Ui.rounded(activity, Color.WHITE, 24)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> AlarmEditActivity.open(activity, a.id, null));

        final LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView time = Ui.display(activity, Ui.clock(a.hour, a.minute), 54, Ui.TEXT);
        col.addView(time);
        String days = Ui.days(activity, a.days);
        if (a.snoozeAt != 0) days = activity.getString(R.string.alarm_snoozing, Alarms.time(activity, a.snoozeAt));
        col.addView(Ui.text(activity, days, 14, a.snoozeAt != 0 ? Ui.SUN : Ui.MUTED), row(6));

        LinearLayout st = new LinearLayout(activity);
        st.setGravity(Gravity.CENTER_VERTICAL);
        Library.Station s = station(a.station);
        if (s != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
            lp.rightMargin = dp(10);
            st.addView(Ui.logo(activity, Logos.get(s, 128), 7), lp);
        }
        String name = a.station.isEmpty() ? activity.getString(R.string.station_last) : a.station;
        boolean gone = !a.station.isEmpty() && s == null;
        st.addView(Ui.oneLine(Ui.strong(activity, gone ? activity.getString(R.string.station_missing, name) : name, 14,
                gone ? Ui.WARN : Ui.TEXT)));
        col.addView(st, row(10));
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch on = Ui.toggle(activity, a.enabled || a.snoozeAt != 0);
        on.setContentDescription(activity.getString(R.string.alarm_switch, Ui.clock(a.hour, a.minute)));
        on.setMinimumHeight(dp(48));
        on.setOnCheckedChangeListener((v, checked) -> {
            Alarm now = Alarms.get(activity, a.id);
            if (now == null) return;
            now.enabled = checked;
            Alarms.save(activity, now);
            if (checked) {
                Toast.makeText(activity, activity.getString(R.string.alarm_next, Ui.until(activity, now.nextAt, System.currentTimeMillis())),
                        Toast.LENGTH_SHORT).show();
                askForNotifications(activity);
            }
            post(this::refresh);
        });
        card.addView(on);
        col.setAlpha(a.enabled || a.snoozeAt != 0 ? 1f : 0.45f);
        return card;
    }

    private Library.Station station(String name) {
        List<Library.Station> list = PackStore.get(activity).stations;
        int i = Catalog.indexOf(list, name);
        return i < 0 ? null : list.get(i);
    }

    // ------------------------------------------------------------------------------------------ permissions
    static boolean notificationsAllowed(Context c) {
        return ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).areNotificationsEnabled();
    }

    /** Android 13 asks once with a dialog. */
    static void askForNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33 && a.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            a.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
    }

    private void warnings(LinearLayout into) {
        if (!notificationsAllowed(activity)) {
            into.addView(warning(R.string.warn_notifications, () -> {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
                if (Build.VERSION.SDK_INT >= 26) open(i);
                else open(appSettings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS));
            }), row(4));
        }
        if (!Alarms.exactAllowed(activity) && Build.VERSION.SDK_INT >= 31) {
            into.addView(warning(R.string.warn_exact, () -> open(appSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))), row(8));
        }
        if (Build.VERSION.SDK_INT >= 34
                && !((NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE)).canUseFullScreenIntent()) {
            into.addView(warning(R.string.warn_fullscreen, () -> open(appSettings(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT))), row(8));
        }
    }

    private Intent appSettings(String action) {
        return new Intent(action, Uri.parse("package:" + activity.getPackageName()));
    }

    private void open(Intent i) {
        try {
            activity.startActivity(i);
        } catch (RuntimeException e) {
            AppLog.e("settings", e);
            Toast.makeText(activity, R.string.battery_failed, Toast.LENGTH_LONG).show();
        }
    }

    private View warning(int text, Runnable allow) {
        LinearLayout w = new LinearLayout(activity);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(dp(18), dp(14), dp(18), dp(12));
        android.graphics.drawable.GradientDrawable bg = Ui.rounded(activity, 0x1FFFC46B, 18);
        bg.setStroke(dp(1), Ui.WARN);
        w.setBackground(bg);
        w.addView(Ui.text(activity, activity.getString(text), 14, Ui.TEXT));
        TextView b = Ui.strong(activity, activity.getString(R.string.warn_allow), 14, Ui.WARN);
        b.setPadding(0, dp(10), dp(16), dp(4));
        b.setClickable(true);
        b.setFocusable(true);
        b.setOnClickListener(v -> allow.run());
        w.addView(b);
        return w;
    }
}
