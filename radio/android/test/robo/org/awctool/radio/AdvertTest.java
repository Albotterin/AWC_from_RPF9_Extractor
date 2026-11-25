package org.awctool.radio;

import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/** The advert switch: off at first, remembered, and the playing station changes to its programme with adverts. */
@RunWith(RobolectricTestRunner.class)
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

    /** What Android does with the commands the screen sends to the radio. */
    private void deliver() {
        Intent i;
        while ((i = Shadows.shadowOf(Harness.application()).getNextStartedService()) != null) {
            if (i.getComponent() != null && i.getComponent().getClassName().equals(RadioService.class.getName())) radio.onStartCommand(i, 0, 1);
        }
        Harness.pass(300);
    }

    @Test
    public void offByDefaultAndSwitchable() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        MainActivity a = c.get();
        Assert.assertFalse("adverts are off at first", RadioService.adverts(a));
        List<Library.Station> list = PackStore.get(a).stations;
        Library.Station flash = list.get(Catalog.indexOf(list, "Flash FM"));
        Library.Station club = list.get(Catalog.indexOf(list, "Jack of Hearts"));
        Assert.assertEquals(70, flash.ads.size());
        Assert.assertTrue(club.ads.isEmpty());

        radio.play(Catalog.indexOf(list, "Flash FM"));
        ReflectionHelpers.setField(a, "service", radio);
        Harness.pass(500);
        Assert.assertSame("plays without adverts", flash.schedule, playingSchedule());
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        PlayerScreen player = ReflectionHelpers.getField(a, "player");
        Switch toggle = switches(player).get(0);
        Assert.assertTrue(toggle.isEnabled());
        Assert.assertFalse(toggle.isChecked());

        Shadows.shadowOf(Harness.application()).clearStartedServices();
        toggle.performClick();
        deliver();
        Assert.assertTrue("remembered", RadioService.adverts(a));
        Assert.assertSame("now the programme with adverts", flash.programme(true), playingSchedule());
        Assert.assertNotSame(flash.schedule, playingSchedule());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals("Flash FM", radio.stationName);
        Assert.assertTrue(switches(player).get(0).isChecked());

        radio.play(Catalog.indexOf(list, "Jack of Hearts"));                // a club has no adverts
        Harness.pass(600);
        Assert.assertSame(club.schedule, playingSchedule());
        Assert.assertFalse(switches(player).get(0).isEnabled());
        Assert.assertTrue("the wish stays for the other stations", RadioService.adverts(a));

        radio.play(Catalog.indexOf(list, "Flash FM"));
        Harness.pass(600);
        Assert.assertSame(flash.programme(true), playingSchedule());
        Shadows.shadowOf(Harness.application()).clearStartedServices();
        switches(player).get(0).performClick();
        deliver();
        Assert.assertFalse(RadioService.adverts(a));
        Assert.assertSame(flash.schedule, playingSchedule());
    }
}
