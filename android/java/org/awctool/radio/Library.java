package org.awctool.radio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The stations of the loaded station pack (see Pack). */
final class Library {
    static final class Station {
        final String name;
        final String logo;                 // file name inside the pack, or ""
        final List<Schedule.Track> songs = new ArrayList<>();
        final List<Schedule.Track> jingles = new ArrayList<>();
        Schedule schedule;

        Station(String name, String logo) {
            this.name = name;
            this.logo = logo;
        }
    }

    static void parse(JSONArray arr, List<Station> out) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Station st = new Station(o.getString("name"), o.optString("logo", ""));
            JSONArray songs = o.getJSONArray("songs");
            for (int j = 0; j < songs.length(); j++) {
                JSONObject s = songs.getJSONObject(j);
                st.songs.add(new Schedule.Track(s.getString("k"), s.optString("t", ""), s.optString("a", ""), s.getDouble("s")));
            }
            JSONArray jingles = o.optJSONArray("jingles");
            if (jingles != null) {
                for (int j = 0; j < jingles.length(); j++) {
                    JSONObject s = jingles.getJSONObject(j);
                    st.jingles.add(new Schedule.Track(s.getString("k"), "", "", s.getDouble("s")));
                }
            }
            st.schedule = new Schedule(st.name, st.songs, st.jingles);
            out.add(st);
        }
    }
}
