package org.awctool.radio;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * "Now playing" over the whole screen: the station's logo large, the sky behind it in the station's colour. A swipe
 * over the logo turns the dial to the next or previous station.
 */
final class PlayerScreen extends LinearLayout {
    interface Actions {
        void close();

        void toggle();

        void skip(int delta);

        void sleep();

        void alarm();

        void content();
    }

    private static final int SOFT = 0xCCFFFFFF;         // second-rank text on the coloured background

    private final Activity activity;
    private final Ui.SquareImage logo;
    private final TextView station, title, artist, next, position, length, sleepLabel;
    private final ProgressBar bar;
    private final ImageButton toggle;
    private final Ui.EqView eq;
    private final TextView live;
    private final LinearLayout air;
    private final TextView contentLabel;
    private final LinearLayout contentRow;
    private final boolean compact;
    private final Actions actions;
    private Library.Station shown;
    private int colorShown;

    @SuppressLint("ClickableViewAccessibility")
    PlayerScreen(Activity a, boolean wide, Actions actions) {
        super(a);
        activity = a;
        this.actions = actions;
        setOrientation(VERTICAL);
        setClickable(true);                                             // touches do not fall through to the stations
        int side = dp(24);
        // a small phone: tighter controls and no "up next", so that the logo keeps its size
        boolean small = !wide && a.getResources().getConfiguration().screenHeightDp < 640;
        this.compact = small;

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(8), 0);
        ImageButton close = Ui.icon(a, R.drawable.ic_down, a.getString(R.string.act_close), 48, Color.WHITE);
        close.setOnClickListener(v -> actions.close());
        top.addView(close, new LayoutParams(dp(48), dp(48)));
        air = new LinearLayout(a);
        air.setGravity(Gravity.CENTER);
        eq = new Ui.EqView(a, Color.WHITE);
        air.addView(eq, new LayoutParams(dp(14), dp(12)));
        live = Ui.strong(a, a.getString(R.string.live), 13, Color.WHITE);
        live.setPadding(dp(8), 0, 0, 0);
        air.addView(live);
        top.addView(air, new LayoutParams(0, dp(48), 1f));
        top.addView(new ImageView(a), new LayoutParams(dp(48), dp(48)));       // keeps "Live" in the middle
        addView(top);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(wide ? HORIZONTAL : VERTICAL);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout art = new FrameLayout(a);
        art.setClipToPadding(false);                                    // the shadow of the logo is not cut off
        art.setPadding(wide ? dp(24) : dp(36), dp(12), wide ? 0 : dp(36), dp(12));
        logo = new Ui.SquareImage(a, 28);
        logo.setElevation(dp(16));
        art.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        final GestureDetector swipe = new GestureDetector(a, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onFling(MotionEvent from, MotionEvent to, float vx, float vy) {
                if (from == null || Math.abs(vx) < Math.abs(vy) * 1.3f || Math.abs(to.getX() - from.getX()) < dp(48)) {
                    if (vy > Math.abs(vx) * 1.3f && vy > dp(600)) {
                        actions.close();                                  // pulled down
                        return true;
                    }
                    return false;
                }
                actions.skip(vx < 0 ? 1 : -1);
                return true;
            }
        });
        art.setOnTouchListener((v, e) -> swipe.onTouchEvent(e));
        body.addView(art, wide ? new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f)
                : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(side, 0, side, dp(wide ? 4 : 16));
        body.addView(col, wide ? new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        station = new Ui.FitText(a, wide ? 26 : 32, 20, Color.WHITE);
        col.addView(station, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title = Ui.oneLine(Ui.strong(a, "", 19, Color.WHITE));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(wide ? 2 : 10), 0, 0);
        col.addView(title);
        artist = Ui.oneLine(Ui.text(a, "", 15, SOFT));
        artist.setGravity(Gravity.CENTER);
        col.addView(artist);

        bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressDrawable(Ui.progressLine(a, 0x38FFFFFF, Color.WHITE));
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        blp.topMargin = dp(wide ? 8 : 20);
        col.addView(bar, blp);
        LinearLayout times = new LinearLayout(a);
        times.setPadding(0, dp(6), 0, 0);
        position = Ui.text(a, "0:00", 12, SOFT);
        length = Ui.text(a, "0:00", 12, SOFT);
        length.setGravity(Gravity.END);
        times.addView(position, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(length, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(times);

        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER);
        ImageButton prev = Ui.icon(a, R.drawable.ic_prev, a.getString(R.string.act_prev), 64, Color.WHITE);
        ImageButton fwd = Ui.icon(a, R.drawable.ic_next, a.getString(R.string.act_next), 64, Color.WHITE);
        toggle = Ui.icon(a, R.drawable.ic_play, a.getString(R.string.act_play), 76, Ui.NIGHT);
        toggle.setBackground(Ui.ripple(Ui.circle(Color.WHITE), Ui.circle(Color.WHITE)));
        toggle.setElevation(dp(6));
        prev.setOnClickListener(v -> actions.skip(-1));
        fwd.setOnClickListener(v -> actions.skip(1));
        toggle.setOnClickListener(v -> actions.toggle());
        controls.addView(prev, new LayoutParams(dp(small ? 52 : 64), dp(small ? 52 : 64)));
        LayoutParams mid = new LayoutParams(dp(small ? 60 : 76), dp(small ? 60 : 76));
        mid.setMargins(dp(24), dp(small ? 0 : 4), dp(24), dp(small ? 0 : 4));
        controls.addView(toggle, mid);
        controls.addView(fwd, new LayoutParams(dp(small ? 52 : 64), dp(small ? 52 : 64)));
        col.addView(controls);

        LinearLayout extras = new LinearLayout(a);
        extras.setPadding(0, dp(wide ? 4 : small ? 6 : 12), 0, 0);
        sleepLabel = extra(R.drawable.ic_timer, a.getString(R.string.menu_sleep));
        sleepLabel.setOnClickListener(v -> actions.sleep());
        TextView alarm = extra(R.drawable.ic_alarm, a.getString(R.string.wake_with_station));
        alarm.setOnClickListener(v -> actions.alarm());
        LayoutParams left = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        left.rightMargin = dp(6);
        LayoutParams right = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        right.leftMargin = dp(6);
        extras.addView(sleepLabel, left);
        extras.addView(alarm, right);
        col.addView(extras);

        // what comes between the songs: the row says it and opens the switches (one choice for all stations)
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(10), 0);
        row.setBackground(Ui.ripple(Ui.rounded(a, 0x18FFFFFF, 18), Ui.rounded(a, Color.WHITE, 18)));
        contentLabel = Ui.oneLine(Ui.strong(a, "", 13, Color.WHITE));
        row.addView(contentLabel, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView more = new ImageView(a);
        more.setImageResource(R.drawable.ic_down);
        more.setColorFilter(Color.WHITE);
        more.setRotation(-90);
        row.addView(more, new LayoutParams(dp(24), dp(24)));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> actions.content());
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(wide || small ? 40 : 48));
        clp.topMargin = dp(wide || small ? 6 : 10);
        col.addView(row, clp);
        this.contentRow = row;

        next = Ui.oneLine(Ui.text(a, "", 13, SOFT));
        next.setGravity(Gravity.CENTER);
        next.setPadding(0, dp(wide ? 6 : 14), 0, 0);
        col.addView(next);
        if (small) next.setVisibility(GONE);
        paint(StationColor.plain().tint);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }

    private TextView extra(int icon, String label) {
        TextView t = Ui.strong(activity, label, activity.getResources().getConfiguration().screenWidthDp < 360 ? 12 : 13, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setMinHeight(dp(activity.getResources().getConfiguration().screenHeightDp < 640 ? 44 : 52));
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.graphics.drawable.Drawable d = activity.getDrawable(icon).mutate();
        d.setBounds(0, 0, dp(20), dp(20));
        d.setTint(Color.WHITE);
        // icon and words are centred together: the icon sits in the text, in front of the first word
        t.setTag(d);
        t.setText(withIcon(d, label));
        t.setBackground(Ui.ripple(Ui.rounded(activity, 0x24FFFFFF, 18), Ui.rounded(activity, Color.WHITE, 18)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    /** An icon inside a line of text, in the middle of the letters. */
    private static final class CenteredIcon extends android.text.style.ImageSpan {
        CenteredIcon(android.graphics.drawable.Drawable d) {
            super(d);
        }

        @Override
        public int getSize(android.graphics.Paint paint, CharSequence text, int start, int end, android.graphics.Paint.FontMetricsInt fm) {
            return getDrawable().getBounds().right;
        }

        @Override
        public void draw(android.graphics.Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         android.graphics.Paint paint) {
            android.graphics.drawable.Drawable d = getDrawable();
            android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
            c.save();
            c.translate(x, y + (fm.ascent + fm.descent) / 2 - d.getBounds().height() / 2f);
            d.draw(c);
            c.restore();
        }
    }

    /** The icon sticks to the first word (no-break spaces), also when the words need two lines. */
    private static CharSequence withIcon(android.graphics.drawable.Drawable icon, String label) {
        android.text.SpannableString s = new android.text.SpannableString("\u00a0\u00a0" + label);
        s.setSpan(new CenteredIcon(icon), 0, 1, 0);
        return s;
    }

    private void setExtra(TextView t, String label) {
        CharSequence s = withIcon((android.graphics.drawable.Drawable) t.getTag(), label);
        if (!s.toString().contentEquals(t.getText())) t.setText(s);
    }

    private void paint(int tint) {
        if (tint == colorShown) return;
        colorShown = tint;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{tint, tint, Ui.NIGHT}));
        activity.getWindow().setStatusBarColor(getVisibility() == VISIBLE ? tint : Ui.NIGHT);
    }

    int color() {
        return colorShown;
    }

    /** Shows what the radio is doing; `st` is the station on air, or the one that "play" would start. */
    void show(Library.Station st, RadioService radio) {
        boolean has = st != null && st.hasMore();
        set(contentLabel, Content.summary(getContext(), st));
        if (contentRow.isEnabled() != has) {
            contentRow.setEnabled(has);
            contentRow.setClickable(has);
        }
        int row = has || !compact ? VISIBLE : GONE;              // on a small phone the row only where it does something
        if (contentRow.getVisibility() != row) contentRow.setVisibility(row);
        contentRow.setAlpha(has ? 1f : 0.55f);
        boolean active = radio != null && radio.state != RadioService.STOPPED && st != null && st.name.equals(radio.stationName);
        boolean playing = active && radio.state == RadioService.PLAYING;
        if (st != shown) {
            shown = st;
            logo.setImageBitmap(st == null ? null : Logos.get(st, 256));
            logo.setContentDescription(st == null ? null : st.name);
            paint(st == null ? StationColor.plain().tint : Logos.color(st).tint);
            set(station, st == null ? "" : st.name);
        }
        eq.setDancing(playing);
        air.setAlpha(playing ? 1f : 0.6f);
        set(live, getContext().getString(active && !playing ? R.string.paused : R.string.live));
        air.setVisibility(active ? VISIBLE : INVISIBLE);
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(getContext().getString(playing ? R.string.act_pause : R.string.act_play));
        double len = active && radio.itemStart > 0 ? Math.max(1.0, radio.itemEnd - radio.itemStart) : 0;
        double pos = len > 0 ? Math.max(0, System.currentTimeMillis() / 1000.0 - radio.itemStart) : 0;
        bar.setProgress(len > 0 ? (int) Math.min(1000, 1000 * pos / len) : 0);
        set(position, Ui.fmt(pos));
        set(length, Ui.fmt(len));
        if (active) {
            set(title, radio.title.isEmpty() ? getContext().getString(R.string.loading) : radio.title);
            set(artist, radio.artist);
            set(next, radio.nextText.isEmpty() ? "" : getContext().getString(R.string.up_next, radio.nextText));
        } else {
            set(title, st == null ? "" : getContext().getString(R.string.tap_play));
            set(artist, st == null ? "" : getContext().getString(st.talk ? R.string.shows_count : R.string.songs_count, st.songs.size()));
            set(next, "");
        }
        long left = active ? radio.sleepAt - System.currentTimeMillis() : 0;
        setExtra(sleepLabel, left > 0 ? getContext().getString(R.string.sleep_active, (int) ((left + 59_999) / 60_000))
                : getContext().getString(R.string.menu_sleep));
    }

    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }
}
