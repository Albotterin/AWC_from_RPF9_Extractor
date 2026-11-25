package org.awctool.radio;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.TextView;

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

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Renders every screen with Android's own drawing code into PNG files, to look at them without a phone. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScreenshotTest {
    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        radio = Harness.radio();
    }

    private RadioService radio;

    static void shot(Activity a, String name, Dialog dialog) throws Exception {
        Bitmap bmp = capture(a.getWindow());
        if (dialog != null) {
            Harness.pass(400);
            Bitmap top = capture(dialog.getWindow());
            Canvas c = new Canvas(bmp);
            c.drawColor(0x99000000);
            c.drawBitmap(top, 0, bmp.getHeight() - top.getHeight(), null);          // the sheets sit at the bottom
        }
        File out = new File(System.getProperty("awc.shots"), name + ".png");
        try (FileOutputStream o = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    /** What the window shows, drawn by Android's renderer (rounded corners and shadows included). */
    private static Bitmap capture(Window window) {
        View decor = window.getDecorView();
        Bitmap bmp = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        final int[] result = {-1};
        PixelCopy.request(window, bmp, r -> result[0] = r, new Handler(Looper.getMainLooper()));
        Harness.pass(100);
        Assert.assertEquals("screenshot", PixelCopy.SUCCESS, result[0]);
        return bmp;
    }

    static List<View> all(View root) {
        List<View> out = new ArrayList<>();
        out.add(root);
        if (root instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) out.addAll(all(((ViewGroup) root).getChildAt(i)));
        }
        return out;
    }

    static boolean tap(View root, String text) {
        for (View v : all(root)) {
            boolean match = v instanceof TextView && ((TextView) v).getText().toString().trim().equals(text)
                    || text.contentEquals(String.valueOf(v.getContentDescription()));
            if (!match) continue;
            View t = v;
            while (t != null && !t.isClickable()) t = t.getParent() instanceof View ? (View) t.getParent() : null;
            if (t != null) {
                t.performClick();
                return true;
            }
        }
        return false;
    }

    private RadioService playing(String station) {
        RadioService s = radio;
        List<Library.Station> list = PackStore.get(Harness.app()).stations;
        s.play(Catalog.indexOf(list, station));
        for (int i = 0; i < 20; i++) Harness.pass(100);
        s.title = "Self Control";
        s.artist = "Laura Branigan";
        s.nextText = "Kate Bush - Running Up That Hill";
        s.itemStart = System.currentTimeMillis() / 1000.0 - 83;
        s.itemEnd = s.itemStart + 247;
        return s;
    }

    @Test
    public void radioAndPlayer() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        MainActivity a = c.get();
        Assert.assertEquals(Harness.folder().list((d, n) -> n.endsWith(".radiopak")).length,
                PackStore.get(a).stations.size());
        shot(a, "01_radio_idle", null);

        RadioService s = playing("Flash FM");
        ReflectionHelpers.setField(a, "service", s);
        Alarm alarm = new Alarm();
        alarm.station = "Flash FM";
        Alarms.save(a, alarm);
        Harness.pass(6000);
        s.title = "Self Control";
        s.artist = "Laura Branigan";
        Harness.pass(600);
        shot(a, "02_radio_playing", null);

        tap(a.getWindow().getDecorView(), "Self Control");
        PlayerScreen player = ReflectionHelpers.getField(a, "player");
        Harness.pass(800);
        Assert.assertEquals(View.VISIBLE, player.getVisibility());
        player.setTranslationY(0);
        shot(a, "03_player", null);

        ReflectionHelpers.callInstanceMethod(a, "showSleepSheet");
        Harness.pass(500);
        shot(a, "04_sleep_sheet", ShadowDialog.getLatestDialog());
        ShadowDialog.getLatestDialog().dismiss();

        for (String name : new String[]{"WKTT Talk Radio", "Leonida Jazz Radio", "The Vault Radio"}) {
            s = playing(name);
            ReflectionHelpers.setField(a, "service", s);
            Harness.pass(600);
            shot(a, "05_player_" + name.split(" ")[0], null);
        }
        Harness.app().getSharedPreferences(RadioService.PREFS, 0).edit().putBoolean(RadioService.PREF_ADS, true).commit();
        s = playing("Flash FM");
        ReflectionHelpers.setField(a, "service", s);
        Harness.pass(600);
        s.title = "Werbung";
        s.artist = "Werbung";
        s.nextText = "Werbung";
        Harness.pass(600);
        shot(a, "07_player_advert", null);

        a.onBackPressed();
        Harness.pass(800);
        shot(a, "06_radio_after_back", null);
        Assert.assertEquals(View.GONE, player.getVisibility());
    }

    @Test
    public void alarmsAndEditor() throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        MainActivity a = c.get();
        Assert.assertTrue(tap(a.getWindow().getDecorView(), "Wecker"));
        Harness.pass(500);
        shot(a, "10_alarms_empty", null);

        Alarm one = new Alarm();
        one.station = "Flash FM";
        one.hour = 6;
        one.minute = 45;
        one.days = Alarm.WORKDAYS;
        Alarms.save(a, one);
        Alarm two = new Alarm();
        two.station = "V-Rock";
        two.hour = 9;
        two.minute = 30;
        two.days = Alarm.WEEKEND;
        Alarms.save(a, two);
        Alarm three = new Alarm();
        three.hour = 14;
        three.enabled = false;
        Alarms.save(a, three);
        c.pause().stop().start().resume();
        Harness.settle();
        shot(a, "11_alarms", null);

        ActivityController<AlarmEditActivity> e = Robolectric.buildActivity(AlarmEditActivity.class,
                new Intent().putExtra("id", one.id)).setup();
        Harness.settle();
        shot(e.get(), "12_editor", null);
        Assert.assertTrue(tap(e.get().getWindow().getDecorView(), "Sender"));
        Harness.pass(500);
        shot(e.get(), "13_station_sheet", ShadowDialog.getLatestDialog());
    }

    @Test
    public void ringing() throws Exception {
        PackStore.get(Harness.app());
        Alarm alarm = new Alarm();
        alarm.id = 1;
        alarm.station = "Vice City FM";
        AlarmService s = Robolectric.buildService(AlarmService.class).create().get();
        Intent i = new Intent(Harness.app(), AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, alarm.toJson().toString());
        s.onStartCommand(i, 0, 1);
        Harness.settle();
        for (int k = 0; k < 20; k++) Harness.pass(100);
        s.title = "Take On Me";
        s.artist = "a-ha";
        ActivityController<AlarmActivity> c = Robolectric.buildActivity(AlarmActivity.class).setup();
        Harness.pass(100);
        shot(c.get(), "20_ringing", null);

        s.fallback = true;
        s.station = null;
        s.title = s.artist = "";
        Harness.pass(600);
        shot(c.get(), "21_ringing_fallback", null);
    }

    @Test
    @Config(qualifiers = "de-rDE-w320dp-h568dp-port-xhdpi")
    public void smallPhone() throws Exception {
        small("30_small");
    }

    @Test
    @Config(qualifiers = "en-rUS-w851dp-h393dp-land-xhdpi")
    public void sideways() throws Exception {
        small("40_land");
    }

    private void small(String prefix) throws Exception {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        MainActivity a = c.get();
        RadioService s = playing("The Sniper Of La Perle");
        ReflectionHelpers.setField(a, "service", s);
        Harness.pass(600);
        shot(a, prefix + "_radio", null);
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(800);
        View player = ReflectionHelpers.getField(a, "player");
        player.setTranslationY(0);
        shot(a, prefix + "_player", null);
        Alarm alarm = new Alarm();
        alarm.station = "The Sniper Of La Perle";
        Alarms.save(a, alarm);
        ActivityController<AlarmEditActivity> e = Robolectric.buildActivity(AlarmEditActivity.class,
                new Intent().putExtra("id", 1)).setup();
        Harness.settle();
        shot(e.get(), prefix + "_editor", null);
        AlarmService ring = Robolectric.buildService(AlarmService.class).create().get();
        ring.onStartCommand(new Intent(Harness.app(), AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, alarm.toJson().toString()), 0, 1);
        Harness.settle();
        ActivityController<AlarmActivity> r = Robolectric.buildActivity(AlarmActivity.class).setup();
        Harness.pass(600);
        shot(r.get(), prefix + "_ringing", null);
    }

    @Test
    public void firstStart() throws Exception {
        Harness.app().getSharedPreferences("pack", 0).edit().clear().commit();
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        shot(c.get(), "50_first_start", null);
    }
}
