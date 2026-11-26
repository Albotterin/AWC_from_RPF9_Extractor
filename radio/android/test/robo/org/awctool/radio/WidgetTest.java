package org.awctool.radio;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
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
import org.robolectric.shadows.ShadowAppWidgetManager;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/**
 * The widgets of the home screen: they show what the radio does and their taps steer it, a tile gets its station and
 * keeps it, and they look right in different sizes.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
public class WidgetTest {
    private RadioService radio;
    private ShadowAppWidgetManager widgets;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        radio = Harness.radio();
        widgets = Shadows.shadowOf(AppWidgetManager.getInstance(Harness.app()));
    }

    private static String text(View widget, int id) {
        TextView t = widget.findViewById(id);
        return t == null || t.getVisibility() != View.VISIBLE ? "" : t.getText().toString();
    }

    /** Tap a part of a widget and hand what it sends to the radio, as Android would. */
    private void tap(View widget, int id) {
        Shadows.shadowOf(Harness.application()).clearStartedServices();
        View v = widget.findViewById(id);
        if (v == null) v = widget;
        Assert.assertTrue("something happens", v.performClick());
        Harness.pass(200);
        Intent sent = Shadows.shadowOf(Harness.application()).getNextStartedService();
        Assert.assertNotNull("the tap reaches the radio", sent);
        Assert.assertEquals(RadioService.ACTION_WIDGET, sent.getAction());
        radio.onStartCommand(sent, 0, 1);
        Harness.settle();
        Harness.pass(600);
    }

    private static String playDescription(View widget) {
        return String.valueOf(widget.findViewById(R.id.w_play).getContentDescription());
    }

    @Test
    public void player() {
        int id = widgets.createWidget(PlayerWidget.class, R.layout.widget_player);
        Harness.settle();
        View v = widgets.getViewFor(id);
        Assert.assertEquals("AWC Radio", text(v, R.id.w_title));
        Assert.assertEquals("Mit Play starten", text(v, R.id.w_artist));
        List<Library.Station> list = PackStore.get(Harness.app()).stations;
        int flash = Catalog.indexOf(list, "Flash FM");

        radio.play(flash);
        Harness.pass(800);
        v = widgets.getViewFor(id);
        Assert.assertEquals("Pause", playDescription(v));
        Assert.assertEquals(radio.title, text(v, R.id.w_title));
        Assert.assertTrue(text(v, R.id.w_artist), text(v, R.id.w_artist).endsWith("Flash FM"));

        tap(v, R.id.w_next);
        Assert.assertEquals(list.get(flash + 1).name, radio.stationName);
        v = widgets.getViewFor(id);
        Assert.assertTrue(text(v, R.id.w_artist), text(v, R.id.w_artist).endsWith(radio.stationName));
        tap(v, R.id.w_prev);
        Assert.assertEquals("Flash FM", radio.stationName);

        tap(widgets.getViewFor(id), R.id.w_play);
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        Assert.assertEquals(Harness.app().getString(R.string.act_play), playDescription(widgets.getViewFor(id)));
        tap(widgets.getViewFor(id), R.id.w_play);
        Assert.assertEquals(RadioService.PLAYING, radio.state);

        radio.stopAll();
        Harness.pass(300);
        v = widgets.getViewFor(id);
        Assert.assertEquals("the station heard last", "Flash FM", text(v, R.id.w_title));
        Assert.assertEquals("Mit Play starten", text(v, R.id.w_artist));
        Assert.assertEquals(Harness.app().getString(R.string.act_play), playDescription(v));
        tap(v, R.id.w_play);                                              // off: it tunes in to that station
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals("Flash FM", radio.stationName);
        radio.stopAll();
    }

    @Test
    public void somethingOnDemand() {
        int id = widgets.createWidget(PlayerWidget.class, R.layout.widget_player);
        Harness.settle();
        Library.Station podcast = PackStore.get(Harness.app()).podcasts.get(0);
        radio.playEpisode(podcast.name, 0);
        Harness.pass(800);
        View v = widgets.getViewFor(id);
        Assert.assertEquals(radio.title, text(v, R.id.w_title));
        Assert.assertTrue(text(v, R.id.w_artist), text(v, R.id.w_artist).contains(podcast.name));
        tap(v, R.id.w_next);
        Assert.assertEquals(1, radio.episodeIndex());
        radio.stopAll();
    }

    @Test
    public void stationTile() throws Exception {
        int id = widgets.createWidget(StationWidget.class, R.layout.widget_station);
        Harness.settle();
        View v = widgets.getViewFor(id);
        Assert.assertEquals("Sender wählen", text(v, R.id.w_station));

        // going back without a choice: no tile
        Intent ask = new Intent(Harness.app(), StationWidgetActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        ActivityController<StationWidgetActivity> c = Robolectric.buildActivity(StationWidgetActivity.class, ask).setup();
        Harness.settle();
        ShadowDialog.getLatestDialog().dismiss();
        Harness.pass(500);
        Assert.assertTrue(c.get().isFinishing());
        Assert.assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(c.get()).getResultCode());
        Assert.assertEquals("", Widgets.stationOf(Harness.app(), id));

        c = Robolectric.buildActivity(StationWidgetActivity.class, ask).setup();
        Harness.settle();
        Assert.assertTrue(ScreenshotTest.tap(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "Emotion 98.3"));
        Harness.pass(500);
        Assert.assertTrue(c.get().isFinishing());
        Assert.assertEquals(Activity.RESULT_OK, Shadows.shadowOf(c.get()).getResultCode());
        Assert.assertEquals("Emotion 98.3", Widgets.stationOf(Harness.app(), id));
        v = widgets.getViewFor(id);
        Assert.assertEquals(View.GONE, v.findViewById(R.id.w_badge).getVisibility());
        Assert.assertEquals("Emotion 98.3 abspielen", String.valueOf(v.findViewById(R.id.w_logo).getContentDescription()));
        Assert.assertTrue(new File(Harness.app().getFilesDir(), "widget_" + id + ".png").length() > 1000);

        tap(v, android.R.id.background);
        Assert.assertEquals("Emotion 98.3", radio.stationName);
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        v = widgets.getViewFor(id);
        Assert.assertEquals(View.VISIBLE, v.findViewById(R.id.w_badge).getVisibility());
        tap(v, android.R.id.background);                                  // again: pause
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        tap(widgets.getViewFor(id), android.R.id.background);
        Assert.assertEquals(RadioService.PLAYING, radio.state);

        radio.play(Catalog.indexOf(PackStore.get(Harness.app()).stations, "Flash FM"));
        Harness.pass(500);
        Assert.assertEquals("another station is on", View.GONE, widgets.getViewFor(id).findViewById(R.id.w_badge).getVisibility());
        tap(widgets.getViewFor(id), android.R.id.background);
        Assert.assertEquals("Emotion 98.3", radio.stationName);
        radio.stopAll();
        Harness.pass(300);
        Assert.assertEquals(View.GONE, widgets.getViewFor(id).findViewById(R.id.w_badge).getVisibility());

        new StationWidget().onDeleted(Harness.app(), new int[]{id});
        Assert.assertEquals("", Widgets.stationOf(Harness.app(), id));
        Assert.assertFalse(new File(Harness.app().getFilesDir(), "widget_" + id + ".png").exists());
    }

    @Test
    public void afterARestartTheRadioReadsThePacksFirst() {
        int id = widgets.createWidget(PlayerWidget.class, R.layout.widget_player);
        Harness.settle();
        org.robolectric.util.ReflectionHelpers.setStaticField(PackStore.class, "catalog", null);
        org.robolectric.util.ReflectionHelpers.setStaticField(PackStore.class, "ready", false);
        tap(widgets.getViewFor(id), R.id.w_play);
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        radio.stopAll();
    }

    @Test
    public void withoutPacksNothingBreaks() {
        Harness.app().getSharedPreferences("pack", 0).edit().clear().commit();
        org.robolectric.util.ReflectionHelpers.setStaticField(PackStore.class, "catalog", null);
        int id = widgets.createWidget(PlayerWidget.class, R.layout.widget_player);
        Harness.settle();
        tap(widgets.getViewFor(id), R.id.w_play);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
        tap(widgets.getViewFor(id), R.id.w_next);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
    }

    @Test
    public void sizes() throws Exception {
        List<Library.Station> list = PackStore.get(Harness.app()).stations;
        radio.play(Catalog.indexOf(list, "Flash FM"));
        Harness.pass(500);
        RadioService s = radio;
        Widgets.on(Harness.app(), list.get(s.stationIndex), "Flash FM", "Flash FM", "Self Control", "Laura Branigan", true);
        int tile = widgets.createWidget(StationWidget.class, R.layout.widget_station);
        Widgets.choose(Harness.app(), tile, list.get(Catalog.indexOf(list, "Flash FM")));
        int other = widgets.createWidget(StationWidget.class, R.layout.widget_station);
        Widgets.choose(Harness.app(), other, list.get(Catalog.indexOf(list, "WKTT Talk Radio")));

        View row = apply(Widgets.player(Harness.app(), 330, 70), 330, 70);
        Assert.assertEquals(View.VISIBLE, row.findViewById(R.id.w_next).getVisibility());
        View narrow = apply(Widgets.player(Harness.app(), 220, 70), 220, 70);
        Assert.assertEquals(View.GONE, narrow.findViewById(R.id.w_next).getVisibility());
        Assert.assertEquals(View.VISIBLE, narrow.findViewById(R.id.w_texts).getVisibility());
        View tiny = apply(Widgets.player(Harness.app(), 120, 70), 120, 70);
        Assert.assertEquals(View.INVISIBLE, tiny.findViewById(R.id.w_texts).getVisibility());
        View large = apply(Widgets.player(Harness.app(), 330, 160), 330, 160);
        Assert.assertEquals("Flash FM", text(large, R.id.w_station));
        Assert.assertEquals("Laura Branigan", text(large, R.id.w_artist));
        View small = apply(Widgets.tileOf(Harness.app(), tile, 72), 72, 72);
        Assert.assertEquals(View.GONE, small.findViewById(R.id.w_station).getVisibility());
        View big = apply(Widgets.tileOf(Harness.app(), other, 150), 150, 150);
        Assert.assertEquals("WKTT Talk Radio", text(big, R.id.w_station));
        // every button is inside its widget and large enough to hit
        for (View w : new View[]{row, narrow, tiny, large}) {
            View play = w.findViewById(R.id.w_play);
            Assert.assertTrue(play.getWidth() >= 80 && play.getRight() <= w.getWidth() && play.getHeight() >= 80);
        }
        Assert.assertTrue(((ImageView) row.findViewById(R.id.w_logo)).getWidth() >= 90);

        radio.stopAll();
        Harness.pass(300);
        View off = apply(Widgets.player(Harness.app(), 330, 70), 330, 70);

        // all of them on one picture, like a home screen
        View[] shown = {row, narrow, tiny, large, off, small, big};
        int[][] at = {{16, 16}, {16, 98}, {248, 98}, {16, 180}, {16, 352}, {16, 434}, {104, 434}};
        Bitmap screen = Bitmap.createBitmap(393 * 2, 600 * 2, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(screen);
        canvas.drawColor(0xFF4A6B8A);
        for (int i = 0; i < shown.length; i++) {
            Bitmap b = Bitmap.createBitmap(shown[i].getWidth(), shown[i].getHeight(), Bitmap.Config.ARGB_8888);
            shown[i].draw(new Canvas(b));
            canvas.drawBitmap(b, at[i][0] * 2, at[i][1] * 2, new Paint());
        }
        try (FileOutputStream o = new FileOutputStream(new File(System.getProperty("awc.shots"), "90_widgets.png"))) {
            screen.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    /** A widget drawn by the home screen in `w` x `h` dp. */
    private static View apply(RemoteViews views, int w, int h) {
        FrameLayout parent = new FrameLayout(Harness.app());
        View v = views.apply(Harness.app(), parent);
        int pw = w * 2, ph = h * 2;                                       // xhdpi
        v.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, pw, ph);
        return v;
    }
}
