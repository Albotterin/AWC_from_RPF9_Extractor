package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import java.util.List;

/**
 * Plays a station live, like Spotify plays in the background: a foreground service with a media notification
 * (previous / pause / next / stop, station logo), a MediaSession (lock screen, headset and Bluetooth buttons), audio
 * focus handling (calls, other apps), pause when the headphones are unplugged, a sleep timer, and a programme that is
 * calculated from the UTC clock exactly like on the PC (Schedule, played by Engine).
 */
public class RadioService extends Service {
    static final String ACTION_PLAY = "org.awctool.radio.PLAY";
    static final String ACTION_TOGGLE = "org.awctool.radio.TOGGLE";
    static final String ACTION_STOP = "org.awctool.radio.STOP";
    static final String ACTION_NEXT = "org.awctool.radio.NEXT";
    static final String ACTION_PREV = "org.awctool.radio.PREV";
    static final String ACTION_SLEEP = "org.awctool.radio.SLEEP";
    static final String EXTRA_STATION = "station";
    static final String EXTRA_MINUTES = "minutes";

    static final int STOPPED = 0, PLAYING = 1, PAUSED = 2;

    private static final int NOTIF_ID = 17;
    private static final String CHANNEL = "radio";
    private static final long PAUSE_TIMEOUT_MS = 30L * 60 * 1000;     // a pause that is forgotten ends the service
    private static final long SLEEP_FADE_MS = 8000;
    private static final String PREFS = "radio";
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
    private Pack pack;
    private List<Library.Station> stations;
    private Library.Station station;
    private Engine engine;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private MediaSession session;
    private boolean pausedByFocus, noisyRegistered, timeRegistered;
    private float focusDuck = 1f;

    private final Runnable tickRun = new Runnable() {
        @Override
        public void run() {
            long delay = 250;
            try {
                if (engine != null && state == PLAYING) {
                    delay = engine.tick();
                    if (engine.takeChanged()) onItemChanged();
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
                else if (state == STOPPED) play(getSharedPreferences(PREFS, MODE_PRIVATE).getInt("last", 0));
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
        });
        // flags are deprecated (Android 8+ always handles them) but needed on Android 7
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        AppLog.d("service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        try {
            if (ACTION_PLAY.equals(action)) {
                startForegroundNow();             // must happen within seconds of startForegroundService(), whatever follows
                play(intent.getIntExtra(EXTRA_STATION, 0));
            } else if (ACTION_TOGGLE.equals(action)) {
                toggle();
            } else if (ACTION_NEXT.equals(action)) {
                skip(+1);
            } else if (ACTION_PREV.equals(action)) {
                skip(-1);
            } else if (ACTION_SLEEP.equals(action)) {
                setSleepTimer(intent.getIntExtra(EXTRA_MINUTES, 0));
            } else if (ACTION_STOP.equals(action)) {
                stopAll();
            }
        } catch (RuntimeException e) {
            AppLog.e("command " + action, e);
        }
        if (state == STOPPED && !ACTION_PLAY.equals(action)) stopSelf();
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
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.stop();
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
    void play(int index) {
        pack = PackStore.get(this);
        if (pack == null || pack.stations().isEmpty()) {
            AppLog.d("play: no usable station pack (" + PackStore.error + ")");
            stopAll();
            return;
        }
        stations = pack.stations();
        if (index < 0 || index >= stations.size()) index = 0;
        handler.removeCallbacks(tickRun);
        handler.removeCallbacks(pauseTimeout);
        if (engine != null) engine.stop();
        stationIndex = index;
        station = stations.get(index);
        stationName = station.name;
        title = artist = nextText = "";
        jingle = false;
        itemStart = itemEnd = 0;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("last", index).apply();
        final Pack p = pack;
        engine = new Engine(station.schedule, clock, track -> new PackPlayer(p, "t/" + track.key));
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
        if (stations == null) {
            pack = PackStore.get(this);
            if (pack == null) return;
            stations = pack.stations();
        }
        if (stations.isEmpty()) return;
        int base = stationIndex >= 0 ? stationIndex : getSharedPreferences(PREFS, MODE_PRIVATE).getInt("last", 0);
        play(((base + delta) % stations.size() + stations.size()) % stations.size());
    }

    private void pauseInternal() {
        if (state != PLAYING) return;
        state = PAUSED;
        handler.removeCallbacks(tickRun);
        if (engine != null) engine.stop();
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
        if (station == null || state == PLAYING || engine == null) return;
        handler.removeCallbacks(pauseTimeout);
        state = PLAYING;
        engine.resync();
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
        handler.removeCallbacksAndMessages(null);
        if (engine != null) engine.stop();
        engine = null;
        unregisterReceivers();
        releaseWakeLock();
        abandonFocus();
        sleepAt = 0;
        stationIndex = -1;
        title = artist = nextText = stationName = "";
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
    }

    // ------------------------------------------------------------------------------------------ what is on air
    private Schedule.Item lastItemShown;

    private void onItemChanged() {
        Schedule.Item it = engine.current();
        if (it == null || it == lastItemShown) return;
        lastItemShown = it;
        jingle = it.jingle;
        title = jingle ? getString(R.string.jingle) : it.track.title;
        artist = jingle ? "" : it.track.artist;
        itemStart = it.start;
        itemEnd = it.end;
        Schedule.Item nx = engine.itemAfter(it);
        nextText = nx == null ? "" : nx.jingle ? getString(R.string.jingle)
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
        long pos = itemStart > 0 ? (long) (Math.max(0, System.currentTimeMillis() / 1000.0 - itemStart) * 1000) : 0;
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(ps, pos, state == PLAYING ? 1f : 0f).build());
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.isEmpty() ? stationName : title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist.isEmpty() ? stationName : artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, stationName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, itemEnd > itemStart ? (long) ((itemEnd - itemStart) * 1000) : -1);
        Bitmap logo = logo();
        if (logo != null) {
            md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, logo);
            md.putBitmap(MediaMetadata.METADATA_KEY_ART, logo);
        }
        session.setMetadata(md.build());
    }

    private Bitmap logo() {
        return station != null && pack != null ? Logos.get(pack, station, 256) : null;
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
                .setColor(0xFFFF5A36)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(actionIntent(ACTION_STOP, 9));
        Bitmap logo = logo();
        if (logo != null) b.setLargeIcon(logo);
        b.addAction(action(R.drawable.ic_prev, R.string.act_prev, ACTION_PREV, 1));
        if (state == PAUSED) b.addAction(action(R.drawable.ic_play, R.string.act_play, ACTION_TOGGLE, 2));
        else b.addAction(action(R.drawable.ic_pause, R.string.act_pause, ACTION_TOGGLE, 2));
        b.addAction(action(R.drawable.ic_next, R.string.act_next, ACTION_NEXT, 3));
        b.addAction(action(R.drawable.ic_stop, R.string.act_stop, ACTION_STOP, 4));
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (session != null) style.setMediaSession(session.getSessionToken());
        b.setStyle(style);
        return b.build();
    }

    private void startForegroundNow() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        else startForeground(NOTIF_ID, n);
    }

    private void updateNotification() {
        if (state == STOPPED) return;
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
    }
}
