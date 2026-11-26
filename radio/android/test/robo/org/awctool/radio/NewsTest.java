package org.awctool.radio;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The programmes of the real packs: every recording a programme puts on air lies in the station's own pack or in the
 * news pack, without the switch a music station plays only songs and jingles, and the night show comes in the evening.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
public class NewsTest {
    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        Harness.radio();
    }

    private static double evening() {
        Calendar c = Calendar.getInstance();
        c.set(2026, Calendar.NOVEMBER, 26, 19, 0, 0);
        return c.getTimeInMillis() / 1000.0;
    }

    @Test
    public void everyItemCanBePlayed() {
        org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        List<Library.Station> list = PackStore.get(Harness.app()).stations;
        int withNews = 0, ruled = 0;
        Map<String, Integer> honeyKinds = new HashMap<>();
        for (Library.Station st : list) {
            for (boolean extras : new boolean[]{false, true}) {
                Schedule programme = st.programme(extras, 3);
                for (Schedule.Item it : programme.itemsFrom(evening(), 400)) {     // from the evening into the next day
                    Pack pack = st.packOf(it.track);
                    Assert.assertNotNull(st.name + ": " + it.kind + " " + it.track.key, pack.entry("t/" + it.track.key));
                    for (Schedule.Voice v : programme.voices(it)) {
                        Assert.assertNotNull(st.name + ": line " + v.line.key, st.packOf(v.line).entry("t/" + v.line.key));
                    }
                    if (!extras && !st.talk) Assert.assertTrue(st.name + ": " + it.kind, it.jingle || Schedule.SONG.equals(it.kind));
                    if (!extras) Assert.assertFalse(st.name, it.ad);
                    if (extras && st.name.equals("Honey FM")) honeyKinds.merge(it.kind + " " + it.track.title, 1, Integer::sum);
                }
            }
            if (st.rules != null) ruled++;
            if (st.playsNews) {
                withNews++;
                Assert.assertTrue(st.name, st.hasMore());
            }
        }
        Assert.assertEquals("music stations with news", 18, withNews);
        Assert.assertEquals("stations with the game's rules", 20, ruled);
        Library.Station honey = list.get(Catalog.indexOf(list, "Honey FM"));
        Assert.assertEquals(56, honey.songs.size());
        Assert.assertTrue("news", honeyKinds.keySet().stream().anyMatch(k -> k.startsWith("news")));
        Assert.assertTrue("the night show", honeyKinds.containsKey("talk Liquid City"));
        Library.Station club = list.get(Catalog.indexOf(list, "Jack of Hearts"));
        Assert.assertFalse(club.hasMore());
    }
}
