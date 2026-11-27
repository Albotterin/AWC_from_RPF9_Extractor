package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.view.View;
import android.widget.EditText;
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
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/** The score: its tab with groups, sub groups and pieces, playing from there, the players, the car, and back. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ScoreTest {
    private static final String MISSIONS = "score:mission";
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

    private void command(String action) {
        radio.onStartCommand(new Intent(Harness.app(), RadioService.class).setAction(action), 0, 1);
        Harness.pass(400);
    }

    private List<String> buttons() {
        NotificationManager nm = (NotificationManager) Harness.app().getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification(17);
        if (n == null) n = Shadows.shadowOf(radio).getLastForegroundNotification();
        List<String> out = new ArrayList<>();
        for (Notification.Action x : n.actions) out.add(x.title.toString());
        return out;
    }

    private Library.Station missions() {
        return PackStore.get(Harness.app()).onDemand(MISSIONS);
    }

    @Test
    public void browseAndPlay() throws Exception {
        open();
        List<Library.Station> groups = PackStore.get(a).scores;
        Assert.assertEquals(5, groups.size());
        Assert.assertEquals("score:openworld", groups.get(0).name);
        Assert.assertEquals(MISSIONS, groups.get(1).name);
        Assert.assertEquals("score:compound", groups.get(2).name);
        Assert.assertEquals("score:activity", groups.get(3).name);
        Assert.assertEquals("score:places", groups.get(4).name);
        Assert.assertEquals("the score is no station", -1, Catalog.indexOf(PackStore.get(a).stations, MISSIONS));
        Assert.assertEquals("and no podcast", 5, PackStore.get(a).podcasts.size());
        Assert.assertTrue(tap("Score"));
        for (String name : new String[]{"Offene Welt", "Missionen", "Stützpunkte", "Aktivitäten", "Musik an Orten"}) {
            Assert.assertTrue(name, visible(name));
        }
        Assert.assertFalse("no small player of the radio under Score", visible("Zum Einschalten"));
        ScreenshotTest.shot(a, "60_score", null);

        Library.Station m = missions();
        String sub = m.songs.get(0).artist, second = m.songs.get(1).artist;
        Assert.assertTrue(tap("Missionen"));
        Assert.assertTrue(visible("Alle abspielen"));
        Assert.assertTrue(visible(sub));
        ScreenshotTest.shot(a, "61_score_subs", null);
        Assert.assertTrue(tap(sub));
        Assert.assertTrue(visible(m.songs.get(0).title));
        Assert.assertTrue(tap(m.songs.get(0).title));
        Assert.assertTrue(radio.isScore());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals(MISSIONS, radio.stationName);
        Assert.assertEquals("Missionen", radio.label());
        Assert.assertEquals(m.songs.get(0).title, radio.title);
        Assert.assertEquals(sub, radio.artist);
        Assert.assertEquals(m.songs.get(0).sec, radio.duration(), 0.01);
        seconds(20);
        Assert.assertTrue(radio.position() > 15 && radio.position() < 25);
        Assert.assertTrue("the small player", visible("Missionen • " + sub));
        Assert.assertEquals("[Voriger Sender, Pause, Nächster Sender, Stopp]".replace("Voriger Sender", buttons().get(0))
                .replace("Nächster Sender", buttons().get(2)), buttons().toString());
        Assert.assertFalse(buttons().toString(), buttons().contains("30 Sekunden vor"));
        ScreenshotTest.shot(a, "62_score_pieces_playing", null);

        // the large player: no jumps, the next piece instead
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        Assert.assertEquals(View.VISIBLE, screen.getVisibility());
        screen.setTranslationY(0);
        Harness.pass(300);
        Assert.assertTrue(visible("Alle Stücke"));
        Assert.assertTrue(visible(sub + " • Missionen"));
        Assert.assertFalse(visible("Folge"));
        for (View v : ScreenshotTest.all(screen)) {
            if ("30 Sekunden vor".contentEquals(String.valueOf(v.getContentDescription()))) Assert.assertFalse(v.isShown());
        }
        ScreenshotTest.shot(a, "63_score_player", null);
        Assert.assertTrue(tap("Nächstes Stück"));
        Assert.assertEquals(1, radio.episodeIndex());
        Assert.assertEquals(m.songs.get(1).title, radio.title);
        Assert.assertEquals(second, radio.artist);
        seconds(2);
        Assert.assertTrue(tap("Alle Stücke"));
        Assert.assertTrue("the pieces of what is playing", visible(m.songs.get(1).title));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Alle abspielen"));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Offene Welt"));
        Assert.assertTrue(visible("Läuft: " + m.songs.get(1).title));
        ScreenshotTest.shot(a, "64_score_playing", null);

        // a piece starts at its beginning again; its end leads to the next one
        radio.playEpisode(MISSIONS, 0);
        seconds(2);
        Assert.assertTrue(radio.position() < 4);
        radio.seek(radio.duration() - 3);
        seconds(1);
        radio.seek(599);                                              // the fake player ends at 600 s
        seconds(6);
        Assert.assertTrue("goes on by itself: " + radio.episodeIndex(), radio.episodeIndex() <= 1);
        Assert.assertEquals("the score leaves the memory of the podcasts alone", "", new Heard(a).lastShow());

        command(RadioService.ACTION_TOGGLE);
        Assert.assertEquals(RadioService.PAUSED, radio.state);
        command(RadioService.ACTION_STOP);
        Assert.assertEquals(RadioService.STOPPED, radio.state);
        tap("Radio");
        Assert.assertTrue("the radio area keeps its station", visible("Zum Einschalten"));
        radio.play(Catalog.indexOf(PackStore.get(a).stations, "Flash FM"));
        Harness.pass(800);
        Assert.assertFalse(radio.isPodcast());
        Assert.assertEquals("Flash FM", radio.label());
    }

    private EditText field() {
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof EditText && v.isShown()) return (EditText) v;
        }
        return null;
    }

    /** A line (not the search field) that reads exactly this. */
    private boolean listed(String text) {
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof TextView && !(v instanceof EditText) && v.isShown() && text.contentEquals(((TextView) v).getText())) return true;
        }
        return false;
    }

    private void type(String text) {
        field().setText(text);
        Harness.pass(400);
    }

    @Test
    public void search() throws Exception {
        open();
        tap("Score");
        Library.Station m = missions();
        Schedule.Track piece = m.songs.get(m.songs.size() / 2);
        type(piece.title + " " + piece.artist);
        Assert.assertTrue(visible("Stücke"));
        Assert.assertTrue(listed(piece.title));
        Assert.assertFalse("the groups make room", visible("Offene Welt"));
        ScreenshotTest.shot(a, "69a_score_search", null);
        Assert.assertTrue(tap(piece.title));
        Assert.assertTrue(radio.isScore());
        Assert.assertEquals(piece.title, radio.title);
        Assert.assertEquals(piece.artist, radio.artist);
        seconds(2);
        Assert.assertEquals("the search stays", piece.title + " " + piece.artist, field().getText().toString());
        ScreenshotTest.shot(a, "69b_score_search_playing", null);

        type("qqqzzz");
        Assert.assertTrue(visible("Nichts gefunden"));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue("back clears the search", visible("Offene Welt"));
        Assert.assertEquals("", field().getText().toString());

        // a mission that is found opens; back leads to its group
        type(piece.artist.toLowerCase(java.util.Locale.ROOT) + " ");
        Assert.assertTrue(visible("Missionen und Orte"));
        ScreenshotTest.shot(a, "69c_score_search_mission", null);
        Assert.assertTrue(tap(piece.artist));
        Assert.assertEquals("", field().getText().toString());
        Assert.assertTrue(visible(piece.title));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Alle abspielen"));

        // searched inside a group: the whole score is searched, back returns to the group
        type(piece.title);
        Assert.assertFalse(visible("Alle abspielen"));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Alle abspielen"));

        // very many hits: the list stays a list
        for (String letters : new String[]{"a", "an", "a"}) type(letters);
        Assert.assertTrue(visible("Stücke"));
        int lines = 0;
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof TextView && v.isShown()) lines++;
        }
        Assert.assertTrue("only what is on the screen is built: " + lines, lines < 120);
        type("");
        Assert.assertTrue(visible("Alle abspielen"));
        command(RadioService.ACTION_STOP);
    }

    @Test
    public void inTheCar() {
        Harness.settle();
        List<MediaBrowser.MediaItem> root = radio.children("awc_root");
        Assert.assertEquals("awc_score", root.get(root.size() - 1).getMediaId());
        List<MediaBrowser.MediaItem> groups = radio.children("awc_score");
        Assert.assertEquals(5, groups.size());
        Assert.assertEquals("Offene Welt", groups.get(0).getDescription().getTitle().toString());
        Assert.assertEquals("Musik an Orten", groups.get(4).getDescription().getTitle().toString());
        List<MediaBrowser.MediaItem> subs = radio.children(groups.get(1).getMediaId());
        Assert.assertTrue(subs.size() > 20);
        List<MediaBrowser.MediaItem> pieces = radio.children(subs.get(3).getMediaId());
        Assert.assertTrue(pieces.size() >= 1);
        Assert.assertTrue(pieces.get(0).isPlayable());
        radio.playEpisodeId(pieces.get(0).getMediaId());
        Harness.pass(800);
        Assert.assertEquals(MISSIONS, radio.stationName);
        Assert.assertEquals(pieces.get(0).getDescription().getTitle().toString(), radio.title);
        radio.skip(1);
        Harness.pass(400);
        Assert.assertTrue(radio.isScore());
        Assert.assertTrue(radio.children("scg:score:none").isEmpty());
        Assert.assertTrue(radio.children("scs:broken").isEmpty());
        radio.stopAll();
    }

    @Test
    @Config(qualifiers = "de-rDE-w320dp-h569dp-port-xhdpi")
    public void smallPhone() throws Exception {
        open();
        tap("Score");
        ScreenshotTest.shot(a, "65_small_score", null);
        type("loop");
        ScreenshotTest.shot(a, "65b_small_score_search", null);
        type("");
        tap("Offene Welt");
        tap("Alle abspielen");
        seconds(3);
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        screen.setTranslationY(0);
        ScreenshotTest.shot(a, "66_small_score_player", null);
    }

    @Test
    @Config(qualifiers = "en-rUS-w851dp-h393dp-land-xhdpi")
    public void sideways() throws Exception {
        open();
        radio.playEpisode("score:activity", 0);
        seconds(5);
        tap("Score");
        ScreenshotTest.shot(a, "67_land_score", null);
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        screen.setTranslationY(0);
        ScreenshotTest.shot(a, "68_land_score_player", null);
    }
}
