package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.view.View;
import android.widget.SeekBar;
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

/** The podcasts: the tab, the episodes, playing with pause and seeking, the remembered places, and back to the radio. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PodcastTest {
    private static final String SHOW = "Fuck Marry Murder";
    private RadioService radio;
    private MainActivity a;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.app().getSharedPreferences("podcasts", 0).edit().clear().commit();
        Harness.choosePacks();
        Harness.workingPlayers();                                    // every file "lasts" 600 s
        radio = Harness.radio();
    }

    private void open() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        a = c.get();
        ReflectionHelpers.setField(a, "service", radio);
    }

    /** What Android does with the commands the screen sends to the radio. */
    private void deliver() {
        Intent i;
        while ((i = Shadows.shadowOf(Harness.application()).getNextStartedService()) != null) {
            if (i.getComponent() != null && i.getComponent().getClassName().equals(RadioService.class.getName())) radio.onStartCommand(i, 0, 1);
        }
        Harness.pass(400);
    }

    private boolean tap(String text) {
        Shadows.shadowOf(Harness.application()).clearStartedServices();
        boolean found = ScreenshotTest.tap(a.getWindow().getDecorView(), text);
        deliver();
        return found;
    }

    private boolean visible(String text) {
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown() && ((TextView) v).getText().toString().contains(text)) return true;
        }
        return false;
    }

    private void seconds(int n) {
        for (int i = 0; i < n * 4; i++) Harness.pass(250);
    }

    private void command(String action, String extra, double value) {
        Intent i = new Intent(Harness.app(), RadioService.class).setAction(action);
        if (extra != null) {
            if (action.equals(RadioService.ACTION_SEEK)) i.putExtra(extra, value);
            else i.putExtra(extra, (int) value);
        }
        radio.onStartCommand(i, 0, 1);
        Harness.pass(400);
    }

    /** The titles of the buttons of the media notification. */
    private List<String> buttons() {
        NotificationManager nm = (NotificationManager) Harness.app().getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification(17);
        if (n == null) n = Shadows.shadowOf(radio).getLastForegroundNotification();
        List<String> out = new ArrayList<>();
        for (Notification.Action x : n.actions) out.add(x.title.toString());
        return out;
    }

    private Library.Station show() {
        List<Library.Station> list = PackStore.get(Harness.app()).podcasts;
        return list.get(Catalog.indexOf(list, SHOW));
    }

    @Test
    public void listPlayAndRemember() throws Exception {
        open();
        Assert.assertEquals(5, PackStore.get(a).podcasts.size());
        Assert.assertEquals("the podcasts are no stations", -1, Catalog.indexOf(PackStore.get(a).stations, SHOW));
        Assert.assertTrue(tap("Podcasts"));
        for (String name : new String[]{"Drunk Kings", "Final Act", SHOW, "The Safe Trap", "Vice City True Stories"}) {
            Assert.assertTrue(name, visible(name));
        }
        Assert.assertTrue(visible("6 Folgen"));
        ScreenshotTest.shot(a, "40_podcasts", null);

        Assert.assertTrue(tap(SHOW));
        Assert.assertTrue(visible("Folge 6"));
        Assert.assertTrue(visible("Alle Podcasts"));

        Assert.assertTrue(tap("Folge 1"));
        Assert.assertTrue(radio.isPodcast());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals(SHOW, radio.stationName);
        Assert.assertEquals("Folge 1", radio.title);
        Assert.assertEquals(show().songs.get(0).sec, radio.duration(), 0.01);
        seconds(20);
        double at = radio.position();
        Assert.assertTrue("runs: " + at, at > 15 && at < 25);
        Assert.assertTrue(visible("noch "));
        ScreenshotTest.shot(a, "41_episodes_playing", null);

        command(RadioService.ACTION_TOGGLE, null, 0);
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        double paused = radio.position();
        seconds(10);
        Assert.assertEquals("a paused podcast waits", paused, radio.position(), 0.6);
        Assert.assertEquals("[15 Sekunden zurück, Abspielen, 30 Sekunden vor, Stopp]", buttons().toString());
        command(RadioService.ACTION_TOGGLE, null, 0);
        seconds(5);
        Assert.assertTrue("goes on where it was", radio.position() > paused + 3 && radio.position() < paused + 8);

        command(RadioService.ACTION_JUMP, RadioService.EXTRA_SECONDS, 30);
        Assert.assertTrue(radio.position() > paused + 33);
        command(RadioService.ACTION_JUMP, RadioService.EXTRA_SECONDS, -15);
        command(RadioService.ACTION_SEEK, RadioService.EXTRA_SECONDS, 300);
        seconds(2);
        Assert.assertEquals(302, radio.position(), 1.5);
        // the large player
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        Assert.assertEquals(View.VISIBLE, screen.getVisibility());
        Assert.assertEquals(View.GONE, ((View) ReflectionHelpers.getField(a, "player")).getVisibility());
        screen.setTranslationY(0);
        Assert.assertTrue(visible("Folge 1 von 6"));
        ScreenshotTest.shot(a, "42_episode_player", null);
        SeekBar bar = null;
        for (View v : ScreenshotTest.all(screen)) if (v instanceof SeekBar) bar = (SeekBar) v;
        Assert.assertNotNull(bar);
        Assert.assertEquals(1000 * radio.position() / radio.duration(), bar.getProgress(), 3);
        Assert.assertTrue(tap("30 Sekunden vor"));
        Assert.assertTrue(radio.position() > 330);
        Assert.assertTrue(tap("15 Sekunden zurück"));
        Assert.assertTrue(radio.position() < 325);

        Assert.assertTrue(tap("Nächste Folge"));
        Assert.assertEquals(1, radio.episodeIndex());
        Assert.assertEquals("Folge 2", radio.title);
        double left = new Heard(a).position(show().songs.get(0).key);
        Assert.assertEquals("the place in episode 1 is kept", 318, left, 8);

        // the radio takes over, the large player changes with it
        radio.play(Catalog.indexOf(PackStore.get(a).stations, "Flash FM"));
        Harness.pass(800);
        Assert.assertFalse(radio.isPodcast());
        Assert.assertEquals("Flash FM", radio.stationName);
        Assert.assertEquals(View.VISIBLE, ((View) ReflectionHelpers.getField(a, "player")).getVisibility());
        Assert.assertEquals(View.GONE, screen.getVisibility());
        Assert.assertFalse(buttons().toString(), buttons().contains("30 Sekunden vor"));

        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, false));
        Harness.pass(600);
        Assert.assertTrue(tap("Folge 1"));
        seconds(2);
        Assert.assertTrue(radio.isPodcast());
        Assert.assertEquals("and goes on there", left + 2, radio.position(), 2.5);

        // the end of an episode leads to the next, the end of the last one stops
        command(RadioService.ACTION_SEEK, RadioService.EXTRA_SECONDS, 590);
        seconds(15);
        Assert.assertEquals(1, radio.episodeIndex());
        Assert.assertTrue(new Heard(a).done(show().songs.get(0).key));
        Assert.assertTrue(visible("Gehört"));
        radio.playEpisode(SHOW, 5);
        Harness.pass(400);
        command(RadioService.ACTION_SEEK, RadioService.EXTRA_SECONDS, 590);
        seconds(15);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
        Assert.assertFalse(radio.isPodcast());
        Assert.assertTrue(new Heard(a).done(show().songs.get(5).key));
        ScreenshotTest.shot(a, "43_episodes_heard", null);

        tap("Alle Podcasts");
        Assert.assertTrue("the next one not heard yet", visible("Weiter mit Folge 2"));
        ScreenshotTest.shot(a, "44_podcasts_progress", null);
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue("back on the radio tab", visible("AWC Radio"));
    }

    @Test
    public void twoAreas() throws Exception {
        open();
        tap("Podcasts");
        Assert.assertFalse("nothing to go on with yet: no small player under Podcasts", visible("Zum Einschalten"));
        ReflectionHelpers.callInstanceMethod(a, "showMenu");
        Harness.pass(300);
        Assert.assertFalse("adverts belong to the radio", dialogShows("Werbung einschalten"));
        ShadowDialog.getLatestDialog().dismiss();
        tap("Radio");
        Assert.assertTrue("the radio offers its station", visible("Zum Einschalten"));
        ReflectionHelpers.callInstanceMethod(a, "showMenu");
        Harness.pass(300);
        Assert.assertTrue(dialogShows("Werbung einschalten"));
        ShadowDialog.getLatestDialog().dismiss();

        tap("Podcasts");
        tap("Final Act");
        tap("Folge 2");
        seconds(20);
        command(RadioService.ACTION_STOP, null, 0);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
        tap("Alle Podcasts");
        Assert.assertTrue("the small player offers the podcast", visible("Weiter mit Folge 2"));
        Assert.assertFalse(visible("Zum Einschalten"));
        ScreenshotTest.shot(a, "48_podcasts_resume", null);
        tap("Radio");
        Assert.assertTrue("the radio area keeps its station", visible("Zum Einschalten"));
        tap("Podcasts");
        Assert.assertTrue(tap("Abspielen"));                                     // the small player's button
        Assert.assertTrue(radio.isPodcast());
        Assert.assertEquals("Final Act", radio.stationName);
        Assert.assertEquals(1, radio.episodeIndex());
        seconds(2);
        Assert.assertTrue("goes on at the place", radio.position() > 18);
        tap("Radio");
        Assert.assertTrue("what is playing stays within reach", visible("Folge 2"));
    }

    private boolean dialogShows(String text) {
        for (View v : ScreenshotTest.all(ShadowDialog.getLatestDialog().getWindow().getDecorView())) {
            if (v instanceof TextView && ((TextView) v).getText().toString().contains(text)) return true;
        }
        return false;
    }

    @Test
    public void sleepTimerAndAdvertSwitch() {
        open();
        radio.playEpisode("Drunk Kings", 0);
        seconds(3);
        command(RadioService.ACTION_ADS, null, 0);                    // the advert switch does not disturb a podcast
        Assert.assertTrue(radio.isPodcast());
        command(RadioService.ACTION_SLEEP, RadioService.EXTRA_MINUTES, 1);
        radio.sleepAt = System.currentTimeMillis() - 1;
        seconds(2);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
        Assert.assertTrue("the place is kept", new Heard(a).position(PackStore.get(a).podcasts.get(0).songs.get(0).key) >= 2);
    }

    private static List<String> ids(List<MediaBrowser.MediaItem> items) {
        List<String> out = new ArrayList<>();
        for (MediaBrowser.MediaItem i : items) out.add(i.getMediaId());
        return out;
    }

    @Test
    public void inTheCar() {
        Harness.settle();
        List<String> root = ids(radio.children("awc_root"));
        Assert.assertEquals(35, root.size());                          // 34 stations and the folder of the podcasts
        Assert.assertEquals("awc_podcasts", root.get(34));
        List<String> shows = ids(radio.children("awc_podcasts"));
        Assert.assertEquals(5, shows.size());
        Assert.assertEquals("pod:Drunk Kings", shows.get(0));
        List<MediaBrowser.MediaItem> eps = radio.children("pod:" + SHOW);
        Assert.assertEquals(6, eps.size());
        Assert.assertEquals("Folge 3", eps.get(2).getDescription().getTitle().toString());
        Assert.assertTrue(eps.get(2).isPlayable());
        radio.playEpisodeId(eps.get(2).getMediaId());
        Harness.pass(800);
        Assert.assertEquals(SHOW, radio.stationName);
        Assert.assertEquals(2, radio.episodeIndex());
        radio.skip(1);                                                // the car's "next" is the next episode
        Assert.assertEquals(3, radio.episodeIndex());
        radio.playEpisodeId("ep:");
        radio.playEpisodeId("ep:No such show#1");
        Assert.assertEquals(3, radio.episodeIndex());
        Assert.assertTrue(ids(radio.children("pod:No such show")).isEmpty());
        radio.stopAll();
    }

    @Test
    @Config(qualifiers = "de-rDE-w320dp-h569dp-port-xhdpi")
    public void smallPhone() throws Exception {
        open();
        tap("Podcasts");
        ScreenshotTest.shot(a, "45_small_podcasts", null);
        tap("The Safe Trap");
        tap("Folge 2");
        seconds(70);
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        screen.setTranslationY(0);
        ScreenshotTest.shot(a, "46_small_player", null);
    }

    @Test
    @Config(qualifiers = "en-rUS-w851dp-h393dp-land-xhdpi")
    public void sideways() throws Exception {
        open();
        radio.playEpisode("Vice City True Stories", 0);
        seconds(30);
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        screen.setTranslationY(0);
        ScreenshotTest.shot(a, "47_land_player", null);
    }
}
