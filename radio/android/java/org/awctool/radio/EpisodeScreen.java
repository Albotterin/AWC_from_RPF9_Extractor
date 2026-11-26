package org.awctool.radio;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * The large player of what is played on demand. A podcast: the cover, the place in the episode (it can be dragged), back
 * and forward, the episode before and after, the sleep timer and the way to the list of episodes. A group of the score
 * is shown the same way, with the piece's name and without the jumps back and forward; so is a queue of songs, with the
 * picture of where the song comes from and the switches for shuffle and repeat.
 */
final class EpisodeScreen extends LinearLayout {
    interface Actions {
        void close();

        void toggle();

        void jump(int seconds);

        void seek(double seconds);

        void skip(int delta);

        void sleep();

        void episodes(String podcast);

        void shuffle();

        void repeat();
    }

    private static final int SOFT = 0xCCFFFFFF;

    private final Activity activity;
    private final Ui.SquareImage cover;
    private final TextView kind, name, title, position, left, sleep, next, list, shuffle, repeat;
    private final LinearLayout order;                      // shuffle and repeat: only for songs
    private Library.Station coverShown;
    private final SeekBar bar;
    private final ImageButton toggle, before, after;
    private final FrameLayout back, forward;
    private Library.Station shown;
    private int colorShown;
    private double length;
    private boolean dragging;

    EpisodeScreen(Activity a, boolean wide, final Actions actions) {
        super(a);
        activity = a;
        setOrientation(VERTICAL);
        setClickable(true);                                             // touches do not fall through to the list
        boolean small = !wide && a.getResources().getConfiguration().screenHeightDp < 640;

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(8), 0);
        ImageButton close = Ui.icon(a, R.drawable.ic_down, a.getString(R.string.act_close), 48, Color.WHITE);
        close.setOnClickListener(v -> actions.close());
        top.addView(close, new LayoutParams(dp(48), dp(48)));
        kind = Ui.strong(a, a.getString(R.string.podcast), 13, Color.WHITE);
        kind.setGravity(Gravity.CENTER);
        top.addView(kind, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(new TextView(a), new LayoutParams(dp(48), dp(48)));
        addView(top);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(wide ? HORIZONTAL : VERTICAL);
        addView(body, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        FrameLayout art = new FrameLayout(a);
        art.setClipToPadding(false);
        art.setPadding(wide ? dp(24) : dp(36), dp(12), wide ? 0 : dp(36), dp(12));
        cover = new Ui.SquareImage(a, 28);
        cover.setElevation(dp(16));
        art.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        body.addView(art, wide ? new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f)
                : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(dp(18), 0, dp(18), dp(wide ? 4 : 16));
        body.addView(col, wide ? new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                : new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        name = new Ui.FitText(a, wide ? 26 : 32, 20, Color.WHITE);
        col.addView(name, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        title = Ui.oneLine(Ui.strong(a, "", 18, Color.WHITE));
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(wide || small ? 2 : 10), 0, 0);
        col.addView(title);

        bar = new SeekBar(a);
        bar.setMax(1000);
        bar.setContentDescription(a.getString(R.string.act_seek));
        bar.setProgressTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0x66FFFFFF));
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (!fromUser) return;
                times(length * value / 1000.0);
                if (!dragging) actions.seek(length * value / 1000.0);        // set without dragging (accessibility)
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                actions.seek(length * s.getProgress() / 1000.0);
            }
        });
        LayoutParams blp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(wide || small ? 4 : 14);
        col.addView(bar, blp);
        LinearLayout times = new LinearLayout(a);
        times.setPadding(dp(8), 0, dp(8), 0);
        position = Ui.text(a, "0:00", 12, SOFT);
        left = Ui.text(a, "0:00", 12, SOFT);
        left.setGravity(Gravity.END);
        times.addView(position, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(left, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(times);

        LinearLayout controls = new LinearLayout(a);
        controls.setGravity(Gravity.CENTER);
        before = Ui.icon(a, R.drawable.ic_prev, a.getString(R.string.act_prev_episode), 48, Color.WHITE);
        after = Ui.icon(a, R.drawable.ic_next, a.getString(R.string.act_next_episode), 48, Color.WHITE);
        toggle = Ui.icon(a, R.drawable.ic_play, a.getString(R.string.act_resume), 72, Ui.NIGHT);
        toggle.setBackground(Ui.ripple(Ui.circle(Color.WHITE), Ui.circle(Color.WHITE)));
        toggle.setElevation(dp(6));
        before.setOnClickListener(v -> actions.skip(-1));
        after.setOnClickListener(v -> actions.skip(1));
        toggle.setOnClickListener(v -> actions.toggle());
        back = jumpButton(R.drawable.ic_rewind, R.string.act_rewind, RadioService.BACK_SECONDS);
        back.getChildAt(0).setOnClickListener(v -> actions.jump(-RadioService.BACK_SECONDS));
        forward = jumpButton(R.drawable.ic_forward, R.string.act_forward, RadioService.FORWARD_SECONDS);
        forward.getChildAt(0).setOnClickListener(v -> actions.jump(RadioService.FORWARD_SECONDS));
        int side = dp(48), jump = dp(small ? 52 : 58), big = dp(small ? 60 : 72);
        controls.addView(before, new LayoutParams(side, side));
        space(controls);
        controls.addView(back, new LayoutParams(jump, jump));
        space(controls);
        controls.addView(toggle, new LayoutParams(big, big));
        space(controls);
        controls.addView(forward, new LayoutParams(jump, jump));
        space(controls);
        controls.addView(after, new LayoutParams(side, side));
        LayoutParams clp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(wide || small ? 0 : 8);
        col.addView(controls, clp);

        LinearLayout extras = new LinearLayout(a);
        extras.setPadding(dp(6), dp(wide || small ? 6 : 16), dp(6), 0);
        sleep = extra(a.getString(R.string.menu_sleep), small);
        sleep.setOnClickListener(v -> actions.sleep());
        list = extra(a.getString(R.string.all_episodes), small);
        list.setOnClickListener(v -> {
            if (shown != null) actions.episodes(shown.name);
        });
        LayoutParams l = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        l.rightMargin = dp(6);
        LayoutParams r = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        r.leftMargin = dp(6);
        extras.addView(sleep, l);
        extras.addView(list, r);

        order = new LinearLayout(a);
        order.setPadding(dp(6), dp(wide || small ? 6 : 16), dp(6), 0);
        shuffle = extra("", small);
        shuffle.setOnClickListener(v -> actions.shuffle());
        repeat = extra("", small);
        repeat.setOnClickListener(v -> actions.repeat());
        LayoutParams ol = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        ol.rightMargin = dp(6);
        LayoutParams or = new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        or.leftMargin = dp(6);
        order.addView(shuffle, ol);
        order.addView(repeat, or);
        order.setVisibility(GONE);
        col.addView(order);
        col.addView(extras);

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

    private void space(LinearLayout row) {
        row.addView(new TextView(activity), new LayoutParams(0, 1, 1f));
    }

    /** A round arrow with the number of seconds in it. */
    private FrameLayout jumpButton(int icon, int label, int seconds) {
        FrameLayout box = new FrameLayout(activity);
        ImageButton b = Ui.icon(activity, icon, activity.getString(label), 56, Color.WHITE);
        b.setPadding(dp(4), dp(4), dp(4), dp(4));
        box.addView(b, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView n = Ui.strong(activity, String.valueOf(seconds), 11, Color.WHITE);
        n.setPadding(0, dp(4), 0, 0);
        n.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(n, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return box;
    }

    private TextView extra(String label, boolean small) {
        TextView t = Ui.strong(activity, label, 13, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        t.setMinHeight(dp(small ? 44 : 52));
        t.setPadding(dp(8), dp(8), dp(8), dp(8));
        t.setBackground(Ui.ripple(Ui.rounded(activity, 0x24FFFFFF, 18), Ui.rounded(activity, Color.WHITE, 18)));
        t.setClickable(true);
        t.setFocusable(true);
        return t;
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

    private void times(double at) {
        set(position, Ui.fmt(at));
        set(left, "-" + Ui.fmt(Math.max(0, length - at)));
    }

    /** The words and buttons of a podcast, of the score or of songs. */
    private void kindOf(boolean score, boolean music) {
        Context c = getContext();
        boolean plain = score || music;                    // music has no jumps
        set(kind, c.getString(music ? R.string.tab_music : score ? R.string.tab_score : R.string.podcast));
        back.setVisibility(plain ? GONE : VISIBLE);
        forward.setVisibility(plain ? GONE : VISIBLE);
        order.setVisibility(music ? VISIBLE : GONE);
        before.setContentDescription(c.getString(music ? R.string.act_prev_song : score ? R.string.act_prev_piece
                : R.string.act_prev_episode));
        after.setContentDescription(c.getString(music ? R.string.act_next_song : score ? R.string.act_next_piece
                : R.string.act_next_episode));
        bar.setContentDescription(c.getString(plain ? R.string.act_seek_piece : R.string.act_seek));
        set(list, c.getString(music ? R.string.show_list : score ? R.string.score_all_pieces : R.string.all_episodes));
    }

    /** Shows the podcast, score group or queue of songs that the radio service is playing. */
    void show(Library.Station podcast, RadioService radio) {
        boolean score = podcast.score != null, music = podcast.music;
        if (podcast != shown) {
            shown = podcast;
            coverShown = null;
            kindOf(score, music);
            if (!score && !music) set(name, podcast.name);
        }
        Library.Station art = podcast.coverOf(radio.episodeIndex());
        if (art != coverShown) {
            coverShown = art;
            cover.setImageBitmap(Logos.get(art, 256));
            cover.setContentDescription(Ui.nameOf(getContext(), art));
            paint(Logos.color(art).tint);
        }
        if (score || music) set(name, radio.title);
        if (music) {
            Context c = getContext();
            boolean mixed = Music.isShuffled(podcast.name);
            int how = radio.repeatMode();
            set(shuffle, c.getString(mixed ? R.string.shuffle_on : R.string.shuffle_off));
            shuffle.setSelected(mixed);
            shuffle.setTextColor(mixed ? Ui.SUN : Color.WHITE);
            set(repeat, c.getString(how == Episodes.REPEAT_ONE ? R.string.repeat_one : how == Episodes.REPEAT_ALL
                    ? R.string.repeat_all : R.string.repeat_off));
            repeat.setSelected(how != Episodes.REPEAT_OFF);
            repeat.setTextColor(how != Episodes.REPEAT_OFF ? Ui.SUN : Color.WHITE);
        }
        boolean playing = radio.state == RadioService.PLAYING;
        toggle.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(getContext().getString(playing ? R.string.act_pause : R.string.act_resume));
        int i = radio.episodeIndex();
        boolean failed = radio.title.equals(getContext().getString(score || music ? R.string.piece_error : R.string.episode_error));
        // an episode with a title: the title below the podcast's name, "Episode 2 of 3" at the top
        boolean named = !score && !music && i >= 0 && !podcast.songs.get(i).title.isEmpty();
        String place = score || music ? "" : getContext().getString(R.string.episode_of, i + 1, podcast.songs.size());
        if (!score && !music) set(kind, named ? place : getContext().getString(R.string.podcast));
        if (music) set(kind, Ui.nameOf(getContext(), podcast));
        set(title, music ? radio.artist : score ? radio.artist + " • " + Ui.nameOf(getContext(), podcast)
                : failed || named ? radio.title : place);
        length = radio.duration();
        if (!dragging) {
            double at = radio.position();
            bar.setProgress(length > 0 ? (int) Math.round(1000 * at / length) : 0);
            times(at);
        }
        set(next, radio.nextText.isEmpty() ? "" : getContext().getString(R.string.up_next, radio.nextText));
        long rest = radio.sleepAt - System.currentTimeMillis();
        set(sleep, rest > 0 ? getContext().getString(R.string.sleep_active, (int) ((rest + 59_999) / 60_000))
                : getContext().getString(R.string.menu_sleep));
    }

    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }
}
