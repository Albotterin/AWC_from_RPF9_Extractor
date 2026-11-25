package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaDescription;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.browse.MediaBrowser;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.service.media.MediaBrowserService;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a station live, like Spotify plays in the background: a foreground service with a media notification
 * (previous / pause / next / stop, station logo), a MediaSession (lock screen, headset and Bluetooth buttons), audio
 * focus handling (calls, other apps), pause when the headphones are unplugged, a sleep timer, and a programme that is
 * calculated from the UTC clock exactly like on the PC (Schedule, played by Engine).
 *
 * A podcast is played by the same service, but on demand (Episodes): it pauses where it is, can be wound back and forth
 * and goes on with the next episode.
 */
public class RadioService extends MediaBrowserService implements PackStore.Listener {
    static final String ACTION_PLAY = "org.awctool.radio.PLAY";
    static final String ACTION_TOGGLE = "org.awctool.radio.TOGGLE";
    static final String ACTION_STOP = "org.awctool.radio.STOP";
    static final String ACTION_NEXT = "org.awctool.radio.NEXT";
    static final String ACTION_PREV = "org.awctool.radio.PREV";
    static final String ACTION_SLEEP = "org.awctool.radio.SLEEP";
    static final String ACTION_ADS = "org.awctool.radio.ADS";            // the advert switch changed
    static final String ACTION_EPISODE = "org.awctool.radio.EPISODE";    // play an episode of a podcast
    static final String ACTION_SEEK = "org.awctool.radio.SEEK";          // podcast: go to EXTRA_SECONDS
    static final String ACTION_JUMP = "org.awctool.radio.JUMP";          // podcast: EXTRA_SECONDS back or forth
    static final String EXTRA_EPISODE = "episode", EXTRA_SECONDS = "seconds";
    static final int BACK_SECONDS = 15, FORWARD_SECONDS = 30;
    static final String EXTRA_STATION = "station";        // the station's name
    static final String EXTRA_MINUTES = "minutes";
    static final String EXTRA_QUERY = "query";            // a spoken station name from the voice assistant

    static final int STOPPED = 0, PLAYING = 1, PAUSED = 2;

    private static final int NOTIF_ID = 17;
    private static final String CHANNEL = "radio";
    private static final long PAUSE_TIMEOUT_MS = 30L * 60 * 1000;     // a pause that is forgotten ends the service
    private static final long SLEEP_FADE_MS = 8000;
    static final String PREFS = "radio", PREF_LAST = "lastName", PREF_ADS = "ads";
    private static final long WAKE_LOCK_MS = 30L * 60 * 1000;

    public final class LocalBinder extends Binder {
        RadioService get() {
            return RadioService.this;
        }
    }

    // state that the UI reads (all on the main thread)
    int state = STOPPED;
    int stationIndex = -1;
    String stationName = "", title = "", artist = "", nextText = "";
    boolean jingle;
    double itemStart, itemEnd;
    long sleepAt;                                // UNIX millis when the sleep timer stops the radio, 0 = off

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Library.Station> stations;
    private Library.Station station;
    private Engine engine;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private boolean pausedByFocus, noisyRegistered, timeRegistered, started;
    private float focusDuck = 1f;
    private Episodes episodes;                    // set while a podcast is on; `station` then is the podcast
    private boolean episodeLoading;

    private final Runnable tickRun = new Runnable() {
        @Override
        public void run() {
            long delay = 250;
            try {
                if (episodes != null && state == PLAYING) {
                    tickEpisodes();
                } else if (engine != null && state == PLAYING) {
                    delay = engine.tick();
                    if (engine.takeChanged()) onItemChanged();
                    updatePart();
                    checkSleepTimer();
                }
            } catch (RuntimeException e) {
                AppLog.e("tick", e);                  // keep the radio going whatever happens in one tick
            }
            if (state == PLAYING) handler.postDelayed(this, delay);
        }
    };

    private final Runnable pauseTimeout = () -> {
        AppLog.d("paused for too long, stopping");
        stopAll();
    };

    private final Engine.Clock clock = () -> System.currentTimeMillis() / 1000.0;

    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(i.getAction())) {
                AppLog.d("headphones unplugged / audio route lost: pause");
                pausedByFocus = false;
                pauseInternal();
            }
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            AppLog.d("phone clock changed: resync");
            if (engine != null && state == PLAYING) engine.resync();
        }
    };

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        AppLog.d("audio focus change " + change);
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                pausedByFocus = false;
                pauseInternal();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                if (state == PLAYING) {
                    pausedByFocus = true;
                    pauseInternal();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                focusDuck = 0.25f;
                applyDuck();
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                focusDuck = 1f;
                applyDuck();
                if (pausedByFocus) {
                    pausedByFocus = false;
                    resumeLive();
                }
                break;
            default:
                break;
        }
    };

    // ------------------------------------------------------------------------------------------ service
    @Override
    public IBinder onBind(Intent intent) {
        // Android Auto, the Assistant and the system media controls bind with the MediaBrowserService action; our own
        // screen binds without one and gets the local binder
        if (intent != null && MediaBrowserService.SERVICE_INTERFACE.equals(intent.getAction())) return super.onBind(intent);
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        session = new MediaSession(this, "AWCRadio");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                if (state == PAUSED) resumeLive();
                else if (state == STOPPED) play(lastStation());
            }

            @Override
            public void onPause() {
                pausedByFocus = false;
                pauseInternal();
            }

            @Override
            public void onStop() {
                stopAll();
            }

            @Override
            public void onSkipToNext() {
                skip(+1);
            }

            @Override
            public void onSkipToPrevious() {
                skip(-1);
            }

            @Override
            public void onSeekTo(long pos) {
                seek(pos / 1000.0);
            }

            @Override
            public void onRewind() {
                jump(-BACK_SECONDS);
            }

            @Override
            public void onFastForward() {
                jump(FORWARD_SECONDS);
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                if (ACTION_JUMP.equals(action) && extras != null) jump(extras.getInt(EXTRA_SECONDS));
            }

            // ---- the car (Android Auto), the Assistant and other media controllers
            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                if (mediaId != null && mediaId.startsWith(EPISODE_ID)) {
                    playEpisodeId(mediaId);
                    return;
                }
                int idx = stationOfMediaId(mediaId);
                AppLog.d("play from media id " + mediaId + " -> " + idx);
                if (idx >= 0) startPlaying(idx);
                else if (NOPACK.equals(mediaId)) showError(getString(R.string.auto_no_pack));
            }

            @Override
            public void onSkipToQueueItem(long id) {
                if (id < 0) return;
                if (episodes != null) playEpisode(stationName, (int) id);
                else startPlaying((int) id);
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                // "Hey Google, play Radio On-U on AWC Radio": a station name, or nothing (then the last station)
                int idx = stationForQuery(query);
                AppLog.d("play from search '" + query + "' -> " + idx);
                startPlaying(idx);
            }
        });
        // flags are deprecated (Android 8+ always handles them) but needed on Android 7
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        setSessionToken(session.getSessionToken());                 // lets browsers (Android Auto) connect to the session
        PackStore.addListener(this);
        AppLog.d("service created");
    }

    // ------------------------------------------------------------------------------------------ browsing (Android Auto)
    private static final String ROOT = "awc_root", RECENT = "awc_recent", NOPACK = "awc_nopack", STATION_ID = "st:";
    private static final String PODCASTS = "awc_podcasts", PODCAST_ID = "pod:", EPISODE_ID = "ep:";
    // Android Auto / Automotive hints for how to draw the tree (the strings are part of the public media-app contract)
    private static final String STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED";
    private static final String STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT";
    private static final String STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT";
    private static final int STYLE_LIST = 1, STYLE_GRID = 2;

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        // The tree holds nothing but station names and logos, and the radio is a live programme anyone may tune in, so
        // every media client (Android Auto, the Assistant, the system media controls, head units) may browse it.
        Bundle extras = new Bundle();
        extras.putBoolean(STYLE_SUPPORTED, true);
        extras.putInt(STYLE_PLAYABLE, STYLE_GRID);
        extras.putInt(STYLE_BROWSABLE, STYLE_LIST);
        if (rootHints != null && rootHints.getBoolean(BrowserRoot.EXTRA_RECENT, false)) {
            return new BrowserRoot(RECENT, extras);                  // "resume what I heard last"
        }
        return new BrowserRoot(ROOT, extras);
    }

    @Override
    public void onLoadChildren(final String parentId, final Result<List<MediaBrowser.MediaItem>> result) {
        result.detach();                                              // decoding the logos takes a moment: not on the main thread
        final Context ctx = getApplicationContext();
        PackStore.refresh(ctx, null);                                 // new files show up through onStationsChanged
        new Thread(() -> result.sendResult(children(parentId)), "awc-browse").start();
    }

    /** What the car shows below an entry: stations and "Podcasts" at the top, then the podcasts and their episodes. */
    List<MediaBrowser.MediaItem> children(String parentId) {
        Context ctx = getApplicationContext();
        List<MediaBrowser.MediaItem> out = new ArrayList<>();
        try {
            Catalog catalog = PackStore.get(ctx);
            List<Library.Station> list = catalog.stations;
            if (PODCASTS.equals(parentId)) {
                for (Library.Station p : catalog.podcasts) {
                    out.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(PODCAST_ID + p.name)
                            .setTitle(p.name).setSubtitle(getString(R.string.episodes_count, p.songs.size()))
                            .setIconBitmap(Logos.get(p, 192)).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
                }
            } else if (parentId.startsWith(PODCAST_ID)) {
                int at = Catalog.indexOf(catalog.podcasts, parentId.substring(PODCAST_ID.length()));
                Library.Station p = at < 0 ? null : catalog.podcasts.get(at);
                for (int i = 0; p != null && i < p.songs.size(); i++) {
                    out.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(EPISODE_ID + p.name + "#" + i)
                            .setTitle(getString(R.string.episode_n, i + 1)).setSubtitle(Ui.fmt(p.songs.get(i).sec))
                            .setIconBitmap(Logos.get(p, 192)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
                }
            } else if (list.isEmpty() && catalog.podcasts.isEmpty()) {
                out.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(NOPACK)
                        .setTitle(getString(R.string.auto_no_pack)).build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            } else {
                int only = RECENT.equals(parentId) ? lastStation(list) : -1;
                for (int i = 0; i < list.size(); i++) {
                    if (RECENT.equals(parentId) && i != only) continue;
                    Library.Station st = list.get(i);
                    out.add(new MediaBrowser.MediaItem(new MediaDescription.Builder()
                            .setMediaId(STATION_ID + st.name)
                            .setTitle(st.name)
                            .setSubtitle(getString(st.talk ? R.string.shows_count : R.string.songs_count, st.songs.size()))
                            .setIconBitmap(Logos.get(st, 192))
                            .build(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
                }
                if (ROOT.equals(parentId) && !catalog.podcasts.isEmpty()) {
                    out.add(new MediaBrowser.MediaItem(new MediaDescription.Builder().setMediaId(PODCASTS)
                            .setTitle(getString(R.string.tab_podcasts)).build(), MediaBrowser.MediaItem.FLAG_BROWSABLE));
                }
            }
        } catch (RuntimeException e) {
            AppLog.e("browse " + parentId, e);
        }
        return out;
    }

    /** "ep:<podcast>#<number>" as the car sends it. */
    void playEpisodeId(String mediaId) {
        int hash = mediaId.lastIndexOf('#');
        if (hash < EPISODE_ID.length()) return;
        try {
            playEpisode(mediaId.substring(EPISODE_ID.length(), hash), Integer.parseInt(mediaId.substring(hash + 1)));
        } catch (NumberFormatException e) {
            AppLog.d("bad media id " + mediaId);
        }
    }

    private int stationOfMediaId(String id) {
        if (id == null || !id.startsWith(STATION_ID)) return -1;
        return Catalog.indexOf(PackStore.get(this).stations, id.substring(STATION_ID.length()));
    }

    /** The station heard last (by name: the list changes when packs are added), else the first one. */
    private int lastStation(List<Library.Station> list) {
        return Math.max(0, Catalog.indexOf(list, getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_LAST, "")));
    }

    private int lastStation() {
        return lastStation(PackStore.get(this).stations);
    }

    private int stationForQuery(String query) {
        int idx = findStation(query);
        return idx >= 0 ? idx : lastStation();
    }

    /** The station whose name best matches a spoken query, or -1. */
    private int findStation(String query) {
        if (query == null || query.trim().isEmpty()) return -1;
        String q = norm(query);
        int best = -1, bestLen = Integer.MAX_VALUE;
        List<Library.Station> list = PackStore.get(this).stations;
        for (int i = 0; i < list.size(); i++) {
            String n = norm(list.get(i).name);
            if (n.equals(q)) return i;
            if ((n.contains(q) || q.contains(n)) && n.length() < bestLen) {
                best = i;
                bestLen = n.length();
            }
        }
        return best;
    }

    private static String norm(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private void startPlaying(int index) {
        play(index);
    }

    /** Tells the controllers (the car screen) why nothing plays, e.g. no station pack chosen yet. */
    private void showError(String message) {
        if (session == null) return;
        session.setActive(true);
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR, 0, 0f)
                .setErrorMessage(message).build());
    }

    /** Fills the "queue" of the session with the stations: the car shows it as the station list. */
    private void publishQueue() {
        if (session == null) return;
        List<MediaSession.QueueItem> q = new ArrayList<>();
        if (episodes != null) {
            for (int i = 0; i < episodes.count(); i++) {
                q.add(new MediaSession.QueueItem(new MediaDescription.Builder().setMediaId(EPISODE_ID + stationName + "#" + i)
                        .setTitle(getString(R.string.episode_n, i + 1)).build(), i));
            }
            session.setQueue(q);
            session.setQueueTitle(stationName);
            return;
        }
        if (stations == null) return;
        for (int i = 0; i < stations.size(); i++) {
            q.add(new MediaSession.QueueItem(new MediaDescription.Builder().setMediaId(STATION_ID + stations.get(i).name)
                    .setTitle(stations.get(i).name).build(), i));
        }
        session.setQueue(q);
        session.setQueueTitle(getString(R.string.stations));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        started = true;
        try {
            if (ACTION_PLAY.equals(action)) {
                startForegroundNow();             // must happen within seconds of startForegroundService(), whatever follows
                play(intent.hasExtra(EXTRA_QUERY) ? stationForQuery(intent.getStringExtra(EXTRA_QUERY))
                        : Catalog.indexOf(PackStore.get(this).stations, intent.getStringExtra(EXTRA_STATION)));
            } else if (ACTION_EPISODE.equals(action)) {
                startForegroundNow();
                playEpisode(intent.getStringExtra(EXTRA_STATION), intent.getIntExtra(EXTRA_EPISODE, 0));
            } else if (ACTION_SEEK.equals(action)) {
                seek(intent.getDoubleExtra(EXTRA_SECONDS, 0));
            } else if (ACTION_JUMP.equals(action)) {
                jump(intent.getIntExtra(EXTRA_SECONDS, 0));
            } else if (ACTION_TOGGLE.equals(action)) {
                toggle();
            } else if (ACTION_NEXT.equals(action)) {
                skip(+1);
            } else if (ACTION_PREV.equals(action)) {
                skip(-1);
            } else if (ACTION_ADS.equals(action)) {
                applyAdverts();
            } else if (ACTION_SLEEP.equals(action)) {
                setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0));
            } else if (ACTION_STOP.equals(action)) {
                stopAll();
            }
        } catch (RuntimeException e) {
            AppLog.e("command " + action, e);
        }
        if (state == STOPPED && !ACTION_PLAY.equals(action) && !ACTION_EPISODE.equals(action)) {
            started = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // swiping the app away keeps the music going (like Spotify); a paused radio is not worth keeping alive
        if (state != PLAYING) stopAll();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        AppLog.d("service destroyed");
        PackStore.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.stop();
        closePodcast();
        unregisterReceivers();
        releaseWakeLock();
        abandonFocus();
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------ control
    /** A request from a controller (the car) only binds to the service; it must also be started to keep running. */
    private void ensureStarted() {
        if (started) return;
        try {
            startService(new Intent(this, RadioService.class));
            started = true;
        } catch (RuntimeException e) {
            AppLog.e("startService", e);                              // still bound by the controller: plays on regardless
        }
    }

    void play(int index) {
        ensureStarted();
        List<Library.Station> list = PackStore.get(this).stations;
        if (list.isEmpty()) {
            AppLog.d("play: no station pack (" + PackStore.error + ")");
            stopAll();
            showError(getString(R.string.auto_no_pack));                // the car screen says what to do
            return;
        }
        boolean newList = stations != list;
        stations = list;
        if (index < 0 || index >= stations.size()) index = 0;
        handler.removeCallbacks(tickRun);
        handler.removeCallbacks(pauseTimeout);
        if (engine != null) engine.stop();
        closePodcast();
        stationIndex = index;
        station = stations.get(index);
        stationName = station.name;
        title = artist = nextText = "";
        jingle = false;
        itemStart = itemEnd = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_LAST, stationName).apply();
        engine = newEngine(station);
        if (newList) publishQueue();
        state = PLAYING;
        pausedByFocus = false;
        applyDuck();
        session.setActive(true);
        requestFocus();
        acquireWakeLock();
        registerReceivers();
        startForegroundNow();
        updateSession();
        handler.post(tickRun);
        AppLog.d("play station " + index + ": " + stationName);
    }

    private Engine newEngine(Library.Station st) {
        final Pack p = st.pack;
        return new Engine(st.programme(adverts(this)), clock, track -> new PackPlayer(p, "t/" + track.key, AudioAttributes.USAGE_MEDIA));
    }

    /** Whether the listener wants advert breaks (off unless switched on). */
    static boolean adverts(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ADS, false);
    }

    /** The switch changed: the station goes on with its other programme. */
    private void applyAdverts() {
        if (station == null || state == STOPPED || episodes != null) return;
        AppLog.d("adverts " + (adverts(this) ? "on" : "off"));
        if (state == PLAYING) {
            play(stationIndex);
        } else {
            if (engine != null) engine.stop();
            engine = newEngine(station);
            lastItemShown = null;
        }
    }

    // ------------------------------------------------------------------------------------------ podcasts
    boolean isPodcast() {
        return episodes != null;
    }

    int episodeIndex() {
        return episodes == null ? -1 : episodes.index();
    }

    /** Seconds into the episode, and how long it is. */
    double position() {
        return episodes == null ? 0 : episodes.position();
    }

    double duration() {
        return episodes == null ? 0 : episodes.duration();
    }

    private Episodes newEpisodes(Library.Station p) {
        final Pack pack = p.pack;
        return new Episodes(p.songs, track -> new PackPlayer(pack, "t/" + track.key, AudioAttributes.USAGE_MEDIA), new Heard(this));
    }

    /** Play an episode of a podcast, from where the listener left it. */
    void playEpisode(String name, int index) {
        ensureStarted();
        List<Library.Station> list = PackStore.get(this).podcasts;
        int at = Catalog.indexOf(list, name);
        if (at < 0) {
            AppLog.d("no pack for the podcast " + name);
            if (state == STOPPED) stopAll();
            return;
        }
        Library.Station p = list.get(at);
        handler.removeCallbacks(tickRun);
        handler.removeCallbacks(pauseTimeout);
        if (engine != null) engine.stop();
        engine = null;
        stations = null;
        stationIndex = -1;
        index = Math.max(0, Math.min(p.songs.size() - 1, index));
        if (episodes == null || station != p) {
            closePodcast();
            episodes = newEpisodes(p);
            episodes.open(index, true);
        } else if (episodes.index() != index) {
            episodes.open(index, true);
        } else {
            episodes.play();
        }
        station = p;
        stationName = p.name;
        jingle = false;
        itemStart = itemEnd = 0;
        state = PLAYING;
        pausedByFocus = false;
        applyDuck();
        session.setActive(true);
        requestFocus();
        acquireWakeLock();
        registerReceivers();
        publishQueue();
        onEpisodeChanged();
        startForegroundNow();
        handler.post(tickRun);
        AppLog.d("podcast " + name + ", episode " + (index + 1));
    }

    private void onEpisodeChanged() {
        title = getString(R.string.episode_n, episodes.index() + 1);
        artist = "";
        nextText = episodes.index() + 1 < episodes.count() ? getString(R.string.episode_n, episodes.index() + 2) : "";
        episodeLoading = episodes.isLoading();
        new Heard(this).setLast(stationName, episodes.index());
        acquireWakeLock();
        updateSession();
        updateNotification();
    }

    private void tickEpisodes() {
        boolean changed = episodes.tick();
        if (episodes.hasEnded()) {
            AppLog.d("podcast: the last episode is over");
            stopAll();
            return;
        }
        if (changed) {
            onEpisodeChanged();
        } else if (episodes.hasFailed()) {
            AppLog.d("podcast: episode cannot be played");
            pauseInternal();
            title = getString(R.string.episode_error);
            updateSession();
            updateNotification();
            return;
        } else if (episodeLoading && !episodes.isLoading()) {
            episodeLoading = false;
            updateSession();                          // the position of the media controls starts to run
        }
        checkSleepTimer();
    }

    void seek(double seconds) {
        if (episodes == null) return;
        episodes.seekTo(seconds);
        updateSession();
    }

    void jump(int seconds) {
        if (episodes == null) return;
        episodes.jump(seconds);
        updateSession();
    }

    private void closePodcast() {
        if (episodes == null) return;
        episodes.release();                          // notes the position
        episodes = null;
        station = null;
    }

    /** Packs were added, replaced or removed: keep playing the same station if it is still there. */
    @Override
    public void onStationsChanged() {
        if (state != STOPPED && episodes != null) {
            List<Library.Station> list = PackStore.get(this).podcasts;
            int at = Catalog.indexOf(list, stationName);
            if (at < 0) {
                AppLog.d("the pack of " + stationName + " is gone: stop");
                stopAll();
            } else if (list.get(at) != station) {                    // a new pack: go on at the same place
                int episode = episodes.index();
                episodes.release();
                station = list.get(at);
                episodes = newEpisodes(station);
                episodes.open(episode, state == PLAYING);
                publishQueue();
                onEpisodeChanged();
            }
        } else if (state != STOPPED) {
            List<Library.Station> list = PackStore.get(this).stations;
            int idx = Catalog.indexOf(list, stationName);
            if (idx < 0) {
                AppLog.d("the pack of " + stationName + " is gone: stop");
                stopAll();
            } else if (list.get(idx) != station && state == PLAYING) {
                AppLog.d("new pack for " + stationName);
                play(idx);                                           // tune in again, with the new programme
            } else {
                if (list.get(idx) != station) {                      // paused: take the new pack for the next start
                    station = list.get(idx);
                    if (engine != null) engine.stop();
                    engine = newEngine(station);
                }
                stations = list;
                stationIndex = idx;
                publishQueue();
                updateSession();
                updateNotification();
            }
        } else {
            stations = null;
        }
        notifyChildrenChanged(ROOT);                                 // the car reads its station list again
        notifyChildrenChanged(PODCASTS);
    }

    void toggle() {
        if (state == PLAYING) {
            pausedByFocus = false;
            pauseInternal();
        } else if (state == PAUSED) {
            resumeLive();
        }
    }

    /** Previous (-1) / next (+1) station, wrapping around. */
    void skip(int delta) {
        if (episodes != null) {                           // a podcast: the next or previous episode
            boolean changed = delta > 0 ? episodes.next() : episodes.previous();
            if (changed) onEpisodeChanged();
            else updateSession();
            return;
        }
        if (stations == null) stations = PackStore.get(this).stations;
        if (stations.isEmpty()) return;
        int base = stationIndex >= 0 ? stationIndex : lastStation(stations);
        play(((base + delta) % stations.size() + stations.size()) % stations.size());
    }

    private void pauseInternal() {
        if (state != PLAYING) return;
        state = PAUSED;
        handler.removeCallbacks(tickRun);
        if (engine != null) engine.stop();
        if (episodes != null) episodes.pause();
        unregisterNoisy();
        releaseWakeLock();
        lastItemShown = null;
        updateSession();
        updateNotification();
        handler.postDelayed(pauseTimeout, PAUSE_TIMEOUT_MS);
        AppLog.d("paused");
    }

    /** A radio keeps running while you are away: resume where the station is by now. */
    private void resumeLive() {
        if (station == null || state == PLAYING || (engine == null && episodes == null)) return;
        handler.removeCallbacks(pauseTimeout);
        state = PLAYING;
        if (episodes != null) {
            boolean again = episodes.hasFailed();
            episodes.play();
            if (again) onEpisodeChanged();
        } else {
            engine.resync();
        }
        requestFocus();
        acquireWakeLock();
        registerReceivers();
        startForegroundNow();
        updateSession();
        handler.removeCallbacks(tickRun);
        handler.post(tickRun);
        AppLog.d("resumed live");
    }

    void stopAll() {
        AppLog.d("stop");
        state = STOPPED;
        started = false;
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.stop();
        engine = null;
        closePodcast();
        unregisterReceivers();
        releaseWakeLock();
        abandonFocus();
        sleepAt = 0;
        stationIndex = -1;
        title = artist = nextText = stationName = "";
        stations = null;
        if (session != null) {
            session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
            session.setActive(false);
        }
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------------------------------------ sleep timer
    void setSleepTimer(int minutes) {
        sleepAt = minutes > 0 ? System.currentTimeMillis() + minutes * 60_000L : 0;
        AppLog.d(minutes > 0 ? "sleep timer set: " + minutes + " min" : "sleep timer off");
        applyDuck();
    }

    private void checkSleepTimer() {
        if (sleepAt == 0) return;
        long left = sleepAt - System.currentTimeMillis();
        if (left <= 0) {
            AppLog.d("sleep timer: stopping");
            stopAll();
        } else if (left < SLEEP_FADE_MS) {
            applyDuck();                                  // fade out over the last seconds
        }
    }

    private void applyDuck() {
        float sleep = 1f;
        if (sleepAt > 0) {
            long left = sleepAt - System.currentTimeMillis();
            if (left < SLEEP_FADE_MS) sleep = Math.max(0f, left / (float) SLEEP_FADE_MS);
        }
        if (engine != null) engine.setDuck(focusDuck * sleep);
        if (episodes != null) episodes.setVolume(focusDuck * sleep);
    }

    // ------------------------------------------------------------------------------------------ what is on air
    private Schedule.Item lastItemShown;
    private int partShown = -2;

    /** A long mix shows the title of the part that is playing now (from the markers in the pack). */
    private void updatePart() {
        Schedule.Item it = engine.current();
        if (it == null || it.jingle || it.track.partStart == null || it != lastItemShown) return;
        int k = it.track.partAt(System.currentTimeMillis() / 1000.0 - it.start);
        if (k == partShown) return;
        partShown = k;
        boolean named = k >= 0 && !it.track.partTitle[k].isEmpty();
        title = named ? it.track.partTitle[k] : it.track.title;
        artist = named ? it.track.partArtist[k] : it.track.artist;
        updateSession();
        updateNotification();
    }

    static String advertTitle(Context c, Schedule.Track t) {
        return t.title.isEmpty() ? c.getString(R.string.advert) : t.title;
    }

    private void onItemChanged() {
        Schedule.Item it = engine.current();
        if (it == null || it == lastItemShown) return;
        lastItemShown = it;
        partShown = -2;
        jingle = it.jingle;
        title = jingle ? getString(R.string.jingle) : it.ad ? advertTitle(this, it.track) : it.track.title;
        artist = jingle ? "" : it.ad ? (it.track.title.isEmpty() ? "" : getString(R.string.advert)) : it.track.artist;
        itemStart = it.start;
        itemEnd = it.end;
        Schedule.Item nx = engine.itemAfter(it);
        nextText = nx == null ? "" : nx.jingle ? getString(R.string.jingle) : nx.ad ? getString(R.string.advert)
                : (nx.track.artist.isEmpty() ? nx.track.title : nx.track.artist + " - " + nx.track.title);
        AppLog.d("on air: " + (jingle ? "jingle" : artist + " - " + title) + " (" + it.track.key + ")"
                + (engine.failures() > 0 ? ", player failures so far: " + engine.failures() : ""));
        acquireWakeLock();
        updateSession();
        updateNotification();
    }

    private void updateSession() {
        if (session == null) return;
        int ps = state == PLAYING ? PlaybackState.STATE_PLAYING : state == PAUSED ? PlaybackState.STATE_PAUSED : PlaybackState.STATE_STOPPED;
        if (episodes != null) {
            updatePodcastSession(ps);
            return;
        }
        long pos = itemStart > 0 ? (long) (Math.max(0, System.currentTimeMillis() / 1000.0 - itemStart) * 1000) : 0;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH
                | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;
        PlaybackState.Builder pb = new PlaybackState.Builder().setActions(actions)
                .setState(ps, pos, state == PLAYING ? 1f : 0f);
        if (stationIndex >= 0) pb.setActiveQueueItemId(stationIndex);
        session.setPlaybackState(pb.build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? stationName : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.isEmpty() ? stationName : artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, stationName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title.isEmpty() ? stationName : title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist.isEmpty() ? stationName : artist + " • " + stationName)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, STATION_ID + stationName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, itemEnd > itemStart ? (long) ((itemEnd - itemStart) * 1000) : -1);
        Bitmap logo = logo();
        if (logo != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logo);
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, logo);
        }
        session.setMetadata(md.build());
    }

    /** The media controls of a podcast: a position that can be dragged, back and forward, the episodes as queue. */
    private void updatePodcastSession(int ps) {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP
                | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_PLAY_FROM_SEARCH
                | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_REWIND
                | PlaybackState.ACTION_FAST_FORWARD;
        PlaybackState.Builder pb = new PlaybackState.Builder().setActions(actions)
                .setState(ps, (long) (episodes.position() * 1000), ps == PlaybackState.STATE_PLAYING && !episodes.isLoading() ? 1f : 0f)
                .setActiveQueueItemId(episodes.index())
                .addCustomAction(jumpAction(-BACK_SECONDS, R.string.act_rewind, R.drawable.ic_rewind))
                .addCustomAction(jumpAction(FORWARD_SECONDS, R.string.act_forward, R.drawable.ic_forward));
        session.setPlaybackState(pb.build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, stationName)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, stationName)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, stationName)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, EPISODE_ID + stationName + "#" + episodes.index())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, (long) (episodes.duration() * 1000));
        Bitmap logo = logo();
        if (logo != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logo);
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, logo);
        }
        session.setMetadata(md.build());
    }

    private PlaybackState.CustomAction jumpAction(int seconds, int label, int icon) {
        Bundle b = new Bundle();
        b.putInt(EXTRA_SECONDS, seconds);
        return new PlaybackState.CustomAction.Builder(ACTION_JUMP, getString(label), icon).setExtras(b).build();
    }

    private Bitmap logo() {
        return station != null ? Logos.get(station, 256) : null;
    }

    // ------------------------------------------------------------------------------------------ receivers, focus, wake lock
    private void registerReceivers() {
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));
            noisyRegistered = true;
        }
        if (!timeRegistered) {
            registerReceiver(timeReceiver, new IntentFilter(Intent.ACTION_TIME_CHANGED));
            timeRegistered = true;
        }
    }

    private void unregisterNoisy() {
        if (noisyRegistered) {
            try {
                unregisterReceiver(noisyReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            noisyRegistered = false;
        }
    }

    private void unregisterReceivers() {
        unregisterNoisy();
        if (timeRegistered) {
            try {
                unregisterReceiver(timeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            timeRegistered = false;
        }
    }

    private void requestFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setOnAudioFocusChangeListener(focusListener).build();
            }
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonFocus() {
        if (audio == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "awcradio:play");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(WAKE_LOCK_MS);          // with a timeout: refreshed at every new song, released by the OS if we die
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    // ------------------------------------------------------------------------------------------ notification
    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action action(int icon, int label, String action, int code) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), actionIntent(action, code)).build();
    }

    private Notification.Action jumpButton(int icon, int label, int seconds, int code) {
        Intent i = new Intent(this, RadioService.class).setAction(ACTION_JUMP).putExtra(EXTRA_SECONDS, seconds);
        PendingIntent p = PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), p).build();
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        String head = title.isEmpty() ? (stationName.isEmpty() ? getString(R.string.app_name) : stationName) : title;
        String text = state == PAUSED ? getString(R.string.notif_paused, stationName)
                : stationName + (artist.isEmpty() ? "" : " • " + artist);
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(head)
                .setContentText(text)
                .setOngoing(state == PLAYING)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Ui.SUN)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(actionIntent(ACTION_STOP, 9));
        Bitmap logo = logo();
        if (logo != null) b.setLargeIcon(logo);
        boolean pod = episodes != null;
        if (pod) b.addAction(jumpButton(R.drawable.ic_rewind, R.string.act_rewind, -BACK_SECONDS, 5));
        else b.addAction(action(R.drawable.ic_prev, R.string.act_prev, ACTION_PREV, 1));
        if (state == PAUSED) b.addAction(action(R.drawable.ic_play, pod ? R.string.act_resume : R.string.act_play, ACTION_TOGGLE, 2));
        else b.addAction(action(R.drawable.ic_pause, R.string.act_pause, ACTION_TOGGLE, 2));
        if (pod) b.addAction(jumpButton(R.drawable.ic_forward, R.string.act_forward, FORWARD_SECONDS, 6));
        else b.addAction(action(R.drawable.ic_next, R.string.act_next, ACTION_NEXT, 3));
        b.addAction(action(R.drawable.ic_stop, R.string.act_stop, ACTION_STOP, 4));
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private void startForegroundNow() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
        } catch (RuntimeException e) {
            // Android 12+ may refuse a foreground start that was triggered from the background; the controller that
            // is bound (the car) keeps the service alive, so the radio plays on anyway
            AppLog.e("startForeground", e);
        }
    }

    private void updateNotification() {
        if (state == STOPPED) return;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
    }
}
