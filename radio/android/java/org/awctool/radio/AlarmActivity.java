package org.awctool.radio;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;
import java.util.Locale;

/** The ringing alarm, over the lock screen: a sunrise, the time, what is playing, snooze and stop. */
public class AlarmActivity extends Activity {
    private static final long START_GRACE_MS = 3000;       // the service may still be starting

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView time, date, station, title, snooze, stop, keep, note;
    private ImageView logo;
    private Object shownLogo;             // null until the ringing alarm has been shown once
    private long openedAt;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (update()) handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.SKY[0]);
        getWindow().setNavigationBarColor(Ui.SKY[Ui.SKY.length - 1]);
        setVolumeControlStream(AudioManager.STREAM_ALARM);
        openedAt = SystemClock.elapsedRealtime();
        build();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void build() {
        boolean wide = getResources().getConfiguration().screenHeightDp < 480;
        boolean low = getResources().getConfiguration().screenHeightDp < 700;
        FrameLayout root = new FrameLayout(this);
        root.addView(new Sunrise(this));

        LinearLayout all = new LinearLayout(this);
        all.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        all.setGravity(Gravity.CENTER_HORIZONTAL);
        all.setPadding(dp(24), dp(wide ? 8 : low ? 20 : 36), dp(24), dp(wide ? 8 : low ? 14 : 28));
        root.addView(all);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        time = new Ui.FitText(this, wide ? 68 : low ? 76 : 108, 40, Color.WHITE);
        top.addView(time, match(0));
        date = Ui.text(this, "", 16, 0xE6FFFFFF);
        date.setGravity(Gravity.CENTER);
        date.setPadding(0, dp(low ? 4 : 10), 0, 0);
        top.addView(date);

        FrameLayout art = new FrameLayout(this);
        art.setClipToPadding(false);
        art.setPadding(dp(32), dp(wide ? 6 : low ? 12 : 24), dp(32), dp(wide ? 4 : 12));
        logo = new Ui.SquareImage(this, 28);
        logo.setElevation(dp(14));
        art.addView(logo, new FrameLayout.LayoutParams(dp(220), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        top.addView(art, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        station = new Ui.FitText(this, wide || low ? 24 : 30, 18, Color.WHITE);
        station.setShadowLayer(dp(8), 0, 0, 0xCC301050);                // the thin lettering on the bright sky
        top.addView(station, match(0));
        title = Ui.text(this, "", 15, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setShadowLayer(dp(6), 0, 0, 0x99301050);                 // readable on the bright part of the sky
        top.addView(title);
        all.addView(top, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        note = Ui.strong(this, "", 14, Color.WHITE);
        note.setGravity(Gravity.CENTER);
        note.setBackground(Ui.rounded(this, 0xB315122A, 16));
        note.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams np = match(0);
        np.topMargin = dp(8);
        bottom.addView(note, np);

        snooze = Ui.button(this, "", true);
        snooze.setTextSize(20);
        snooze.setMinHeight(dp(wide ? 52 : low ? 64 : 80));
        snooze.setBackground(Ui.ripple(Ui.rounded(this, Color.WHITE, 40), Ui.rounded(this, Color.WHITE, 40)));
        snooze.setElevation(dp(6));
        snooze.setOnClickListener(v -> send(AlarmService.ACTION_SNOOZE));
        bottom.addView(snooze, match(wide ? 6 : 14));
        stop = Ui.button(this, getString(R.string.alarm_stop), false);
        stop.setTextSize(18);
        stop.setMinHeight(dp(wide ? 48 : low ? 52 : 62));
        stop.setBackground(Ui.ripple(Ui.rounded(this, 0xE615122A, 34), Ui.rounded(this, Color.WHITE, 34)));
        stop.setOnClickListener(v -> send(AlarmService.ACTION_DISMISS));
        bottom.addView(stop, match(wide || low ? 8 : 12));
        keep = Ui.strong(this, getString(R.string.alarm_keep), 15, Ui.NIGHT);
        keep.setGravity(Gravity.CENTER);
        keep.setMinHeight(dp(48));
        keep.setBackground(Ui.ripple(null, Ui.rounded(this, Color.WHITE, 24)));
        keep.setClickable(true);
        keep.setFocusable(true);
        keep.setOnClickListener(v -> send(AlarmService.ACTION_KEEP));
        bottom.addView(keep, match(wide || low ? 2 : 6));
        all.addView(bottom, wide ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : match(0));
        setContentView(root);
    }

    private LinearLayout.LayoutParams match(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void send(String action) {
        AlarmService s = AlarmService.running;
        if (s != null) {
            s.answer(action);
            if (AlarmService.ACTION_KEEP.equals(action)) {
                // the phone may still be locked: Android asks to unlock before the app itself appears
                startActivity(new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        }
        finish();
    }

    /** False when the alarm is over and the screen closes. */
    private boolean update() {
        AlarmService s = AlarmService.running;
        long now = System.currentTimeMillis();
        time.setText(DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm", now));
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), new Date(now)));
        if (s == null || s.alarm == null) {
            if (SystemClock.elapsedRealtime() - openedAt > START_GRACE_MS || shownLogo != null) {
                finish();
                return false;
            }
            return true;
        }
        Library.Station st = s.station;
        Object key = st != null ? st : R.drawable.logo;
        if (key != shownLogo) {
            shownLogo = key;
            if (st != null) logo.setImageBitmap(Logos.get(st, 256));
            else logo.setImageResource(R.drawable.logo);
        }
        station.setText(st != null ? st.name : getString(s.fallback ? R.string.alarm_tone : R.string.loading));
        title.setText(s.artist.isEmpty() ? s.title : s.artist + " – " + s.title);
        String n = s.fallback ? getString(R.string.alarm_fallback) : s.test ? getString(R.string.alarm_testing) : "";
        note.setText(n);
        note.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        snooze.setText(getString(R.string.alarm_snooze, s.alarm.snoozeMin));
        snooze.setVisibility(s.test ? View.GONE : View.VISIBLE);
        stop.setText(s.test ? R.string.alarm_test_end : R.string.alarm_stop);
        keep.setVisibility(st != null && !s.fallback ? View.VISIBLE : View.INVISIBLE);
        return true;
    }

    @Override
    protected void onStart() {
        super.onStart();
        handler.post(refresh);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        if (AlarmService.running != null && AlarmService.running.test) send(AlarmService.ACTION_DISMISS);
        // a real alarm is only ended with one of its buttons
    }

    /** The sky of the app's icon with the sun coming up behind the buttons, striped like in the icon. */
    static final class Sunrise extends View {
        private final Paint sky = new Paint(), sun = new Paint(Paint.ANTI_ALIAS_FLAG);

        Sunrise(Context c) {
            super(c);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            sky.setShader(new LinearGradient(0, 0, 0, h, Ui.SKY, new float[]{0f, 0.34f, 0.6f, 0.8f, 1f}, Shader.TileMode.CLAMP));
            sun.setShader(new LinearGradient(0, h * 0.69f, 0, h * 0.95f, 0xFFFFF0B0, 0x00FF9A6E, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            c.drawRect(0, 0, w, h, sky);
            float r = Math.min(w, h) * 0.46f, cy = h * 0.69f + r;
            // the lower part of the sun is cut into stripes that get wider towards the horizon
            c.save();
            float y = cy - r * 0.62f, gap = r * 0.012f;
            for (int i = 0; i < 7 && y < h; i++) {
                if (Build.VERSION.SDK_INT >= 26) c.clipOutRect(0, y, w, y + gap);
                else c.clipRect(0, y, w, y + gap, android.graphics.Region.Op.DIFFERENCE);
                y += r * 0.11f;
                gap += r * 0.009f;
            }
            c.drawCircle(w / 2, cy, r, sun);
            c.restore();
        }
    }
}
