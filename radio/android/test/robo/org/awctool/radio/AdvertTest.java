package org.awctool.radio;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/** The content switches in the player: remembered, and the playing station changes to its other programme. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
public class AdvertTest {
    private RadioService radio;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        radio = Harness.radio();
    }

    private static List<Switch> switches(View v) {
        List<Switch> out = new ArrayList<>();
        if (v instanceof Switch) out.add((Switch) v);
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) out.addAll(switches(((ViewGroup) v).getChildAt(i)));
        }
        return out;
    }

    private Schedule playingSchedule() {
        Object engine = ReflectionHelpers.getField(radio, "engine");
        return ReflectionHelpers.getField(engine, "schedule");
    }

    /** The switches of the sheet that is open. */
    private static List<Switch> sheet() {
        return switches(ShadowDialog.getLatestDialog().getWindow().getDecorView());
    }

    @Test
    public void standardAtFirstAndSwitchable() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        MainActivity a = c.get();
        Assert.assertEquals("DJ talk and jingles at first", Schedule.STANDARD, RadioService.content(a));
        List<Library.Station> list = PackStore.get(a).stations;
        Library.Station flash = list.get(Catalog.indexOf(list, "Flash FM"));
        Library.Station club = list.get(Catalog.indexOf(list, "Jack of Hearts"));
        Assert.assertEquals(70, flash.ads.size());
        Assert.assertTrue(club.ads.isEmpty());
        final int has = Schedule.EVERYTHING & ~Schedule.TALK_ON;             // Flash FM has no talk of its own
        Assert.assertEquals(has, flash.available());

        radio.play(Catalog.indexOf(list, "Flash FM"));
        ReflectionHelpers.setField(a, "service", radio);
        Harness.pass(500);
        Assert.assertSame("plays without adverts", flash.programme(false, 3), playingSchedule());
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        PlayerScreen player = ReflectionHelpers.getField(a, "player");
        Assert.assertTrue("the player says what is on", SettingsTest.shows(player, "Inhalte: DJ-Beiträge, Jingles"));
        Assert.assertTrue(ScreenshotTest.tap(player, "Inhalte: DJ-Beiträge, Jingles"));
        Harness.pass(400);
        List<Switch> five = sheet();
        Assert.assertEquals(5, five.size());
        Assert.assertFalse(five.get(0).isChecked() || five.get(1).isChecked() || five.get(3).isChecked());
        Assert.assertTrue(five.get(2).isChecked() && five.get(4).isChecked());

        five.get(0).performClick();                                          // adverts on
        Harness.pass(400);
        int withAds = Schedule.STANDARD | Schedule.ADS;
        Assert.assertEquals("remembered", withAds, RadioService.content(a));
        Assert.assertSame("now the programme with adverts", flash.programme(withAds, 3), playingSchedule());
        Assert.assertNotSame(flash.programme(false, 3), playingSchedule());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals("Flash FM", radio.stationName);
        Assert.assertTrue("the sheet stays open", ShadowDialog.getLatestDialog().isShowing());
        ScreenshotTest.shot(a, "54_content_sheet", ShadowDialog.getLatestDialog());
        Assert.assertFalse("nothing to switch", five.get(3).isEnabled());
        for (int i = 1; i < 5; i++) if (five.get(i).isEnabled() && !five.get(i).isChecked()) five.get(i).performClick();
        Harness.pass(400);
        Assert.assertSame(flash.programme(true, 3), playingSchedule());
        ShadowDialog.getLatestDialog().dismiss();
        Harness.pass(600);
        Assert.assertTrue(SettingsTest.shows(player, "Inhalte: alles"));

        radio.play(Catalog.indexOf(list, "Jack of Hearts"));                // a club has nothing between its songs
        Harness.pass(600);
        Assert.assertSame(club.programme(false, 3), playingSchedule());
        Assert.assertTrue(SettingsTest.shows(player, "Dieser Sender spielt nur Songs"));
        Assert.assertEquals("the wish stays for the other stations", has, RadioService.content(a));

        radio.play(Catalog.indexOf(list, "Flash FM"));
        Harness.pass(600);
        Assert.assertSame(flash.programme(true, 3), playingSchedule());
        for (int kind : Content.KINDS) RadioService.setContent(a, kind, false);
        Harness.pass(400);
        Assert.assertSame(flash.programme(0, 3), playingSchedule());
        Assert.assertTrue(SettingsTest.shows(player, "Inhalte: nur Songs"));
        for (Schedule.Item it : playingSchedule().itemsFrom(System.currentTimeMillis() / 1000.0, 200)) {
            Assert.assertEquals(it.track.key, Schedule.SONG, it.kind);
        }
    }

    @Test
    public void theOldSwitchCounts() {
        Assert.assertEquals(Schedule.STANDARD, RadioService.content(Harness.app()));
        Harness.app().getSharedPreferences(RadioService.PREFS, 0).edit().putBoolean(RadioService.PREF_ADS, true).commit();
        Assert.assertEquals("was switched on in 1.16", Schedule.EVERYTHING, RadioService.content(Harness.app()));
        RadioService.setContent(Harness.app(), Schedule.NEWS_ON, false);
        Assert.assertEquals(Schedule.EVERYTHING & ~Schedule.NEWS_ON, RadioService.content(Harness.app()));
    }
}
