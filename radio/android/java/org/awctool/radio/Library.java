package org.awctool.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The stations of a station pack (see Pack). */
final class Library {
    static final String SCORE_NAME = "score:";

    static final class Station {
        final String name;
        final String logo;                 // file name inside the pack, or ""
        final boolean talk;                // talk radio: its items are shows, not songs
        final boolean podcast;             // played on demand: songs are its episodes, there is no programme
        final String score;                // a group of the game's score ("mission", ...): on demand, songs are its pieces
        Pack pack;                         // the file the station comes from
        final List<Schedule.Track> songs = new ArrayList<>();
        final List<Schedule.Track> jingles = new ArrayList<>();
        final List<Schedule.Track> ads = new ArrayList<>();
        final List<Schedule.Track> toads = new ArrayList<>();      // the DJ's lines into an advert break
        final List<Schedule.Track> tonews = new ArrayList<>();     // ... and into the news
        final List<Schedule.Track> says = new ArrayList<>();       // what is said on it (longer spoken items)
        Schedule.Rules rules;                                      // the game's programme rules, or null
        boolean playsNews;
        private List<Schedule.Track> news = java.util.Collections.emptyList();   // the bulletins of the news pack
        private Pack newsPack;
        private final java.util.Map<String, Schedule> programmes = new java.util.HashMap<>();

        /** The news of the folder (empty: there is no news pack); stations that play them get a new programme. */
        synchronized void setNews(List<Schedule.Track> bulletins, Pack from) {
            if (!playsNews || (from == newsPack)) return;
            news = bulletins;
            newsPack = from;
            programmes.clear();
        }

        /** Whether the switch for adverts, news and talk changes anything on this station. */
        synchronized boolean hasMore() {
            return rules != null && (!ads.isEmpty() || !news.isEmpty() || (!rules.talkRadio && !says.isEmpty()));
        }

        /** The file a recording of this station lies in: its own pack, or for a bulletin the news pack. */
        synchronized Pack packOf(Schedule.Track t) {
            return newsPack != null && pack.entry("t/" + t.key) == null ? newsPack : pack;
        }

        /** The station's programme: with adverts, news and talk if wanted and the station has any, and with this crossfade. */
        synchronized Schedule programme(boolean extras, double fade) {
            boolean with = extras && hasMore();
            String key = with + " " + fade;
            Schedule s = programmes.get(key);
            if (s == null) {
                s = new Schedule(name, songs, jingles, fade, talk && rules == null);
                if (rules != null) {
                    java.util.Map<String, Schedule.Track> tracks = new java.util.HashMap<>();
                    for (List<Schedule.Track> list : java.util.Arrays.asList(songs, jingles, says, ads)) {
                        for (Schedule.Track t : list) tracks.put(t.key, t);
                    }
                    s.setRules(rules, tracks, news, with);
                }
                s.setLinesIntoAds(toads);
                s.setLinesIntoNews(tonews);
                programmes.put(key, s);
            }
            return s;
        }

        Station(String name, String logo, boolean talk) {
            this(name, logo, talk, false);
        }

        Station(String name, String logo, boolean talk, boolean podcast) {
            this(name, logo, talk, podcast, null);
        }

        private Station(String name, String logo, boolean talk, boolean podcast, String score) {
            this.name = name;
            this.logo = logo;
            this.talk = talk;
            this.podcast = podcast;
            this.score = score;
        }

        /** The name under which the app keeps a group of the score; the screens show a translated one. */
        static Station ofScore(String group) {
            return new Station(SCORE_NAME + group, "", false, true, group);
        }
    }

    /** The podcasts of a pack: [{"name", "logo", "episodes": [{"k", "s", "t"?}]}], in their order; "t": the title. */
    static void parsePodcasts(JSONArray arr, List<Station> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station p = new Station(o.getString("name"), o.optString("logo", ""), false, true);
            JSONArray episodes = o.getJSONArray("episodes");
            for (int j = 0; j < episodes.length(); j++) {
                JSONObject e = episodes.getJSONObject(j);
                p.songs.add(new Schedule.Track(e.getString("k"), e.optString("t", ""), p.name, e.getDouble("s")));
            }
            if (!p.songs.isEmpty()) out.add(p);
        }
    }

    /** The score of a pack: [{"name", "group", "pieces": [{"k", "g", "t", "s"}]}], the pieces in their order. */
    static void parseScores(JSONArray arr, List<Station> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station group = Station.ofScore(o.getString("group"));
            JSONArray pieces = o.getJSONArray("pieces");
            for (int j = 0; j < pieces.length(); j++) {
                JSONObject p = pieces.getJSONObject(j);                  // "artist": the sub group, e.g. a mission
                group.songs.add(new Schedule.Track(p.getString("k"), p.optString("t", ""), p.optString("g", ""), p.getDouble("s")));
            }
            if (!group.songs.isEmpty()) out.add(group);
        }
    }

    /** [start, end, [line keys]] of a song's pack entry; null if none of its lines is there. */
    private static Schedule.Window window(JSONArray w, java.util.Map<String, Schedule.Track> lines) throws Exception {
        if (w == null) return null;
        JSONArray keys = w.getJSONArray(2);
        List<Schedule.Track> found = new ArrayList<>();
        for (int k = 0; k < keys.length(); k++) {
            Schedule.Track line = lines.get(keys.getString(k));
            if (line != null) found.add(line);
        }
        return found.isEmpty() ? null : new Schedule.Window(w.getDouble(0), w.getDouble(1), found);
    }

    /** The news of a pack: [{"name", "items": [{"k", "s"}]}]. */
    static void parseNews(JSONArray arr, List<Schedule.Track> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            tracks(arr.getJSONObject(i).getJSONArray("items"), out);
        }
    }

    /** Recordings that are no songs: [{"k", "s", "t"?, "a"?}]. */
    private static void tracks(JSONArray arr, List<Schedule.Track> out) throws Exception {
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject s = arr.getJSONObject(i);
            out.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s")));
        }
    }

    private static List<String> texts(JSONArray arr) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; arr != null && i < arr.length(); i++) out.add(arr.getString(i));
        return out;
    }

    /** A factor of a rule: a number, or [what, category, curve] with a curve of [x, y] points or of values. */
    private static Schedule.Factor factor(Object o) throws Exception {
        if (o instanceof Number) return new Schedule.Factor(((Number) o).doubleValue());
        JSONArray f = (JSONArray) o;
        JSONArray curve = f.getJSONArray(2);
        boolean points = curve.get(0) instanceof JSONArray;
        double[] xs = points ? new double[curve.length()] : null, ys = new double[curve.length()];
        for (int i = 0; i < curve.length(); i++) {
            if (points) {
                xs[i] = curve.getJSONArray(i).getDouble(0);
                ys[i] = curve.getJSONArray(i).getDouble(1);
            } else {
                ys[i] = curve.getDouble(i);
            }
        }
        return new Schedule.Factor(f.getString(0).charAt(0), f.getString(1), xs, ys);
    }

    static Schedule.Rules rules(JSONObject o) throws Exception {
        Schedule.Rules r = new Schedule.Rules();
        JSONObject table = o.getJSONObject("table");
        for (java.util.Iterator<String> it = table.keys(); it.hasNext(); ) {
            String after = it.next();
            JSONArray rows = table.getJSONArray(after);
            List<Schedule.Row> out = new ArrayList<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONArray row = rows.getJSONArray(i), factors = row.getJSONArray(1);
                Schedule.Factor[] f = new Schedule.Factor[factors.length()];
                for (int k = 0; k < f.length; k++) f[k] = factor(factors.get(k));
                out.add(new Schedule.Row(row.getString(0), f));
            }
            r.table.put(after, out);
        }
        JSONObject pools = o.getJSONObject("pools");
        for (java.util.Iterator<String> it = pools.keys(); it.hasNext(); ) {
            String category = it.next();
            r.pools.put(category, texts(pools.getJSONArray(category)));
        }
        JSONObject history = o.optJSONObject("history");
        if (history != null) {
            for (java.util.Iterator<String> it = history.keys(); it.hasNext(); ) {
                String category = it.next();
                r.history.put(category, history.getInt(category));
            }
        }
        r.sequence.addAll(texts(o.optJSONArray("sequence")));
        r.ads.addAll(texts(o.optJSONArray("ads")));
        r.talkRadio = o.optBoolean("talkradio", false);
        return r;
    }

    /** Songs: [{"k", "t", "a", "s", "m"?, "dj"?}]. */
    private static void songs(JSONArray songs, java.util.Map<String, Schedule.Track> lines, List<Schedule.Track> out)
            throws Exception {
        for (int j = 0; songs != null && j < songs.length(); j++) {
            JSONObject s = songs.getJSONObject(j);
            JSONArray m = s.optJSONArray("m");               // parts of a mix: [[start, title, artist], ...]
            if (m != null && m.length() > 0) {
                double[] ps = new double[m.length()];
                String[] pt = new String[m.length()], pa = new String[m.length()];
                for (int k = 0; k < m.length(); k++) {
                    JSONArray e = m.getJSONArray(k);
                    ps[k] = e.getDouble(0);
                    pt[k] = e.optString(1, "");
                    pa[k] = e.optString(2, "");
                }
                out.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s"), ps, pt, pa));
            } else {
                out.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s")));
            }
            JSONObject dj = s.optJSONObject("dj");
            if (dj != null) {
                Schedule.Track song = out.get(out.size() - 1);
                song.intro = window(dj.optJSONArray("i"), lines);
                song.outro = window(dj.optJSONArray("o"), lines);
            }
        }
    }

    private static void lineKeys(JSONArray keys, java.util.Map<String, Schedule.Track> lines, List<Schedule.Track> out)
            throws Exception {
        for (int j = 0; keys != null && j < keys.length(); j++) {
            Schedule.Track line = lines.get(keys.getString(j));
            if (line != null) out.add(line);
        }
    }

    static void parse(JSONArray arr, List<Station> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station st = new Station(o.getString("name"), o.optString("logo", ""), "talk".equals(o.optString("kind", "")));
            java.util.Map<String, Schedule.Track> lines = new java.util.HashMap<>();      // what the DJ can say
            JSONArray voices = o.optJSONArray("voices");
            for (int j = 0; voices != null && j < voices.length(); j++) {
                JSONObject v = voices.getJSONObject(j);
                lines.put(v.getString("k"), new Schedule.Track(v.getString("k"), "", "", v.getDouble("s")));
            }
            lineKeys(o.optJSONArray("toads"), lines, st.toads);
            lineKeys(o.optJSONArray("tonews"), lines, st.tonews);
            songs(o.getJSONArray("songs"), lines, st.songs);
            tracks(o.optJSONArray("jingles"), st.jingles);
            tracks(o.optJSONArray("ads"), st.ads);
            tracks(o.optJSONArray("talk"), st.says);
            st.playsNews = o.optBoolean("news", false);
            JSONObject programme = o.optJSONObject("rules");
            if (programme != null) st.rules = rules(programme);
            out.add(st);
        }
    }
}
