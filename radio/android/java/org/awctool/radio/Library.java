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
        private final java.util.Map<String, Schedule> programmes = new java.util.HashMap<>();

        /** The station's programme: with advert breaks if wanted and the station has adverts, and with this crossfade. */
        synchronized Schedule programme(boolean adverts, double fade) {
            boolean with = adverts && !ads.isEmpty();
            String key = with + " " + fade;
            Schedule s = programmes.get(key);
            if (s == null) {
                s = new Schedule(name, songs, jingles, with ? ads : java.util.Collections.<Schedule.Track>emptyList(), fade, talk);
                s.setLinesIntoAds(toads);
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

    /** The podcasts of a pack: [{"name", "logo", "episodes": [{"k", "s"}]}], the episodes in their order. */
    static void parsePodcasts(JSONArray arr, List<Station> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station p = new Station(o.getString("name"), o.optString("logo", ""), false, true);
            JSONArray episodes = o.getJSONArray("episodes");
            for (int j = 0; j < episodes.length(); j++) {
                JSONObject e = episodes.getJSONObject(j);
                p.songs.add(new Schedule.Track(e.getString("k"), "", p.name, e.getDouble("s")));
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
            JSONArray toads = o.optJSONArray("toads");
            for (int j = 0; toads != null && j < toads.length(); j++) {
                Schedule.Track line = lines.get(toads.getString(j));
                if (line != null) st.toads.add(line);
            }
            JSONArray songs = o.getJSONArray("songs");
            for (int j = 0; j < songs.length(); j++) {
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
                    st.songs.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s"), ps, pt, pa));
                } else {
                    st.songs.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s")));
                }
                JSONObject dj = s.optJSONObject("dj");
                if (dj != null) {
                    Schedule.Track song = st.songs.get(st.songs.size() - 1);
                    song.intro = window(dj.optJSONArray("i"), lines);
                    song.outro = window(dj.optJSONArray("o"), lines);
                }
            }
            JSONArray jingles = o.optJSONArray("jingles");
            if (jingles != null) {
                for (int j = 0; j < jingles.length(); j++) {
                    JSONObject s = jingles.getJSONObject(j);
                    st.jingles.add(new Schedule.Track(s.getString("k"), "", "", s.getDouble("s")));
                }
            }
            JSONArray ads = o.optJSONArray("ads");
            if (ads != null) {
                for (int j = 0; j < ads.length(); j++) {
                    JSONObject s = ads.getJSONObject(j);
                    st.ads.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), "", s.getDouble("s")));
                }
            }
            out.add(st);
        }
    }
}
