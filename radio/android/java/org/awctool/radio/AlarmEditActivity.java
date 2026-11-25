package org.awctool.radio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/** Create or change one alarm: time, days, station, how loud, how gently, how long to snooze; try it out. */
public class AlarmEditActivity extends Activity {
    private static final String EXTRA_ID = "id", EXTRA_STATION = "station", STATE = "alarm";
    private static final int[] FADES = {0, 15, 30, 60, 180};
    private static final int[] SNOOZES = {5, 9, 10, 15, 20, 30};

    private Alarm alarm;
    private TextView time, countdown, daySummary, stationName, fadeValue, snoozeValue;
    private LinearLayout stationLogoBox;
    private final List<TextView> dayChips = new ArrayList<>();

    /** id 0 = a new alarm, optionally already set to a station. */
    static void open(Context from, int id, String station) {
        from.startActivity(new Intent(from, AlarmEditActivity.class).putExtra(EXTRA_ID, id).putExtra(EXTRA_STATION, station));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (state != null && state.getString(STATE) != null) alarm = Alarm.fromJson(new JSONObject(state.getString(STATE)));
        } catch (Exception e) {
            AppLog.e("restoring the editor", e);
        }
        if (alarm == null) {
            int id = getIntent().getIntExtra(EXTRA_ID, 0);
            alarm = id == 0 ? null : Alarms.get(this, id);
        }
        boolean fresh = alarm == null;
        if (fresh) {
            alarm = new Alarm();
            String s = getIntent().getStringExtra(EXTRA_STATION);
            if (s != null) alarm.station = s;
        }
        build();
        if (fresh && state == null) pickTime();                    // a new alarm starts with the one thing it needs
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        try {
            out.putString(STATE, alarm.toJson().toString());
        } catch (org.json.JSONException e) {
            AppLog.e("keeping the editor", e);
        }
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void build() {
        dayChips.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.NIGHT);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(8), dp(10), dp(16), dp(4));
        ImageButton back = Ui.icon(this, R.drawable.ic_back, getString(R.string.act_close), 48, Ui.TEXT);
        back.setOnClickListener(v -> finish());
        head.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = Ui.display(this, getString(alarm.id == 0 ? R.string.alarm_new : R.string.alarm_edit), 28, Ui.TEXT);
        title.setIncludeFontPadding(true);
        title.setPadding(dp(8), 0, 0, 0);
        head.addView(title);
        root.addView(head);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), 0, dp(16), dp(16));
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        boolean low = getResources().getConfiguration().screenHeightDp < 640;
        time = new Ui.FitText(this, low ? 72 : 104, 40, Ui.TEXT);
        time.setPadding(dp(12), dp(low ? 4 : 18), dp(12), dp(low ? 4 : 14));
        time.setBackground(Ui.ripple(null, Ui.rounded(this, Color.WHITE, 28)));
        time.setClickable(true);
        time.setFocusable(true);
        time.setOnClickListener(v -> pickTime());
        box.addView(time, row(0));
        countdown = Ui.text(this, "", 14, Ui.SUN);
        countdown.setGravity(Gravity.CENTER);
        box.addView(countdown, row(0));

        LinearLayout days = new LinearLayout(this);
        String[] letters = getResources().getStringArray(R.array.days_short);
        String[] full = getResources().getStringArray(R.array.days_long);
        int chipDp = Math.min(42, (getResources().getConfiguration().screenWidthDp - 32) / 7 - 4);
        for (int i = 0; i < 7; i++) {
            final int bit = 1 << i;
            TextView chip = Ui.strong(this, letters[i], 13, Ui.TEXT);
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription(full[i]);
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setOnClickListener(v -> {
                alarm.days ^= bit;
                update();
            });
            dayChips.add(chip);
            LinearLayout cell = new LinearLayout(this);
            cell.setGravity(Gravity.CENTER);
            cell.addView(chip, new LinearLayout.LayoutParams(dp(chipDp), dp(chipDp)));
            days.addView(cell, new LinearLayout.LayoutParams(0, dp(48), 1f));
        }
        box.addView(days, row(low ? 8 : 20));
        daySummary = Ui.text(this, "", 13, Ui.MUTED);
        daySummary.setGravity(Gravity.CENTER);
        box.addView(daySummary, row(4));

        LinearLayout group = group();
        LinearLayout st = line(group, getString(R.string.alarm_station), v -> pickStation());
        stationLogoBox = new LinearLayout(this);
        stationName = Ui.oneLine(Ui.strong(this, "", 15, Ui.SUN));
        stationName.setGravity(Gravity.END);
        st.addView(stationName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        st.addView(stationLogoBox);

        LinearLayout vol = new LinearLayout(this);
        vol.setOrientation(LinearLayout.VERTICAL);
        vol.setPadding(dp(18), dp(14), dp(18), dp(10));
        vol.addView(Ui.text(this, getString(R.string.alarm_volume), 15, Ui.TEXT));
        SeekBar seek = new SeekBar(this);
        seek.setMax(90);
        seek.setProgress(alarm.volume - 10);
        seek.setProgressTintList(ColorStateList.valueOf(Ui.SUN));
        seek.setThumbTintList(ColorStateList.valueOf(Ui.SUN));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.MUTED));
        seek.setContentDescription(getString(R.string.alarm_volume));
        seek.setPadding(dp(12), dp(10), dp(12), dp(10));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                alarm.volume = p + 10;
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        vol.addView(seek, row(4));
        group.addView(vol);

        LinearLayout fade = line(group, getString(R.string.alarm_fade), v -> pickFade());
        fadeValue = value(fade);
        LinearLayout snooze = line(group, getString(R.string.alarm_snooze_length), v -> pickSnooze());
        snoozeValue = value(snooze);
        Switch vibrate = Ui.toggle(this, alarm.vibrate);
        vibrate.setOnCheckedChangeListener((v, on) -> alarm.vibrate = on);
        LinearLayout vib = line(group, getString(R.string.alarm_vibrate), v -> vibrate.toggle());
        View space = new View(this);
        vib.addView(space, new LinearLayout.LayoutParams(0, 1, 1f));
        vibrate.setContentDescription(getString(R.string.alarm_vibrate));
        vib.addView(vibrate);
        box.addView(group, row(20));

        TextView net = Ui.text(this, getString(R.string.alarm_safety), 13, Ui.MUTED);
        net.setLineSpacing(0, 1.15f);
        net.setPadding(dp(8), 0, dp(8), 0);
        box.addView(net, row(14));
        if (alarm.id != 0) {
            TextView delete = Ui.strong(this, getString(R.string.alarm_delete), 15, 0xFFFF9C9C);
            delete.setGravity(Gravity.CENTER);
            delete.setMinHeight(dp(48));
            delete.setBackground(Ui.ripple(null, Ui.rounded(this, Color.WHITE, 24)));
            delete.setClickable(true);
            delete.setFocusable(true);
            delete.setOnClickListener(v -> confirmDelete());
            box.addView(delete, row(10));
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(dp(16), dp(10), dp(16), dp(14));
        TextView test = Ui.button(this, getString(R.string.alarm_test), false);
        test.setOnClickListener(v -> {
            AlarmReceiver.ring(this, alarm, true);             // rings now like the real one, nothing is saved
            startActivity(new Intent(this, AlarmActivity.class));
        });
        TextView save = Ui.button(this, getString(R.string.alarm_save), true);
        save.setOnClickListener(v -> save());
        for (TextView b : new TextView[]{test, save}) {
            b.setPadding(dp(8), dp(12), dp(8), dp(12));
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        l.rightMargin = dp(10);
        buttons.addView(test, l);
        buttons.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
        root.addView(buttons);
        setContentView(root);
        update();
    }

    private LinearLayout group() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(Ui.rounded(this, Ui.SURFACE, 24));
        g.setPadding(0, dp(6), 0, dp(6));
        Ui.clip(g, 24);
        return g;
    }

    /** A setting: its name on the left, its value to be added on the right. */
    private LinearLayout line(LinearLayout group, String name, View.OnClickListener tap) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(58));
        r.setPadding(dp(18), dp(6), dp(18), dp(6));
        r.setBackground(Ui.ripple(null, new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(tap);
        TextView n = Ui.text(this, name, 15, Ui.TEXT);
        n.setPadding(0, 0, dp(16), 0);
        r.addView(n);
        group.addView(r);
        return r;
    }

    private TextView value(LinearLayout line) {
        TextView v = Ui.strong(this, "", 15, Ui.SUN);
        v.setGravity(Gravity.END);
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return v;
    }

    private String fadeText(int sec) {
        if (sec == 0) return getString(R.string.fade_off);
        return sec < 60 ? getString(R.string.fade_seconds, sec) : getString(R.string.fade_minutes, sec / 60);
    }

    private void update() {
        time.setText(Ui.clock(alarm.hour, alarm.minute));
        time.setContentDescription(getString(R.string.alarm_time, Ui.clock(alarm.hour, alarm.minute)));
        long now = System.currentTimeMillis();
        countdown.setText(getString(R.string.alarm_next,
                Ui.until(this, Alarm.nextRing(alarm.hour, alarm.minute, alarm.days, now, TimeZone.getDefault()), now)));
        for (int i = 0; i < dayChips.size(); i++) {
            boolean on = (alarm.days & (1 << i)) != 0;
            TextView c = dayChips.get(i);
            c.setTextColor(on ? Ui.NIGHT : Ui.TEXT);
            c.setSelected(on);
            c.setBackground(Ui.ripple(Ui.circle(on ? Ui.SUN : Ui.SURFACE), Ui.circle(Color.WHITE)));
        }
        daySummary.setText(Ui.days(this, alarm.days));
        List<Library.Station> list = PackStore.get(this).stations;
        int i = Catalog.indexOf(list, alarm.station);
        stationName.setText(alarm.station.isEmpty() ? getString(R.string.station_last)
                : i < 0 ? getString(R.string.station_missing, alarm.station) : alarm.station);
        stationLogoBox.removeAllViews();
        if (i >= 0) {
            ImageView logo = Ui.logo(this, Logos.get(list.get(i), 128), 8);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
            lp.leftMargin = dp(12);
            stationLogoBox.addView(logo, lp);
        }
        fadeValue.setText(fadeText(alarm.fadeSec));
        snoozeValue.setText(getString(R.string.sleep_minutes, alarm.snoozeMin));
    }

    private void pickTime() {
        new TimePickerDialog(this, R.style.AppDialog, (v, h, m) -> {
            alarm.hour = h;
            alarm.minute = m;
            update();
        }, alarm.hour, alarm.minute, DateFormat.is24HourFormat(this)).show();
    }

    private void pickStation() {
        Ui.stationSheet(this, getString(R.string.alarm_station), PackStore.get(this).stations, alarm.station,
                getString(R.string.station_last), name -> {
                    alarm.station = name;
                    update();
                });
    }

    private void pickFade() {
        List<Ui.Choice> out = new ArrayList<>();
        for (int f : FADES) {
            out.add(new Ui.Choice(fadeText(f), null, f == alarm.fadeSec, () -> {
                alarm.fadeSec = f;
                update();
            }));
        }
        Ui.sheet(this, getString(R.string.alarm_fade), out);
    }

    private void pickSnooze() {
        List<Ui.Choice> out = new ArrayList<>();
        for (int m : SNOOZES) {
            out.add(new Ui.Choice(getString(R.string.sleep_minutes, m), null, m == alarm.snoozeMin, () -> {
                alarm.snoozeMin = m;
                update();
            }));
        }
        Ui.sheet(this, getString(R.string.alarm_snooze_length), out);
    }

    private void save() {
        alarm.enabled = true;
        Alarm saved = Alarms.save(this, alarm);
        Toast.makeText(this, getString(R.string.alarm_next, Ui.until(this, saved.nextAt, System.currentTimeMillis())), Toast.LENGTH_LONG).show();
        alarm = saved;
        // without notifications Android cannot show the ringing alarm: ask before leaving
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AlarmList.askForNotifications(this);
        } else {
            finish();
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == AlarmList.REQ_NOTIFICATIONS) finish();      // the list shows a warning if the answer was no
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this, R.style.AppDialog)
                .setMessage(getString(R.string.alarm_delete_ask, Ui.clock(alarm.hour, alarm.minute)))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.alarm_delete, (d, w) -> {
                    Alarms.delete(this, alarm.id);
                    finish();
                }).show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (time != null) update();
    }
}
