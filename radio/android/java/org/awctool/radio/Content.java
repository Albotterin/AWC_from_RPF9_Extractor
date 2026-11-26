package org.awctool.radio;

import android.app.Dialog;
import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The switches for what comes between the songs: adverts, news, the DJ's talk, other talk and jingles. One choice for all
 * stations; a station only offers the ones that change something on it.
 */
final class Content {
    static final int[] KINDS = {Schedule.ADS, Schedule.NEWS_ON, Schedule.DJ_TALK, Schedule.TALK_ON, Schedule.JINGLES};
    static final int[] NAMES = {R.string.c_ads, R.string.c_news, R.string.c_dj, R.string.c_talk, R.string.c_jingles};
    static final int[] WHAT = {R.string.c_ads_what, R.string.c_news_what, R.string.c_dj_what, R.string.c_talk_what,
            R.string.c_jingles_what};

    private Content() {
    }

    /** One line that says what is on air on this station besides its songs (or shows). */
    static String summary(Context c, Library.Station st) {
        int available = st == null ? 0 : st.available();
        if (available == 0) return c.getString(R.string.content_nothing);
        int on = RadioService.content(c) & available;
        String what;
        if (on == 0) {
            what = c.getString(st.talk ? R.string.content_shows_only : R.string.content_songs_only);
        } else if (on == available) {
            what = c.getString(R.string.content_all);
        } else {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < KINDS.length; i++) if ((on & KINDS[i]) != 0) names.add(c.getString(NAMES[i]));
            what = TextUtils.join(", ", names);
        }
        return c.getString(R.string.content_row, what);
    }

    /** The switches of this station in a sheet; it stays open while they are moved. */
    static Dialog sheet(Context c, Library.Station st) {
        int available = st == null ? 0 : st.available(), on = RadioService.content(c);
        List<Ui.Toggle> rows = new ArrayList<>();
        for (int i = 0; i < KINDS.length; i++) {
            int kind = KINDS[i];
            boolean has = (available & kind) != 0;
            rows.add(new Ui.Toggle(c.getString(NAMES[i]), c.getString(has ? WHAT[i] : R.string.content_not_here),
                    has && (on & kind) != 0, has, now -> RadioService.setContent(c, kind, now)));
        }
        return Ui.switchSheet(c, c.getString(R.string.content_title), rows);
    }
}
