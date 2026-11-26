package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.browse.MediaBrowser;
import android.view.View;
import android.view.ViewGroup;
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

/** Songs on demand: playlists, artists and the search, playing from there, shuffle and repeat, the players, the car. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MusicScreenTest {
    private RadioService radio;
    private MainActivity a;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.app().getSharedPreferences(RadioService.PREFS, 0).edit().clear().commit();
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

    private EditText field() {
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof EditText && v.isShown()) return (EditText) v;
        }
        return null;
    }

    private void type(String text) {
        field().setText(text);
        Harness.pass(400);
    }

    private void seconds(int n) {
        for (int i = 0; i < n * 4; i++) Harness.pass(250);
    }

    private List<String> buttons() {
        NotificationManager nm = (NotificationManager) Harness.app().getSystemService(Context.NOTIFICATION_SERVICE);
        Notification n = Shadows.shadowOf(nm).getNotification(17);
        if (n == null) n = Shadows.shadowOf(radio).getLastForegroundNotification();
        List<String> out = new ArrayList<>();
        for (Notification.Action x : n.actions) out.add(x.title.toString());
        return out;
    }

    private Music music() {
        return PackStore.get(Harness.app()).music();
    }

    private void largePlayer() {
        ReflectionHelpers.callInstanceMethod(a, "openPlayer", ReflectionHelpers.ClassParameter.from(boolean.class, true));
        Harness.pass(600);
        EpisodeScreen screen = ReflectionHelpers.getField(a, "episodePlayer");
        Assert.assertEquals(View.VISIBLE, screen.getVisibility());
        screen.setTranslationY(0);
        Harness.pass(300);
    }

    @Test
    public void playlistsAndPlaying() throws Exception {
        open();
        Assert.assertTrue(tap("On-Demand"));
        Assert.assertTrue(visible("Alle Songs"));
        Assert.assertTrue(visible("Interpreten"));
        Assert.assertTrue(visible("Playlisten"));
        Assert.assertNotNull(field());
        Assert.assertFalse("no small player of the radio here", visible("Zum Einschalten"));
        ScreenshotTest.shot(a, "70_music", null);

        Music.Playlist first = music().playlists.get(0);
        Assert.assertTrue(first.name, tap(first.name));
        Assert.assertTrue(visible("Zufall"));
        Assert.assertTrue(visible(first.songs.get(0).track.title));
        ScreenshotTest.shot(a, "71_playlist", null);
        Assert.assertTrue(tap(first.songs.get(1).track.title + ", " + first.songs.get(1).track.artist));
        Assert.assertTrue(radio.isMusic());
        Assert.assertEquals(RadioService.PLAYING, radio.state);
        Assert.assertEquals(Music.LIST + first.name, radio.stationName);
        Assert.assertEquals(first.name, radio.label());
        Assert.assertEquals(first.songs.get(1).track.title, radio.title);
        Assert.assertEquals(first.songs.get(1).track.artist, radio.artist);
        seconds(20);
        Assert.assertTrue(radio.position() > 15 && radio.position() < 25);
        Assert.assertTrue("the small player", visible(first.name + " • " + first.songs.get(1).track.artist));
        Assert.assertFalse(buttons().toString(), buttons().contains("30 Sekunden vor"));
        ScreenshotTest.shot(a, "72_playlist_playing", null);

        largePlayer();
        Assert.assertTrue(visible("Zufall: aus"));
        Assert.assertTrue(visible("Wiederholen: aus"));
        Assert.assertTrue(visible("Zur Liste"));
        ScreenshotTest.shot(a, "73_music_player", null);
        Assert.assertTrue(tap("Nächster Song"));
        Assert.assertEquals(first.songs.get(2).track.title, radio.title);
        String playing = radio.currentKey();
        seconds(5);
        double at = radio.position();
        Assert.assertTrue(tap("Zufall: aus"));
        Assert.assertTrue("shuffled", Music.isShuffled(radio.stationName));
        Assert.assertEquals("the song plays on", playing, radio.currentKey());
        Assert.assertEquals(0, radio.episodeIndex());
        Assert.assertTrue("not from the start again: " + radio.position(), radio.position() >= at - 0.5);
        Assert.assertTrue(visible("Zufall: an"));
        Assert.assertTrue(tap("Nächster Song"));
        Assert.assertEquals(1, radio.episodeIndex());
        Assert.assertTrue(tap("Zufall: an"));
        Assert.assertFalse(Music.isShuffled(radio.stationName));
        Assert.assertEquals(first.songs.get(radio.episodeIndex()).track.title, radio.title);

        // repeat: the last song leads to the first one only when asked for; one song again and again
        org.robolectric.shadows.ShadowMediaPlayer.setMediaInfoProvider(
                source -> new org.robolectric.shadows.ShadowMediaPlayer.MediaInfo(8_000, 0));     // files that end soon
        radio.playEpisode(Music.LIST + first.name, first.songs.size() - 1);
        seconds(2);
        Assert.assertTrue(tap("Wiederholen: aus"));
        Assert.assertEquals(Episodes.REPEAT_ALL, radio.repeatMode());
        seconds(8);
        Assert.assertEquals("round again", 0, radio.episodeIndex());
        Assert.assertTrue(tap("Wiederholen: alle"));
        Assert.assertEquals(Episodes.REPEAT_ONE, radio.repeatMode());
        seconds(10);
        Assert.assertEquals("the same one", 0, radio.episodeIndex());
        Assert.assertTrue(tap("Wiederholen: dieser Song"));
        Assert.assertEquals(Episodes.REPEAT_OFF, radio.repeatMode());

        Assert.assertTrue(tap("Zur Liste"));
        Assert.assertTrue(visible(first.songs.get(0).track.title));
        Assert.assertTrue(visible("Zufall"));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Playlisten"));
        radio.stopAll();
    }

    @Test
    public void searchAndArtists() throws Exception {
        open();
        tap("On-Demand");
        Music.Song song = music().songs.get(music().songs.size() / 3);
        type(song.track.title);
        Assert.assertTrue(visible(song.track.title));
        Assert.assertFalse(visible("Playlisten"));
        ScreenshotTest.shot(a, "74_search", null);
        Assert.assertTrue(tap(song.track.title + ", " + song.track.artist));
        Assert.assertTrue(radio.isMusic());
        Assert.assertTrue(radio.stationName.startsWith(Music.SEARCH));
        Assert.assertEquals(song.track.title, radio.title);
        Assert.assertTrue("the search stays", field().getText().toString().equals(song.track.title));
        type("qqqzzz");
        Assert.assertTrue(visible("Nichts gefunden"));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue("back clears the search", visible("Playlisten"));

        Assert.assertTrue(tap("Interpreten"));
        Music.Artist artist = music().artists.get(0);
        Assert.assertTrue(visible(artist.name));
        ScreenshotTest.shot(a, "75_artists", null);
        Assert.assertTrue(tap(artist.name));
        Assert.assertTrue(visible(artist.songs.get(0).track.title));
        ScreenshotTest.shot(a, "76_artist", null);
        Assert.assertTrue(tap("Alle abspielen"));
        Assert.assertEquals(Music.ARTIST + artist.name, radio.stationName);
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible(artist.name));
        a.onBackPressed();
        Harness.pass(400);
        Assert.assertTrue(visible("Alle Songs"));
        Assert.assertTrue(tap("Alle Songs"));
        Assert.assertTrue(tap("Zufall"));
        Assert.assertTrue(radio.stationName.startsWith(Music.ALL + Music.SHUFFLED));
        Assert.assertEquals(music().songs.size(), ((List<?>) ((Library.Station) ReflectionHelpers.getField(radio, "station")).songs).size());
        // a long queue shows the system only the songs around the one that is on
        radio.playEpisode(radio.stationName, 300);
        Harness.pass(600);
        int from = ReflectionHelpers.getField(radio, "queueFrom"), to = ReflectionHelpers.getField(radio, "queueTo");
        Assert.assertTrue("shown " + from + " to " + to, to - from <= 120 && from <= 300 && to > 300);
        tap("Radio");
        radio.play(Catalog.indexOf(PackStore.get(a).stations, "Flash FM"));
        Harness.pass(800);
        Assert.assertFalse(radio.isMusic());
        Assert.assertEquals("Flash FM", radio.label());
        radio.stopAll();
    }

    @Test
    public void inTheCar() {
        Harness.settle();
        List<MediaBrowser.MediaItem> root = radio.children("awc_root");
        boolean found = false;
        for (MediaBrowser.MediaItem i : root) found |= "awc_songs".equals(i.getMediaId());
        Assert.assertTrue(found);
        List<MediaBrowser.MediaItem> lists = radio.children("awc_songs");
        Assert.assertEquals(music().playlists.size(), lists.size());
        List<MediaBrowser.MediaItem> songs = radio.children(lists.get(1).getMediaId());
        Assert.assertTrue(songs.size() > 3);
        radio.playEpisodeId(songs.get(2).getMediaId());
        Harness.pass(800);
        Assert.assertTrue(radio.isMusic());
        Assert.assertEquals(songs.get(2).getDescription().getTitle().toString(), radio.title);
        radio.skip(1);
        Harness.pass(400);
        Assert.assertEquals(songs.get(3).getDescription().getTitle().toString(), radio.title);
        radio.stopAll();
    }

    @Test
    @Config(qualifiers = "de-rDE-w320dp-h569dp-port-xhdpi")
    public void smallPhone() throws Exception {
        open();
        tap("On-Demand");
        ScreenshotTest.shot(a, "77_small_music", null);
        tap(music().playlists.get(0).name);
        ScreenshotTest.shot(a, "78_small_playlist", null);
        tap("Alle abspielen");
        seconds(3);
        largePlayer();
        ScreenshotTest.shot(a, "79_small_music_player", null);
        radio.stopAll();
    }

    @Test
    @Config(qualifiers = "en-rUS-w851dp-h393dp-land-xhdpi")
    public void sideways() throws Exception {
        open();
        radio.playEpisode(Music.ALL, 0);
        seconds(5);
        tap("On-Demand");
        ScreenshotTest.shot(a, "80_land_music", null);
        largePlayer();
        ScreenshotTest.shot(a, "81_land_music_player", null);
        radio.stopAll();
    }
}
