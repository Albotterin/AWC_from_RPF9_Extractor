package org.awctool.radio;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The score tab: the groups of the score, then the sub groups of one (a mission, an area), then the pieces of one sub
 * group. A piece that is tapped plays, and the group goes on from there.
 */
final class ScoreList extends ScrollView {
    interface Actions {
        /** Show a group and one of its sub groups; null for the level above. */
        void show(String group, String sub);

        void play(String group, int piece);
    }

    /** The views of one line that change while the score is heard. */
    private static final class Row {
        int from, to;                       // the pieces of the group that the line stands for
        TextView title;
        ImageView mark;
        Ui.EqView eq;
    }

    private final Activity activity;
    private final LinearLayout box;
    private final List<Library.Station> scores;
    private final Library.Station open;                 // the group that is shown, or null
    private final String sub;                           // the sub group that is shown, or null
    private final List<Row> rows = new ArrayList<>();
    private final List<TextView> states = new ArrayList<>();

    ScoreList(Activity a, List<Library.Station> scores, Library.Station open, String sub, Actions actions) {
        super(a);
        activity = a;
        this.scores = scores;
        this.open = open;
        this.sub = open == null ? null : sub;
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(18));
        setFillViewport(true);
        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(2), dp(14), dp(20));
        addView(box);
        if (scores.isEmpty()) {
            empty();
        } else if (open == null) {
            for (Library.Station g : scores) box.addView(card(g, actions), row(8));
            TextView hint = Ui.text(a, a.getString(R.string.score_names), 13, Ui.MUTED);
            hint.setPadding(dp(6), dp(6), dp(6), 0);
            box.addView(hint, row(8));
        } else if (this.sub == null) {
            groups(open, actions);
        } else {
            pieces(open, this.sub, actions);
        }
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
        ImageView note = new ImageView(activity);
        note.setImageResource(R.drawable.ic_score);
        note.setColorFilter(Ui.SUN);
        box.addView(note, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView head = Ui.display(activity, activity.getString(R.string.score_empty_title), 28, Ui.TEXT);
        head.setIncludeFontPadding(true);
        head.setGravity(Gravity.CENTER);
        box.addView(head, row(20));
        TextView hint = Ui.text(activity, activity.getString(R.string.score_empty), 15, Ui.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(0, 1.15f);
        hint.setPadding(dp(16), 0, dp(16), 0);
        box.addView(hint, row(8));
    }

    private static double seconds(List<Schedule.Track> pieces, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) s += pieces.get(i).sec;
        return s;
    }

    private String summary(Library.Station g, int from, int to) {
        int minutes = (int) Math.max(1, Math.round(seconds(g.songs, from, to) / 60));
        return activity.getResources().getQuantityString(R.plurals.score_summary, to - from, to - from, minutes);
    }

    private LinearLayout line(int radiusDp) {
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.ripple(Ui.rounded(activity, Ui.SURFACE, radiusDp), Ui.rounded(activity, Color.WHITE, radiusDp)));
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    private View card(final Library.Station g, final Actions actions) {
        LinearLayout card = line(22);
        card.setPadding(dp(10), dp(10), dp(12), dp(10));
        String name = Ui.nameOf(activity, g);
        card.setContentDescription(name);
        card.addView(Ui.logo(activity, Logos.get(g, 256), 16), new LinearLayout.LayoutParams(dp(84), dp(84)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        TextView title = Ui.strong(activity, name, 17, Ui.TEXT);
        title.setMaxLines(2);
        col.addView(title);
        TextView summary = Ui.text(activity, summary(g, 0, g.songs.size()), 13, Ui.MUTED);
        summary.setPadding(0, dp(3), 0, 0);
        col.addView(summary);
        TextView state = Ui.oneLine(Ui.strong(activity, "", 13, Logos.color(g).light));
        state.setPadding(0, dp(3), 0, 0);
        col.addView(state);
        states.add(state);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.setOnClickListener(v -> actions.show(g.name, null));
        return card;
    }

    private void back(String label, View.OnClickListener to) {
        LinearLayout back = new LinearLayout(activity);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinimumHeight(dp(44));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_back);
        arrow.setColorFilter(Ui.SUN);
        back.addView(arrow, new LinearLayout.LayoutParams(dp(22), dp(22)));
        TextView t = Ui.strong(activity, label, 14, Ui.SUN);
        t.setPadding(dp(10), 0, 0, 0);
        back.addView(t);
        back.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(to);
        box.addView(back, row(0));
    }

    private void head(Library.Station g, String title, String below) {
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.logo(activity, Logos.get(g, 256), 18), new LinearLayout.LayoutParams(dp(88), dp(88)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), 0, 0, 0);
        TextView name = Ui.display(activity, title, 26, Ui.TEXT);
        name.setIncludeFontPadding(true);
        name.setMaxLines(3);
        col.addView(name);
        col.addView(Ui.text(activity, below, 13, Ui.MUTED));
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head, row(4));
    }

    /** The sub groups of a group, with a button that plays the whole group. */
    private void groups(final Library.Station g, final Actions actions) {
        back(activity.getString(R.string.tab_score), v -> actions.show(null, null));
        head(g, Ui.nameOf(activity, g), summary(g, 0, g.songs.size()));
        TextView all = Ui.button(activity, activity.getString(R.string.score_play_all), true);
        all.setOnClickListener(v -> actions.play(g.name, 0));
        box.addView(all, row(14));
        Map<String, int[]> subs = new LinkedHashMap<>();             // sub group -> its first and its end in the list
        for (int i = 0; i < g.songs.size(); i++) {
            String s = g.songs.get(i).artist;
            int[] span = subs.get(s);
            if (span == null) subs.put(s, new int[]{i, i + 1});
            else span[1] = i + 1;
        }
        boolean first = true;
        for (final Map.Entry<String, int[]> e : subs.entrySet()) {
            int from = e.getValue()[0], to = e.getValue()[1];
            View v = entry(g, e.getKey(), summary(g, from, to), from, to, R.drawable.ic_score);
            v.setOnClickListener(x -> actions.show(g.name, e.getKey()));
            box.addView(v, row(first ? 14 : 6));
            first = false;
        }
    }

    private void pieces(final Library.Station g, String name, final Actions actions) {
        back(Ui.nameOf(activity, g), v -> actions.show(g.name, null));
        int from = -1, to = 0;
        for (int i = 0; i < g.songs.size(); i++) {
            if (!g.songs.get(i).artist.equals(name)) continue;
            if (from < 0) from = i;
            to = i + 1;
        }
        if (from < 0) return;
        head(g, name, summary(g, from, to));
        for (int i = from; i < to; i++) {
            final int piece = i;
            View v = entry(g, g.songs.get(i).title, Ui.fmt(g.songs.get(i).sec), i, i + 1, R.drawable.ic_play);
            v.setOnClickListener(x -> actions.play(g.name, piece));
            box.addView(v, row(i == from ? 14 : 6));
        }
    }

    /** One line of a list: a round mark (dancing bars while it plays), a title and a line below it. */
    private View entry(Library.Station g, String title, String below, int from, int to, int icon) {
        Row r = new Row();
        r.from = from;
        r.to = to;
        LinearLayout card = line(18);
        card.setPadding(dp(12), dp(8), dp(14), dp(8));
        card.setMinimumHeight(dp(60));
        FrameLayout mark = new FrameLayout(activity);
        mark.setBackground(Ui.circle(Ui.RAISED));
        r.mark = new ImageView(activity);
        r.mark.setImageResource(icon);
        r.mark.setColorFilter(Ui.TEXT);
        r.eq = new Ui.EqView(activity, Logos.color(g).light);
        mark.addView(r.mark, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        mark.addView(r.eq, new FrameLayout.LayoutParams(dp(16), dp(14), Gravity.CENTER));
        card.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(14), 0, 0, 0);
        r.title = Ui.strong(activity, title, 15, Ui.TEXT);
        r.title.setMaxLines(2);
        col.addView(r.title);
        TextView info = Ui.oneLine(Ui.text(activity, below, 13, Ui.MUTED));
        info.setPadding(0, dp(1), 0, 0);
        col.addView(info);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        rows.add(r);
        return card;
    }

    /** Marks what is playing; `radio` may be null. */
    void update(RadioService radio) {
        boolean on = radio != null && radio.state != RadioService.STOPPED && radio.isScore();
        if (open == null) {
            for (int i = 0; i < states.size() && i < scores.size(); i++) {
                boolean this_ = on && radio.stationName.equals(scores.get(i).name);
                String text = this_ ? activity.getString(R.string.score_playing, radio.title) : "";
                if (!text.contentEquals(states.get(i).getText())) states.get(i).setText(text);
                states.get(i).setVisibility(this_ ? VISIBLE : GONE);
            }
            return;
        }
        for (Row r : rows) {
            int at = on && radio.stationName.equals(open.name) ? radio.episodeIndex() : -1;
            boolean current = at >= r.from && at < r.to;
            boolean playing = current && radio.state == RadioService.PLAYING;
            r.title.setTextColor(current ? Logos.color(open).light : Ui.TEXT);
            r.eq.setVisibility(playing ? VISIBLE : GONE);
            r.eq.setDancing(playing);
            r.mark.setVisibility(playing ? GONE : VISIBLE);
        }
    }
}
