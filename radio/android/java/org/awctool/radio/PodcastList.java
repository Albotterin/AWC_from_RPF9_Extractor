package org.awctool.radio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The podcasts tab: all podcasts with their covers, or the episodes of the one that was tapped. */
final class PodcastList extends ScrollView {
    interface Actions {
        /** Show the episodes of a podcast, or (null) the list of podcasts. */
        void show(String podcast);

        void play(String podcast, int episode);
    }

    /** The views of one episode that change while it is listened to. */
    private static final class Row {
        TextView title, info;
        ProgressBar bar;
        ImageView mark;
        Ui.EqView eq;
    }

    private final Activity activity;
    private final LinearLayout box;
    private final Heard heard;
    private final Library.Station open;                  // the podcast whose episodes are shown, or null
    private final List<Library.Station> podcasts;
    private final List<Row> rows = new ArrayList<>();
    private final List<TextView> states = new ArrayList<>();

    PodcastList(Activity a, List<Library.Station> podcasts, Library.Station open, Actions actions) {
        super(a);
        activity = a;
        this.podcasts = podcasts;
        this.open = open;
        heard = new Heard(a);
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(18));
        setFillViewport(true);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(2), dp(14), dp(20));
        addView(box);
        if (podcasts.isEmpty()) empty();
        else if (open == null) for (Library.Station p : podcasts) box.addView(card(p, actions), row(8));
        else episodes(open, actions);
        update(null);
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private void empty() {
        box.setGravity(Gravity.CENTER);
        ImageView mic = new ImageView(activity);
        mic.setImageResource(R.drawable.ic_podcast);
        mic.setColorFilter(Ui.SUN);
        box.addView(mic, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView head = Ui.display(activity, activity.getString(R.string.podcasts_empty_title), 28, Ui.TEXT);
        head.setIncludeFontPadding(true);
        head.setGravity(Gravity.CENTER);
        box.addView(head, row(20));
        TextView hint = Ui.text(activity, activity.getString(R.string.podcasts_empty), 15, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.15f);
        hint.setPadding(dp(16), 0, dp(16), 0);
        box.addView(hint, row(8));
    }

    private static int minutes(Library.Station p) {
        double s = 0;
        for (Schedule.Track t : p.songs) s += t.sec;
        return (int) Math.round(s / 60);
    }

    private View card(final Library.Station p, final Actions actions) {
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(12), dp(10));
        card.setBackground(Ui.ripple(Ui.rounded(activity, Ui.SURFACE, 22), Ui.rounded(activity, Color.WHITE, 22)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setContentDescription(p.name);
        card.addView(Ui.logo(activity, Logos.get(p, 256), 16), new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView name = Ui.strong(activity, p.name, 17, Ui.TEXT);
        name.setMaxLines(2);
        col.addView(name);
        TextView summary = Ui.text(activity, activity.getString(R.string.podcast_summary, p.songs.size(), minutes(p)), 13, Ui.MUTED);
        summary.setPadding(0, dp(3), 0, 0);
        col.addView(summary);
        TextView state = Ui.oneLine(Ui.strong(activity, "", 13, Logos.color(p).light));
        state.setPadding(0, dp(3), 0, 0);
        col.addView(state);
        states.add(state);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(v -> actions.show(p.name));
        return card;
    }

    private void episodes(final Library.Station p, final Actions actions) {
        LinearLayout back = new LinearLayout(activity);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinimumHeight(dp(44));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_back);
        arrow.setColorFilter(Ui.SUN);
        back.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView all = Ui.strong(activity, activity.getString(R.string.all_podcasts), 14, Ui.SUN);
        all.setPadding(dp(10), 0, 0, 0);
        back.addView(all);
        back.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> actions.show(null));
        box.addView(back, row(0));

        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.logo(activity, Logos.get(p, 256), 18), new LinearLayout.LayoutParams(dp(104), dp(104)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, 0, 0);
        TextView name = Ui.display(activity, p.name, 26, Ui.TEXT);
        name.setIncludeFontPadding(true);
        name.setMaxLines(3);
        col.addView(name);
        col.addView(Ui.text(activity, activity.getString(R.string.podcast_summary, p.songs.size(), minutes(p)), 13, Ui.MUTED));
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head, row(4));

        for (int i = 0; i < p.songs.size(); i++) box.addView(episode(p, i, actions), row(i == 0 ? 16 : 8));
    }

    private View episode(final Library.Station p, final int i, final Actions actions) {
        Row r = new Row();
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(14), dp(10));
        card.setMinimumHeight(dp(64));
        card.setBackground(Ui.ripple(Ui.rounded(activity, Ui.SURFACE, 18), Ui.rounded(activity, Color.WHITE, 18)));
        card.setClickable(true);
        card.setFocusable(true);

        FrameLayout button = new FrameLayout(activity);
        button.setBackground(Ui.circle(Ui.RAISED));
        r.mark = new ImageView(activity);
        r.eq = new Ui.EqView(activity, Logos.color(p).light);
        button.addView(r.mark, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        button.addView(r.eq, new FrameLayout.LayoutParams(dp(16), dp(14), Gravity.CENTER));
        card.addView(button, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        r.title = Ui.oneLine(Ui.strong(activity, activity.getString(R.string.episode_n, i + 1), 16, Ui.TEXT));
        col.addView(r.title);
        r.info = Ui.oneLine(Ui.text(activity, "", 13, Ui.MUTED));
        r.info.setPadding(0, dp(2), 0, 0);
        col.addView(r.info);
        r.bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        r.bar.setMax(1000);
        r.bar.setProgressDrawable(Ui.progressLine(activity, 0x33FFFFFF, Logos.color(p).light));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4));
        bp.topMargin = dp(7);
        col.addView(r.bar, bp);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(v -> actions.play(p.name, i));
        rows.add(r);
        return card;
    }

    private static void set(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    /** Keeps the places in the episodes up to date; `radio` may be null. */
    void update(RadioService radio) {
        boolean on = radio != null && radio.state != RadioService.STOPPED && radio.isPodcast();
        if (open == null) {
            for (int i = 0; i < states.size() && i < podcasts.size(); i++) {
                Library.Station p = podcasts.get(i);
                String text = "";
                if (on && radio.stationName.equals(p.name)) {
                    text = activity.getString(R.string.podcast_playing, radio.episodeIndex() + 1);
                } else {
                    int done = 0, go = heard.resumeAt(p);
                    for (Schedule.Track t : p.songs) if (heard.done(t.key)) done++;
                    if (done == p.songs.size()) text = activity.getString(R.string.podcast_all_heard);
                    else if (go >= 0) text = activity.getString(R.string.podcast_continue, go + 1);
                }
                set(states.get(i), text);
                states.get(i).setVisibility(text.isEmpty() ? GONE : VISIBLE);
            }
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Schedule.Track t = open.songs.get(i);
            boolean current = on && radio.stationName.equals(open.name) && radio.episodeIndex() == i;
            boolean playing = current && radio.state == RadioService.PLAYING;
            double at = current ? radio.position() : heard.position(t.key);
            boolean done = heard.done(t.key) && at <= 0;
            String length = Ui.fmt(t.sec);
            set(r.info, at >= 1 ? activity.getString(R.string.episode_left, Ui.fmt(t.sec - at), length)
                    : done ? activity.getString(R.string.episode_heard, length) : length);
            r.bar.setVisibility(at >= 1 ? VISIBLE : GONE);
            r.bar.setProgress((int) Math.min(1000, 1000 * at / Math.max(1, t.sec)));
            r.title.setTextColor(current ? Logos.color(open).light : done ? Ui.MUTED : Ui.TEXT);
            r.eq.setVisibility(playing ? VISIBLE : GONE);
            r.eq.setDancing(playing);
            r.mark.setVisibility(playing ? GONE : VISIBLE);
            int icon = done && !current ? R.drawable.ic_check : R.drawable.ic_play;
            if (r.mark.getTag() == null || (Integer) r.mark.getTag() != icon) {
                r.mark.setTag(icon);
                r.mark.setImageResource(icon);
                r.mark.setColorFilter(icon == R.drawable.ic_check ? Ui.SUN : Ui.TEXT);
            }
        }
    }
}
