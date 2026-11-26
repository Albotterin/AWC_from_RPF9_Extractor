package org.awctool.radio;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The On-Demand tab: the songs of all packs like in a music app. A search field over what is shown: the playlists (one
 * for every station, and those of playlist packs) with all songs and the artists, the songs of a playlist or an artist,
 * or what a search finds. A song that is tapped plays, and its list goes on from there.
 */
final class MusicList extends LinearLayout {
    static final String ARTISTS = "artists", SONGS = "all";                  // views besides a queue's list name
    private static final int ARTIST_HITS = 5;

    interface Actions {
        /** Another view was chosen: null (the start), ARTISTS, or a list as Music names it. */
        void show(String view);

        void searched(String words);

        void play(String queue, int index);
    }

    private enum Kind { HEADING, TILES, LINK, SONG, ARTIST, TOP, HINT }

    private static final class Row {
        final Kind kind;
        String text, below, view, queue;
        int index, icon;
        Music.Song song;
        Library.Station cover;
        List<Music.Playlist> tiles;

        Row(Kind kind) {
            this.kind = kind;
        }
    }

    private final Activity activity;
    private final Music music;
    private final Actions actions;
    private final String view;                            // null: the start
    private final EditText field;
    private final ImageButton clear;
    private final ListView list;
    private final Rows adapter = new Rows();
    private final List<Row> rows = new ArrayList<>();
    private final int columns;
    private String words;
    private String onKey = "", onList = "";               // the song that is on and the list it is played from
    private boolean onPlaying;

    MusicList(Activity a, Music music, String view, String words, Actions actions) {
        super(a);
        activity = a;
        this.music = music;
        this.view = view;
        this.words = words == null ? "" : words;
        this.actions = actions;
        setOrientation(VERTICAL);
        columns = Math.max(3, Math.min(8, a.getResources().getConfiguration().screenWidthDp / 118));

        LinearLayout search = new LinearLayout(a);
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(Ui.rounded(a, Ui.SURFACE, 26));
        search.setPadding(dp(16), 0, dp(4), 0);
        ImageView glass = new ImageView(a);
        glass.setImageResource(R.drawable.ic_search);
        glass.setColorFilter(Ui.MUTED);
        search.addView(glass, new LayoutParams(dp(20), dp(20)));
        field = new EditText(a);
        field.setHint(R.string.search_hint);
        field.setHintTextColor(Ui.MUTED);
        field.setTextColor(Ui.TEXT);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackground(null);
        field.setPadding(dp(12), 0, dp(8), 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setText(this.words);
        field.setOnEditorActionListener((v, id, e) -> {
            keyboardAway();
            return true;
        });
        search.addView(field, new LayoutParams(0, dp(52), 1f));
        clear = Ui.icon(a, R.drawable.ic_close, a.getString(R.string.search_clear), 44, Ui.MUTED);
        clear.setOnClickListener(v -> field.setText(""));
        search.addView(clear, new LayoutParams(dp(44), dp(44)));
        LayoutParams slp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(14), 0, dp(14), dp(6));
        addView(search, slp);

        list = new ListView(a);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setVerticalFadingEdgeEnabled(true);
        list.setFadingEdgeLength(dp(18));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(16));
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) keyboardAway();
            }

            @Override
            public void onScroll(AbsListView v, int first, int visible, int total) {
            }
        });
        fill();
        list.setAdapter(adapter);
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                MusicList.this.words = s.toString();
                actions.searched(MusicList.this.words);
                fill();
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }
        });
    }

    private int dp(float v) {
        return Ui.dp(activity, v);
    }

    private void keyboardAway() {
        InputMethodManager keys = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keys != null) keys.hideSoftInputFromWindow(field.getWindowToken(), 0);
        field.clearFocus();
    }

    // ------------------------------------------------------------------------------------------ what is listed
    private Row add(Kind kind) {
        Row r = new Row(kind);
        rows.add(r);
        return r;
    }

    private void heading(String text) {
        add(Kind.HEADING).text = text;
    }

    private void hint(String text) {
        add(Kind.HINT).text = text;
    }

    private void songs(List<Music.Song> songs, String queue) {
        for (int i = 0; i < songs.size(); i++) {
            Row r = add(Kind.SONG);
            r.song = songs.get(i);
            r.queue = queue;
            r.index = i;
        }
    }

    private void artist(Music.Artist a) {
        Row r = add(Kind.ARTIST);
        r.text = a.name;
        r.below = count(a.songs.size());
        r.view = Music.ARTIST + a.name;
        r.cover = a.songs.get(0).from;
    }

    private String count(int n) {
        return activity.getResources().getQuantityString(R.plurals.songs_n, n, n);
    }

    private String summary(List<Music.Song> songs) {
        double sec = 0;
        for (Music.Song s : songs) sec += s.track.sec;
        return activity.getString(R.string.songs_summary, count(songs.size()), (int) Math.max(1, Math.round(sec / 60)));
    }

    private void fill() {
        rows.clear();
        clear.setVisibility(words.isEmpty() ? INVISIBLE : VISIBLE);
        if (music.songs.isEmpty()) {
            hint(activity.getString(R.string.music_empty));
        } else if (!words.trim().isEmpty()) {
            found();
        } else if (view == null) {
            start();
        } else if (view.equals(ARTISTS)) {
            Row top = add(Kind.TOP);
            top.text = activity.getString(R.string.artists);
            top.below = activity.getResources().getQuantityString(R.plurals.artists_n, music.artists.size(), music.artists.size());
            for (Music.Artist a : music.artists) artist(a);
        } else {
            String list = view.equals(SONGS) ? Music.ALL : view;
            List<Music.Song> songs = music.songsIn(list);
            if (songs == null || songs.isEmpty()) {
                start();
                return;
            }
            Row top = add(Kind.TOP);
            top.text = Ui.queueName(activity, list);
            top.below = summary(songs);
            top.queue = list;
            Music.Playlist p = list.startsWith(Music.LIST) ? music.playlist(list.substring(Music.LIST.length())) : null;
            top.cover = p != null ? p.cover : list.startsWith(Music.ARTIST) ? songs.get(0).from : null;
            songs(songs, list);
        }
    }

    private void start() {
        Row all = add(Kind.LINK);
        all.text = activity.getString(R.string.all_songs);
        all.below = count(music.songs.size());
        all.view = SONGS;
        all.icon = R.drawable.ic_music;
        Row artists = add(Kind.LINK);
        artists.text = activity.getString(R.string.artists);
        artists.below = activity.getResources().getQuantityString(R.plurals.artists_n, music.artists.size(), music.artists.size());
        artists.view = ARTISTS;
        artists.icon = R.drawable.ic_podcast;
        heading(activity.getString(R.string.playlists));
        for (int i = 0; i < music.playlists.size(); i += columns) {
            add(Kind.TILES).tiles = music.playlists.subList(i, Math.min(music.playlists.size(), i + columns));
        }
    }

    private void found() {
        List<Music.Artist> artists = music.searchArtists(words);
        List<Music.Song> songs = music.search(words);
        if (artists.isEmpty() && songs.isEmpty()) {
            hint(activity.getString(R.string.search_nothing, words.trim()));
            return;
        }
        if (!artists.isEmpty()) {
            heading(activity.getString(R.string.artists));
            for (Music.Artist a : artists.subList(0, Math.min(ARTIST_HITS, artists.size()))) artist(a);
        }
        if (!songs.isEmpty()) {
            heading(activity.getString(R.string.songs));
            songs(songs, Music.SEARCH + words);
        }
    }

    // ------------------------------------------------------------------------------------------ the rows
    private final class Rows extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int i) {
            return rows.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public int getViewTypeCount() {
            return Kind.values().length;
        }

        @Override
        public int getItemViewType(int i) {
            return rows.get(i).kind.ordinal();
        }

        @Override
        public boolean isEnabled(int i) {
            return false;                                 // the rows handle their taps themselves
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public View getView(int i, View old, ViewGroup parent) {
            Row r = rows.get(i);
            View v;
            switch (r.kind) {
                case HEADING:
                    v = headingView(r);
                    break;
                case TILES:
                    v = tilesView(r);
                    break;
                case TOP:
                    v = topView(r);
                    break;
                case HINT:
                    TextView t = Ui.text(activity, r.text, 15, Ui.MUTED);
                    t.setGravity(Gravity.CENTER);
                    t.setLineSpacing(0, 1.15f);
                    t.setPadding(dp(32), dp(40), dp(32), dp(16));
                    v = t;
                    break;
                case SONG:
                    v = songView(r);
                    break;
                default:
                    v = linkView(r);
            }
            v.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return v;
        }
    }

    private View headingView(Row r) {
        TextView t = Ui.strong(activity, r.text, 14, Ui.SUN);
        t.setPadding(dp(20), dp(16), dp(20), dp(6));
        return t;
    }

    /** A line that can be tapped, inside the margins of the list. */
    private LinearLayout line(FrameLayout frame) {
        LinearLayout card = new LinearLayout(activity);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.ripple(Ui.rounded(activity, Ui.SURFACE, 18), Ui.rounded(activity, Color.WHITE, 18)));
        card.setClickable(true);
        card.setFocusable(true);
        card.setPadding(dp(10), dp(8), dp(14), dp(8));
        card.setMinimumHeight(dp(62));
        frame.setPadding(dp(14), dp(3), dp(14), dp(3));
        frame.addView(card);
        return card;
    }

    private LinearLayout words(LinearLayout card, String title, String below, int titleColor) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(VERTICAL);
        col.setPadding(dp(12), 0, 0, 0);
        TextView t = Ui.oneLine(Ui.strong(activity, title, 15, titleColor));
        col.addView(t);
        TextView info = Ui.oneLine(Ui.text(activity, below, 13, Ui.MUTED));
        info.setPadding(0, dp(1), 0, 0);
        col.addView(info);
        card.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    private View songView(final Row r) {
        FrameLayout frame = new FrameLayout(activity);
        LinearLayout card = line(frame);
        final Music.Song s = r.song;
        boolean on = s.track.key.equals(onKey) && r.queue.equals(onList);
        FrameLayout art = new FrameLayout(activity);
        art.addView(Ui.logo(activity, Logos.get(s.from, 128), 10),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (on) {
            View shade = new View(activity);
            shade.setBackground(Ui.rounded(activity, 0xB015122A, 10));
            art.addView(shade);
            Ui.EqView eq = new Ui.EqView(activity, Logos.color(s.from).light);
            eq.setDancing(onPlaying);
            art.addView(eq, new FrameLayout.LayoutParams(dp(18), dp(16), Gravity.CENTER));
        }
        card.addView(art, new LayoutParams(dp(46), dp(46)));
        words(card, s.track.title, s.track.artist, on ? Logos.color(s.from).light : Ui.TEXT);
        TextView time = Ui.text(activity, Ui.fmt(s.track.sec), 12, Ui.MUTED);
        time.setPadding(dp(10), 0, 0, 0);
        card.addView(time);
        card.setContentDescription(s.track.title + ", " + s.track.artist);
        card.setOnClickListener(v -> {
            keyboardAway();
            actions.play(r.queue, r.index);
        });
        return frame;
    }

    private View linkView(final Row r) {
        FrameLayout frame = new FrameLayout(activity);
        LinearLayout card = line(frame);
        if (r.kind == Kind.ARTIST) {
            TextView initial = Ui.strong(activity, r.text.isEmpty() ? "" : r.text.substring(0, r.text.offsetByCodePoints(0, 1)), 18,
                    Ui.TEXT);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(Ui.circle(Ui.RAISED));
            card.addView(initial, new LayoutParams(dp(44), dp(44)));
        } else {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(r.icon);
            icon.setColorFilter(Ui.SUN);
            icon.setBackground(Ui.circle(Ui.RAISED));
            icon.setPadding(dp(11), dp(11), dp(11), dp(11));
            card.addView(icon, new LayoutParams(dp(44), dp(44)));
        }
        words(card, r.text, r.below, Ui.TEXT);
        card.setContentDescription(r.text);
        card.setOnClickListener(v -> {
            keyboardAway();
            if (!words.isEmpty()) actions.searched("");
            actions.show(r.view);
        });
        return frame;
    }

    private View tilesView(Row r) {
        LinearLayout line = new LinearLayout(activity);
        line.setPadding(dp(12), 0, dp(12), dp(6));
        for (int i = 0; i < columns; i++) {
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(VERTICAL);
            LayoutParams lp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = dp(i == 0 ? 0 : 8);
            line.addView(tile, lp);
            if (i >= r.tiles.size()) continue;
            final Music.Playlist p = r.tiles.get(i);
            tile.setPadding(0, 0, 0, dp(2));
            tile.setContentDescription(p.name);
            tile.setBackground(Ui.ripple(null, Ui.rounded(activity, Color.WHITE, 20)));
            tile.setClickable(true);
            tile.setFocusable(true);
            FrameLayout box = new FrameLayout(activity);
            box.setPadding(dp(4), dp(4), dp(4), dp(4));
            box.addView(Ui.logo(activity, Logos.get(p.cover, 256), 16),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tile.addView(box);
            TextView name = Ui.strong(activity, p.name, 12, Ui.TEXT);
            name.setGravity(Gravity.CENTER_HORIZONTAL);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(4), dp(3), dp(4), 0);
            tile.addView(name);
            TextView n = Ui.oneLine(Ui.text(activity, count(p.songs.size()), 11, Ui.MUTED));
            n.setGravity(Gravity.CENTER_HORIZONTAL);
            tile.addView(n);
            tile.setOnClickListener(v -> actions.show(Music.LIST + p.name));
        }
        return line;
    }

    /** The head of a list: the way back, its picture and name, and for songs the buttons to play them. */
    private View topView(final Row r) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(VERTICAL);
        box.setPadding(dp(14), 0, dp(14), dp(10));
        LinearLayout back = new LinearLayout(activity);
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinimumHeight(dp(44));
        ImageView arrow = new ImageView(activity);
        arrow.setImageResource(R.drawable.ic_back);
        arrow.setColorFilter(Ui.SUN);
        back.addView(arrow, new LayoutParams(dp(22), dp(22)));
        boolean artist = r.queue != null && r.queue.startsWith(Music.ARTIST);
        TextView to = Ui.strong(activity, activity.getString(artist ? R.string.artists : R.string.tab_music), 14, Ui.SUN);
        to.setPadding(dp(10), 0, 0, 0);
        back.addView(to);
        back.setBackground(Ui.ripple(null, new ColorDrawable(Color.WHITE)));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> actions.show(artist ? ARTISTS : null));
        box.addView(back);
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (r.cover != null) {
            head.addView(Ui.logo(activity, Logos.get(r.cover, 256), 18), new LayoutParams(dp(96), dp(96)));
        }
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(VERTICAL);
        col.setPadding(r.cover != null ? dp(16) : dp(4), 0, 0, 0);
        TextView name = Ui.display(activity, r.text, 26, Ui.TEXT);
        name.setIncludeFontPadding(true);
        name.setMaxLines(3);
        col.addView(name);
        col.addView(Ui.text(activity, r.below, 13, Ui.MUTED));
        head.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(head);
        if (r.queue != null) {
            LinearLayout buttons = new LinearLayout(activity);
            buttons.setPadding(0, dp(12), 0, 0);
            TextView play = Ui.button(activity, activity.getString(R.string.score_play_all), true);
            play.setOnClickListener(v -> actions.play(r.queue, 0));
            buttons.addView(play, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView mix = Ui.button(activity, activity.getString(R.string.shuffle), false);
            mix.setOnClickListener(v -> actions.play(Music.shuffled(r.queue, System.currentTimeMillis() % 1_000_000_000L, null), 0));
            LayoutParams ml = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            ml.leftMargin = dp(10);
            buttons.addView(mix, ml);
            box.addView(buttons);
        }
        return box;
    }

    /** Marks the song that is on; `radio` may be null. */
    void update(RadioService radio) {
        boolean on = radio != null && radio.state != RadioService.STOPPED && radio.isMusic();
        String key = on ? radio.currentKey() : "";
        String from = on ? Music.listOf(radio.stationName) : "";
        boolean playing = on && radio.state == RadioService.PLAYING;
        if (key.equals(onKey) && from.equals(onList) && playing == onPlaying) return;
        onKey = key;
        onList = from;
        onPlaying = playing;
        adapter.notifyDataSetChanged();
    }
}
