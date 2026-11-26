package org.awctool.radio;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/** The settings page: crossfade and adverts reach the running radio, the packs and the information are there. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsTest {
    private RadioService radio;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.app().getSharedPreferences(RadioService.PREFS, 0).edit().clear().commit();
        Harness.choosePacks();
        Harness.workingPlayers();
        radio = Harness.radio();
    }

    private static <T extends View> List<T> views(View root, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (View v : ScreenshotTest.all(root)) if (type.isInstance(v)) out.add(type.cast(v));
        return out;
    }

    static boolean shows(View root, String text) {
        for (TextView t : views(root, TextView.class)) if (t.getText().toString().contains(text)) return true;
        return false;
    }

    private Schedule playing() {
        Engine e = ReflectionHelpers.getField(radio, "engine");
        return e.schedule();
    }

    private static Library.Station station(String name) {
        List<Library.Station> list = PackStore.get(Harness.app()).stations;
        return list.get(Catalog.indexOf(list, name));
    }

    @Test
    public void crossfadeAndAdverts() throws Exception {
        Context app = Harness.app();
        Assert.assertEquals("on, three seconds", 3.0, RadioService.crossfade(app), 0);
        Assert.assertEquals(Schedule.STANDARD, RadioService.content(app));
        ActivityController<MainActivity> main = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        radio.play(Catalog.indexOf(PackStore.get(app).stations, "Flash FM"));
        Harness.pass(500);
        Library.Station flash = station("Flash FM");
        Assert.assertSame(flash.programme(false, 3), playing());

        // the menu leads to the settings
        ReflectionHelpers.callInstanceMethod(main.get(), "showMenu");
        Harness.pass(300);
        Assert.assertTrue(ScreenshotTest.tap(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "Einstellungen"));
        Intent next = Shadows.shadowOf(main.get()).getNextStartedActivity();
        Assert.assertEquals(SettingsActivity.class.getName(), next.getComponent().getClassName());

        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Harness.settle();
        View root = a.getWindow().getDecorView();
        for (String text : new String[]{"Wiedergabe", "Überblenden", "Dauer", "3 s", "Zwischen den Songs", "Werbung", "Nachrichten",
                "DJ-Beiträge", "Talk und Sendungen", "Jingles", "DJ über Songs", "Pack-Ordner", "AWC-Radio", "Sender neu laden", "Auf Updates prüfen", "Beim Start prüfen",
                "Akku-Einstellungen", "AWC Radio 1.19"}) {
            Assert.assertTrue(text, shows(root, text));
        }
        ScreenshotTest.shot(a, "50_settings", null);
        List<Switch> switches = views(root, Switch.class);
        Assert.assertEquals(8, switches.size());
        Switch fade = switches.get(0), ads = switches.get(1);
        SeekBar bar = views(root, SeekBar.class).get(0);
        Assert.assertTrue(fade.isChecked() && bar.isEnabled() && !ads.isChecked());
        Assert.assertEquals(2, bar.getProgress());

        fade.performClick();                                              // off
        Harness.pass(500);
        Assert.assertEquals(0.0, RadioService.crossfade(app), 0);
        Assert.assertFalse(bar.isEnabled());
        Assert.assertSame("the radio goes on without crossfade", flash.programme(false, 0), playing());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        ScreenshotTest.shot(a, "51_settings_off", null);
        fade.performClick();
        Harness.pass(500);
        Assert.assertSame(flash.programme(false, 3), playing());

        // dragging saves once, at the end
        SeekBar.OnSeekBarChangeListener listener = Shadows.shadowOf(bar).getOnSeekBarChangeListener();
        listener.onStartTrackingTouch(bar);
        bar.setProgress(7);
        listener.onProgressChanged(bar, 7, true);
        Assert.assertTrue(shows(root, "8 s"));
        Assert.assertEquals("not yet", 3.0, RadioService.crossfade(app), 0);
        listener.onStopTrackingTouch(bar);
        Harness.pass(500);
        Assert.assertEquals(8.0, RadioService.crossfade(app), 0);
        Assert.assertSame(flash.programme(false, 8), playing());

        Assert.assertTrue("DJ talk and jingles are on", switches.get(3).isChecked() && switches.get(5).isChecked()
                && !switches.get(2).isChecked() && !switches.get(4).isChecked());
        ads.performClick();
        Harness.pass(500);
        int withAds = Schedule.STANDARD | Schedule.ADS;
        Assert.assertEquals(withAds, RadioService.content(app));
        Assert.assertSame(flash.programme(withAds, 8), playing());
        switches.get(5).performClick();                                   // jingles off
        Harness.pass(500);
        Assert.assertSame(flash.programme(withAds & ~Schedule.JINGLES, 8), playing());
        java.util.Set<String> idents = new java.util.HashSet<>(flash.rules.pools.get("ident"));
        for (Schedule.Item it : playing().itemsFrom(System.currentTimeMillis() / 1000.0, 200)) {
            Assert.assertFalse("no station ident: " + it.track.key, idents.contains(it.track.key));
        }
        switches.get(5).performClick();
        Harness.pass(500);
        int heard = 0;
        for (Schedule.Item it : playing().itemsFrom(System.currentTimeMillis() / 1000.0, 200)) {
            if (idents.contains(it.track.key)) heard++;
        }
        Assert.assertTrue("the idents are back: " + heard, heard > 0);
        for (int i = 1; i <= 5; i++) if (!switches.get(i).isChecked()) switches.get(i).performClick();
        Harness.pass(500);
        Assert.assertEquals(Schedule.EVERYTHING, RadioService.content(app));
        Assert.assertSame(flash.programme(true, 8), playing());

        // the DJ: off unless switched on; he comes and goes without tuning in anew
        Switch dj = views(root, Switch.class).get(6);
        Assert.assertTrue(shows(root, "DJ"));
        Assert.assertFalse(dj.isChecked() || RadioService.dj(app));
        Object running = ReflectionHelpers.getField(radio, "engine");
        Assert.assertEquals(false, ReflectionHelpers.getField(running, "dj"));
        dj.performClick();
        Harness.pass(500);
        Assert.assertTrue(RadioService.dj(app));
        Assert.assertSame(running, ReflectionHelpers.getField(radio, "engine"));
        Assert.assertEquals(true, ReflectionHelpers.getField(running, "dj"));
        int said = 0;
        for (Schedule.Item it : flash.programme(true, 8).itemsFrom(System.currentTimeMillis() / 1000.0, 40)) {
            for (Schedule.Voice v : flash.programme(true, 8).voices(it)) {
                Assert.assertNotNull("line in the pack", flash.pack.entry("t/" + v.line.key));
                said++;
            }
        }
        Assert.assertTrue("the DJ has something to say: " + said, said > 5);
        ScreenshotTest.shot(a, "53_settings_dj", null);

        // a station without adverts is not restarted by the advert switch
        radio.play(Catalog.indexOf(PackStore.get(app).stations, "Jack of Hearts"));
        Harness.pass(500);
        Object engine = ReflectionHelpers.getField(radio, "engine");
        ads.performClick();
        Harness.pass(500);
        Assert.assertSame(engine, ReflectionHelpers.getField(radio, "engine"));

        // paused: the new programme is there for the next start
        radio.play(Catalog.indexOf(PackStore.get(app).stations, "Flash FM"));
        radio.toggle();
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        SharedPreferences p = app.getSharedPreferences(RadioService.PREFS, 0);
        p.edit().putInt(RadioService.PREF_FADE_SEC, 5).commit();
        Harness.pass(300);
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        Assert.assertEquals(Schedule.EVERYTHING & ~Schedule.ADS, RadioService.content(app));
        Assert.assertSame(flash.programme(RadioService.content(app), 5), playing());
        radio.toggle();
        Assert.assertEquals(RadioService.PLAYING, radio.state);

        // information and log
        Assert.assertTrue(ScreenshotTest.tap(root, "Info & Protokoll"));
        Harness.pass(300);
        Assert.assertTrue(shows(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "Podcasts: 5"));
        ShadowDialog.getLatestDialog().dismiss();
        // the folder can be changed from here
        Assert.assertTrue(ScreenshotTest.tap(root, "Pack-Ordner"));
        Assert.assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE,
                Shadows.shadowOf(a).getNextStartedActivityForResult().intent.getAction());
        radio.stopAll();
    }

    @Test
    public void valuesOutOfRange() {
        SharedPreferences p = Harness.app().getSharedPreferences(RadioService.PREFS, 0);
        p.edit().putInt(RadioService.PREF_FADE_SEC, 99).commit();
        Assert.assertEquals(12.0, RadioService.crossfade(Harness.app()), 0);
        p.edit().putInt(RadioService.PREF_FADE_SEC, -4).commit();
        Assert.assertEquals(1.0, RadioService.crossfade(Harness.app()), 0);
        p.edit().putBoolean(RadioService.PREF_FADE_ON, false).commit();
        Assert.assertEquals(0.0, RadioService.crossfade(Harness.app()), 0);
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h569dp-port-xhdpi")
    public void smallPhone() throws Exception {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Harness.settle();
        Assert.assertTrue(shows(a.getWindow().getDecorView(), "Crossfade"));
        ScreenshotTest.shot(a, "52_small_settings", null);
    }
}
