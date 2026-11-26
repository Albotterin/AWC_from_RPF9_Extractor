package org.awctool.radio;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
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
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The look of the app in one place: the colours of an evening in Vice City, the art deco lettering of the icon for
 * headlines and clock digits, and the few building blocks every screen is made of.
 */
final class Ui {
    static final int NIGHT = 0xFF15122A;          // the app's background
    static final int SURFACE = 0xFF211D3C;        // bars, sheets
    static final int RAISED = 0xFF2E2951;         // things on a surface
    static final int TEXT = 0xFFF5F2FF;
    static final int MUTED = 0xFFABA4CC;
    static final int SUN = 0xFFFF8A6C;            // actions and everything that is switched on
    static final int WARN = 0xFFFFC46B;
    static final int RIPPLE = 0x33FFFFFF;
    /** The sky of the icon, from top to horizon: the ringing alarm is a sunrise. */
    static final int[] SKY = {0xFF2A2064, 0xFF6D4398, 0xFFD2599B, 0xFFFF8C60, 0xFFFFBC64};

    static Typeface displayFont;

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------------------------------ text
    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static TextView strong(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Headlines and clock digits in Poiret One (assets, SIL Open Font License); only for large sizes, it is thin. */
    static TextView display(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(displayFont(c));
        t.setIncludeFontPadding(false);
        return t;
    }

    static Typeface displayFont(Context c) {
        if (displayFont == null) {
            try {
                displayFont = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "PoiretOne-Regular.ttf");
            } catch (RuntimeException e) {
                AppLog.e("display font", e);
                displayFont = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            }
        }
        return displayFont;
    }

    /** One line of the display lettering that gets smaller until it fits: clock digits, long station names. */
    static final class FitText extends TextView {
        private final float largest, smallest;
        private final android.graphics.Paint probe = new android.graphics.Paint();

        FitText(Context c, float largestSp, float smallestSp, int color) {
            super(c);
            largest = largestSp;
            smallest = smallestSp;
            setTypeface(displayFont(c));
            setTextColor(color);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, largestSp);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setGravity(Gravity.CENTER);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int room = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
            if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && room > 0 && getText().length() > 0) {
                float px = getResources().getDisplayMetrics().scaledDensity;
                probe.set(getPaint());
                probe.setTextSize(largest * px);
                float need = probe.measureText(getText().toString());
                float size = need <= room ? largest * px : Math.max(smallest * px, largest * px * room / need * 0.97f);
                if (Math.abs(size - getTextSize()) > 0.5f) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------------------------------ shapes
    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** The touch feedback that every modern Android control has: a ripple that stays inside the shape. */
    static Drawable ripple(Drawable content, Drawable mask) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), content, mask);
    }

    static Drawable circle(int color) {
        ShapeDrawable d = new ShapeDrawable(new OvalShape());
        d.getPaint().setColor(color);
        return d;
    }

    static void clip(View v, final float radiusDp) {
        v.setClipToOutline(true);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(view.getContext(), radiusDp));
            }
        });
    }

    /** An image that is as high as it is wide, with rounded corners. */
    static final class SquareImage extends ImageView {
        SquareImage(Context c, float radiusDp) {
            super(c);
            setScaleType(ScaleType.CENTER_CROP);
            clip(this, radiusDp);
        }

        @Override
        protected void onMeasure(int w, int h) {
            int side = MeasureSpec.getSize(w);
            if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) side = Math.min(side, MeasureSpec.getSize(h));
            setMeasuredDimension(side, side);
        }
    }

    static ImageView logo(Context c, Bitmap b, float radiusDp) {
        ImageView v = new SquareImage(c, radiusDp);
        if (b != null) v.setImageBitmap(b);
        return v;
    }

    // ------------------------------------------------------------------------------------------ controls
    /** A pill button: filled for the one main action of a screen, quiet for the others. */
    static TextView button(Context c, CharSequence label, boolean main) {
        TextView b = strong(c, label, 15, main ? NIGHT : TEXT);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ripple(rounded(c, main ? SUN : RAISED, 28), rounded(c, Color.WHITE, 28)));
        b.setPadding(dp(c, 24), dp(c, 14), dp(c, 24), dp(c, 14));
        b.setMinHeight(dp(c, 52));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    static ImageButton icon(Context c, int res, CharSequence description, int sizeDp, int color) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(res);
        b.setColorFilter(color);
        b.setBackground(ripple(null, circle(Color.WHITE)));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, sizeDp / 4f);
        b.setPadding(pad, pad, pad, pad);
        b.setContentDescription(description);
        return b;
    }

    static Switch toggle(Context c, boolean on) {
        Switch s = new Switch(c);
        s.setChecked(on);
        int[][] states = {{android.R.attr.state_checked}, {}};
        s.setThumbTintList(new ColorStateList(states, new int[]{SUN, 0xFFCFCAE6}));
        s.setTrackTintList(new ColorStateList(states, new int[]{SUN, MUTED}));
        return s;
    }

    /** A rounded line that fills from the left: the progress of the song. */
    static Drawable progressLine(Context c, int track, int fill) {
        ClipDrawable done = new ClipDrawable(rounded(c, fill, 3), Gravity.START, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{rounded(c, track, 3), done});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        return ld;
    }

    /** Three bars that dance while a station plays (and rest while it is paused): the "this is on air" sign. */
    static final class EqView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean dancing = true;

        EqView(Context c, int color) {
            super(c);
            paint.setColor(color);
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
                double f = dancing ? 0.3 + 0.7 * Math.abs(Math.sin(t / 240.0 * (1 + 0.43 * i) + i * 1.7)) : 0.35;
                float bh = (float) (h * f);
                c.drawRoundRect(i * 2 * bw, h - bh, i * 2 * bw + bw, h, bw / 2, bw / 2, paint);
            }
            if (dancing) postInvalidateDelayed(33);
        }
    }

    // ------------------------------------------------------------------------------------------ bottom sheets
    /** One choice in a bottom sheet. */
    static final class Choice {
        final CharSequence label;
        final Bitmap image;
        final boolean chosen;
        final Runnable action;

        Choice(CharSequence label, Bitmap image, boolean chosen, Runnable action) {
            this.label = label;
            this.image = image;
            this.chosen = chosen;
            this.action = action;
        }
    }

    /** A list that slides up from the bottom; tapping a row does it and closes the sheet. */
    static Dialog sheet(Context c, CharSequence title, List<Choice> choices) {
        final Dialog d = new Dialog(c, R.style.AppSheet);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        float r = dp(c, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, dp(c, 20), 0, dp(c, 8));
        TextView head = display(c, title, 26, TEXT);
        head.setPadding(dp(c, 24), 0, dp(c, 24), dp(c, 12));
        box.addView(head);
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        View current = null;
        for (Choice ch : choices) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(c, 56));
            row.setPadding(dp(c, 24), dp(c, 6), dp(c, 24), dp(c, 6));
            row.setBackground(ripple(ch.chosen ? new ColorDrawable(RAISED) : null, new ColorDrawable(Color.WHITE)));
            row.setClickable(true);
            row.setFocusable(true);
            if (ch.image != null) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44));
                lp.rightMargin = dp(c, 16);
                row.addView(logo(c, ch.image, 10), lp);
            }
            row.addView(oneLine(strong(c, ch.label, 16, ch.chosen ? SUN : TEXT)),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (ch.chosen) {
                ImageView tick = new ImageView(c);
                tick.setImageResource(R.drawable.ic_check);
                tick.setColorFilter(SUN);
                row.addView(tick, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
                current = row;
            }
            row.setOnClickListener(v -> {
                d.dismiss();
                ch.action.run();
            });
            rows.addView(row);
        }
        final ScrollView scroll = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {          // never higher than most of the screen
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.68f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(rows);
        box.addView(scroll);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        if (current != null) {
            final View show = current;
            scroll.post(() -> scroll.scrollTo(0, Math.max(0, show.getTop() - dp(c, 120))));
        }
        d.show();
        return d;
    }

    /** One switch in a bottom sheet. */
    static final class Toggle {
        final CharSequence name, what;
        final boolean on, enabled;
        final java.util.function.Consumer<Boolean> changed;

        Toggle(CharSequence name, CharSequence what, boolean on, boolean enabled, java.util.function.Consumer<Boolean> changed) {
            this.name = name;
            this.what = what;
            this.on = on;
            this.enabled = enabled;
            this.changed = changed;
        }
    }

    /** Switches in a sheet that slides up from the bottom; it stays open until it is dismissed. */
    static Dialog switchSheet(Context c, CharSequence title, List<Toggle> toggles) {
        final Dialog d = new Dialog(c, R.style.AppSheet);
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SURFACE);
        float r = dp(c, 28);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);
        box.setPadding(0, dp(c, 20), 0, dp(c, 8));
        TextView head = display(c, title, 26, TEXT);
        head.setPadding(dp(c, 24), 0, dp(c, 24), dp(c, 8));
        box.addView(head);
        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (Toggle t : toggles) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(c, 60));
            row.setPadding(dp(c, 24), dp(c, 6), dp(c, 20), dp(c, 6));
            row.setBackground(ripple(null, new ColorDrawable(Color.WHITE)));
            LinearLayout words = new LinearLayout(c);
            words.setOrientation(LinearLayout.VERTICAL);
            words.setPadding(0, 0, dp(c, 12), 0);
            words.addView(strong(c, t.name, 16, TEXT));
            words.addView(text(c, t.what, 13, MUTED));
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Switch s = toggle(c, t.on);
            s.setContentDescription(t.name);
            s.setEnabled(t.enabled);
            s.setOnCheckedChangeListener((v, now) -> t.changed.accept(now));
            row.addView(s);
            if (t.enabled) {
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(v -> s.toggle());
            } else {
                row.setAlpha(0.45f);
            }
            rows.addView(row);
        }
        final ScrollView scroll = new ScrollView(c) {
            @Override
            protected void onMeasure(int w, int h) {          // never higher than most of the screen
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.78f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(rows);
        box.addView(scroll);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
        return d;
    }

    /** Pick one of the stations (with their logos). */
    static void stationSheet(Context c, CharSequence title, List<Library.Station> stations, String chosen, CharSequence first,
                             java.util.function.Consumer<String> picked) {
        List<Choice> out = new ArrayList<>();
        if (first != null) out.add(new Choice(first, null, chosen.isEmpty(), () -> picked.accept("")));
        for (Library.Station s : stations) {
            out.add(new Choice(s.name, Logos.get(s, 128), s.name.equals(chosen), () -> picked.accept(s.name)));
        }
        sheet(c, title, out);
    }

    // ------------------------------------------------------------------------------------------ words
    /** "Mon, Tue, Fri", "Every day", "Once" ... for the day bits of an alarm. */
    static String days(Context c, int days) {
        if (days == 0) return c.getString(R.string.days_once);
        if (days == Alarm.EVERY_DAY) return c.getString(R.string.days_every);
        if (days == Alarm.WORKDAYS) return c.getString(R.string.days_work);
        if (days == Alarm.WEEKEND) return c.getString(R.string.days_weekend);
        String[] names = c.getResources().getStringArray(R.array.days_short);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((days & (1 << i)) != 0) out.add(names[i]);
        return TextUtils.join(", ", out);
    }

    /** "in 1 day 3 h", "in 8 h 12 min", "in 5 min": how long until the alarm rings. */
    static String until(Context c, long at, long now) {
        long min = Math.max(1, (at - now + 59_999) / 60_000);
        if (min >= 24 * 60) return c.getString(R.string.until_days, (int) (min / (24 * 60)), (int) (min % (24 * 60) / 60));
        if (min >= 60) return c.getString(R.string.until_hours, (int) (min / 60), (int) (min % 60));
        return c.getString(R.string.until_minutes, (int) min);
    }

    static String clock(int hour, int minute) {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute);
    }

    /** The name of a station or podcast; a group of the score has a translated one. */
    /** The title of an episode; "Episode 3" for a pack that does not know it. */
    static String episodeName(android.content.Context c, Library.Station podcast, int i) {
        String title = podcast.songs.get(i).title;
        return title.isEmpty() ? c.getString(R.string.episode_n, i + 1) : title;
    }

    static String nameOf(Context c, Library.Station st) {
        if (st == null) return "";
        if (st.music) return queueName(c, Music.listOf(st.name));
        if (st.score == null) return st.name;
        return c.getString("openworld".equals(st.score) ? R.string.score_openworld : "mission".equals(st.score) ? R.string.score_mission
                : "compound".equals(st.score) ? R.string.score_compound : "places".equals(st.score) ? R.string.score_places
                : R.string.score_activity);
    }

    /** What a queue of songs is called: its playlist or artist, "All songs", or what was searched for. */
    static String queueName(Context c, String list) {
        if (list.startsWith(Music.LIST)) return list.substring(Music.LIST.length());
        if (list.startsWith(Music.ARTIST)) return list.substring(Music.ARTIST.length());
        if (list.startsWith(Music.SEARCH)) return c.getString(R.string.search_of, list.substring(Music.SEARCH.length()));
        return c.getString(R.string.all_songs);
    }

    static String fmt(double sec) {
        int s = (int) Math.max(0, sec);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
