package org.awctool.radio;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The songs of a pack folder as a music app sees them (Music): what counts as a song, the playlists, the artists, the
 * search and the queues. With the argument "real" the folder holds the game's packs. Run: see android/test/run_tests.py.
 *
 *   java MusicTest <pack folder>
 */
public final class MusicTest {
    private static int checks = 0;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static Catalog load(String folder) {
        List<Catalog.Source> files = new ArrayList<>();
        for (File f : new File(folder).listFiles()) files.add(new Catalog.Source(f.getPath(), f.getName(), f.length(), f.lastModified()));
        return Catalog.load(files, null, s -> {
            FileInputStream in = new FileInputStream(s.id);
            return new Pack(in.getChannel(), in.getFD(), in);
        });
    }

    private static List<String> keys(List<Schedule.Track> list) {
        List<String> out = new ArrayList<>();
        for (Schedule.Track t : list) out.add(t.key);
        return out;
    }

    public static void main(String[] args) throws Exception {
        check(Music.plain("Beyoncé's  \"Déjà-Vu\" $hit").equals("beyonces deja vu shit"), "plain: " + Music.plain("Beyoncé's  \"Déjà-Vu\" $hit"));
        check(Music.credits("A ft. B & C").toString().equals("[A, B & C]"), "credits");
        check(Music.credits("Kool & the Gang").size() == 1, "a band keeps its name");

        Catalog catalog = load(args[0]);
        Music music = catalog.music();
        check(music == catalog.music(), "built once");
        check(!music.songs.isEmpty() && !music.playlists.isEmpty() && !music.artists.isEmpty(), "something to play");
        Set<String> names = new HashSet<>();
        String last = null;
        for (Music.Song s : music.songs) {
            check(Music.isSong(s.track), "only songs: " + s.track.title);
            check(names.add(s.name()), "every song once: " + s.name());
            check(s.from.pack.entry("t/" + s.track.key) != null, "its file is there: " + s.track.title);
        }
        for (Music.Playlist p : music.playlists) {
            check(!p.songs.isEmpty(), "no empty playlist: " + p.name);
            check(!p.cover.talk && (p.cover.rules == null || !p.cover.rules.talkRadio), "no talk radio: " + p.name);
            for (Music.Song s : p.songs) check(Music.isSong(s.track) && p.cover == s.from, "playlist " + p.name);
        }
        for (Music.Artist a : music.artists) {
            check(!a.songs.isEmpty(), "artist " + a.name);
            check(music.artist(a.name) == a && !music.searchArtists(a.name).isEmpty(), "found by name: " + a.name);
        }
        // the search: every song is found by its title and by its artist
        for (Music.Song s : music.songs) {
            if (Music.plain(s.track.title).isEmpty()) continue;
            check(music.search(s.track.title).contains(s), "found by title: " + s.track.title);
            check(music.search(s.track.artist).contains(s), "found by artist: " + s.track.artist);
        }
        check(music.search("").isEmpty() && music.search("qqqzzzxxx").isEmpty(), "nothing for nothing");

        // queues: each holds its list, a shuffled one the same songs in an order that depends only on its name
        String list = Music.LIST + music.playlists.get(0).name;
        Library.Station queue = catalog.onDemand(list);
        check(queue != null && queue.music && queue.songs.size() == music.playlists.get(0).songs.size(), "a playlist's queue");
        check(queue == catalog.onDemand(list), "the same queue again");
        check(queue.packOf(queue.songs.get(0)).entry("t/" + queue.songs.get(0).key) != null, "its first file");
        check(queue.coverOf(0) == music.playlists.get(0).cover, "its picture");
        Library.Station all = catalog.onDemand(Music.ALL);
        check(all.songs.size() == music.songs.size(), "all songs");
        for (int i = 0; i < all.songs.size(); i++) {
            check(all.packOf(all.songs.get(i)).entry("t/" + all.songs.get(i).key) != null, "file " + i + " of all songs");
        }
        String first = all.songs.get(all.songs.size() / 2).key;
        Library.Station mixed = catalog.onDemand(Music.shuffled(Music.ALL, 7, first));
        check(mixed.songs.size() == all.songs.size() && mixed.songs.get(0).key.equals(first), "shuffled, the chosen song first");
        check(new HashSet<>(keys(mixed.songs)).equals(new HashSet<>(keys(all.songs))), "the same songs");
        check(!keys(mixed.songs).equals(keys(all.songs)), "in another order");
        check(keys(new Music(catalog.stations, catalog.playlists).queue(mixed.name).songs).equals(keys(mixed.songs)),
                "the same order when built again");
        check(Music.listOf(mixed.name).equals(Music.ALL) && Music.isShuffled(mixed.name) && !Music.isShuffled(Music.ALL), "names");
        Music.Artist some = music.artists.get(music.artists.size() / 2);
        check(catalog.onDemand(Music.ARTIST + some.name).songs.size() == some.songs.size(), "an artist's queue");
        Music.Song one = music.songs.get(3);
        check(keys(catalog.onDemand(Music.SEARCH + one.track.title).songs).contains(one.track.key), "a search's queue");
        check(catalog.onDemand(Music.LIST + "none") == null && catalog.onDemand(Music.SEARCH + "qqqzzzxxx") == null
                && catalog.onDemand("music:nonsense") == null, "unknown queues");
        System.out.println("Music: ALL OK (" + checks + " checks; " + music.songs.size() + " songs, " + music.playlists.size()
                + " playlists, " + music.artists.size() + " artists)");
    }
}
