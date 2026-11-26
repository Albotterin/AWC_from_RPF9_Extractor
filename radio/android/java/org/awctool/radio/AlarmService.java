package org.awctool.radio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Rings an alarm: the chosen station, live, on the phone's alarm volume. An alarm must never stay silent, so whenever
 * the radio cannot be heard (no pack, phone not unlocked yet, a player error) the phone's own alarm tone takes over,
 * and if even that fails, beeps. Ends by itself after half an hour.
 */
public class AlarmService extends Service {
    static final String ACTION_RING = "org.awctool.radio.ALARM_RING";
    static final String ACTION_SNOOZE = "org.awctool.radio.ALARM_SNOOZE";
    static final String ACTION_DISMISS = "org.awctool.radio.ALARM_DISMISS";
    static final String ACTION_KEEP = "org.awctool.radio.ALARM_KEEP";           // stop ringing, go on listening
    static final String EXTRA_ALARM = "json", EXTRA_TEST = "test";

    static final long RADIO_TIMEOUT_MS = 10_000;          // silence for this long means: use the alarm tone
    static final long RING_LIMIT_MS = 30L * 60 * 1000;
    private static final long TEST_LIMIT_MS = 60_000;
    private static final int NOTIF_ID = 18;
    private static final String CHANNEL = "alarm";
    private static final float FADE_FLOOR = 0.04f;

    /** The service while it rings (the ringing screen reads it); only used on the main thread. */
    static AlarmService running;

    Alarm alarm;
    boolean test;
    Library.Station station;                // the station that rings, null while the alarm tone plays
    String title = "", artist = "";
    boolean fallback;                       // true: the phone's alarm tone, not the radio

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PackPlayer> players = new ArrayList<>();
    private Engine engine;
    private MediaPlayer tone;
    private ToneGenerator beeper;
    private long startedAt, heardAt, toneStartedAt;
    private int session;                    // counts rings: a late answer of an earlier one is ignored
    private int restoreVolume = -1;
    private float focusDuck = 1f;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private PowerManager.WakeLock wakeLock;
    private Vibrator vibrator;
    private boolean vibrating;

    private final AudioManager.OnAudioFocusChangeListener focusListener = change -> {
        // a phone call: stay audible, but quiet
        focusDuck = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ? 0.15f : 1f;
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (alarm == null) return;
            long delay = 200;
            try {
                delay = onTick();
            } catch (RuntimeException e) {
                AppLog.e("alarm tick", e);
                useTone("error while playing the radio");
            }
            if (alarm != null) handler.postDelayed(this, delay);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.notif_alarm), NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(null, null);                       // the service plays the sound itself
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RING.equals(action)) {
            Alarm a;
            try {
                a = Alarm.fromJson(new JSONObject(intent.getStringExtra(EXTRA_ALARM)));
            } catch (Exception e) {
                AppLog.e("unreadable alarm, ringing with defaults", e);
                a = new Alarm();
            }
            start(a, intent.getBooleanExtra(EXTRA_TEST, false));
            return START_REDELIVER_INTENT;                 // if Android kills us while ringing, ring again
        }
        answer(action);
        return START_NOT_STICKY;
    }

    /** The user's answer to the ringing alarm, from the notification or the ringing screen. */
    void answer(String action) {
        if (alarm != null && ACTION_SNOOZE.equals(action)) {
            if (!test) Alarms.snooze(this, alarm.id);
            finishRinging("snooze");
        } else if (alarm != null && ACTION_KEEP.equals(action)) {
            String name = station != null && !fallback ? station.name : null;
            finishRinging("keep listening");
            if (name != null) listen(name);
        } else {
            finishRinging(ACTION_DISMISS.equals(action) ? "stopped" : "nothing to do");
        }
    }

    /** Hands the station over to the normal radio. */
    private void listen(String name) {
        Intent i = new Intent(this, RadioService.class).setAction(RadioService.ACTION_PLAY).putExtra(RadioService.EXTRA_STATION, name);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (RuntimeException e) {
            AppLog.e("continue listening", e);
        }
    }

    private void start(Alarm a, boolean isTest) {
        silence();                                         // a second alarm replaces the first one
        session++;
        alarm = a;
        test = isTest;
        running = this;
        station = null;
        title = artist = "";
        fallback = false;
        focusDuck = 1f;
        startedAt = heardAt = SystemClock.elapsedRealtime();
        startForegroundNow();
        AppLog.d("alarm " + a.id + " rings" + (isTest ? " (test)" : ""));
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "awcradio:alarm");
            wakeLock.setReferenceCounted(false);
        }
        wakeLock.acquire(RING_LIMIT_MS + 60_000);
        setVolume(a.volume);
        requestFocus();
        if (a.vibrate) vibrate();
        handler.post(tick);
        if (!Alarms.unlocked(this)) {
            useTone("the phone has not been unlocked since it started");
            return;
        }
        final int mine = session;
        try {
            PackStore.refresh(this, () -> {
                if (mine == session && alarm != null && !fallback) tuneIn();
            });
        } catch (RuntimeException e) {
            AppLog.e("alarm: station packs", e);
            useTone("the station packs cannot be read");
        }
    }

    private void tuneIn() {
        try {
            List<Library.Station> list = PackStore.get(this).stations;
            String wanted = alarm.station;
            if (wanted.isEmpty()) wanted = getSharedPreferences(RadioService.PREFS, MODE_PRIVATE).getString(RadioService.PREF_LAST, "");
            int i = Catalog.indexOf(list, wanted);
            if (i < 0 && alarm.station.isEmpty() && !list.isEmpty()) i = 0;
            if (i < 0) {
                useTone(list.isEmpty() ? "no station pack" : "the pack of " + wanted + " is missing");
                return;
            }
            station = list.get(i);
            final Library.Station tuned = station;
            engine = new Engine(station.programme(RadioService.content(this), RadioService.crossfade(this)), () -> System.currentTimeMillis() / 1000.0, track -> {
                PackPlayer p = new PackPlayer(tuned.packOf(track), "t/" + track.key, AudioAttributes.USAGE_ALARM);
                players.add(p);
                return p;
            });
            engine.setDj(RadioService.dj(this));
            engine.setDuck(gain());
            heardAt = SystemClock.elapsedRealtime();
            updateNotification();
        } catch (RuntimeException e) {
            AppLog.e("alarm: tune in", e);
            useTone("the station cannot be started");
        }
    }

    /** The loudness right now: rising during the soft start, low during a phone call. */
    private float gain() {
        float g = 1f;
        long since = SystemClock.elapsedRealtime() - (fallback ? toneStartedAt : startedAt);
        long fade = (fallback ? Math.min(alarm.fadeSec, 20) : alarm.fadeSec) * 1000L;
        if (fade > 0 && since < fade) {
            float x = since / (float) fade;
            g = FADE_FLOOR + (1f - FADE_FLOOR) * x * x;           // squared: sounds like an even rise to the ear
        }
        return g * focusDuck;
    }

    private long onTick() {
        long now = SystemClock.elapsedRealtime();
        if (now - startedAt > (test ? TEST_LIMIT_MS : RING_LIMIT_MS)) {
            if (!test) Alarms.missed(this, alarm, System.currentTimeMillis() - RING_LIMIT_MS);
            finishRinging("nobody stopped it");
            return 0;
        }
        if (fallback) {
            if (tone != null) {
                float g = gain();
                tone.setVolume(g, g);
            } else if (beeper != null) {
                beeper.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700);
                return 1200;
            }
            return 200;
        }
        long delay = 200;
        if (engine != null) {
            engine.setDuck(gain());
            delay = Math.min(200, engine.tick());
            engine.takeChanged();
            Schedule.Item it = engine.current();
            if (it != null) {
                String t = RadioService.itemTitle(this, it);
                String a = RadioService.itemArtist(this, it);
                if (Schedule.SONG.equals(it.kind) && it.track.partStart != null) {
                    int k = it.track.partAt(System.currentTimeMillis() / 1000.0 - it.start);
                    if (k >= 0 && !it.track.partTitle[k].isEmpty()) {
                        t = it.track.partTitle[k];
                        a = it.track.partArtist[k];
                    }
                }
                if (!t.equals(title) || !a.equals(artist)) {
                    title = t;
                    artist = a;
                    updateNotification();
                }
            }
            for (Iterator<PackPlayer> p = players.iterator(); p.hasNext(); ) {
                PackPlayer player = p.next();
                if (player.isReleased()) p.remove();
                else if (player.isPlaying()) heardAt = now;
            }
        }
        if (now - heardAt > RADIO_TIMEOUT_MS) useTone("no sound from the radio");
        return delay;
    }

    // ------------------------------------------------------------------------------------------ the safety net
    private void useTone(String why) {
        if (alarm == null || fallback) return;
        AppLog.d("alarm uses the phone's alarm tone: " + why);
        stopRadio();
        fallback = true;
        station = null;
        title = artist = "";
        toneStartedAt = SystemClock.elapsedRealtime();
        vibrate();                                         // something is wrong: wake the user in every way
        int[] kinds = {RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION};
        for (int kind : kinds) {
            MediaPlayer mp = null;
            try {
                Uri uri = RingtoneManager.getDefaultUri(kind);
                if (uri == null) continue;
                mp = new MediaPlayer();
                mp.setAudioAttributes(attributes());
                mp.setDataSource(this, uri);
                mp.setLooping(true);
                mp.setOnErrorListener((m, what, extra) -> {
                    AppLog.d("alarm tone error " + what + "/" + extra);
                    if (m == tone) {
                        tone = null;
                        m.release();
                        beep();
                    }
                    return true;
                });
                mp.prepare();
                float g = gain();
                mp.setVolume(g, g);
                mp.start();
                tone = mp;
                updateNotification();
                return;
            } catch (Exception e) {
                AppLog.d("alarm tone " + kind + " not usable: " + e);
                if (mp != null) mp.release();
            }
        }
        beep();
        updateNotification();
    }

    private void beep() {
        if (beeper != null || alarm == null) return;
        try {
            beeper = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
        } catch (RuntimeException e) {
            AppLog.e("no tone generator", e);
        }
    }

    private void vibrate() {
        if (vibrating || vibrator == null || !vibrator.hasVibrator()) return;
        long[] pattern = {0, 600, 900};
        try {
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes());
            else vibrator.vibrate(pattern, 0, attributes());
            vibrating = true;
        } catch (RuntimeException e) {
            AppLog.e("vibrate", e);
        }
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    }

    // ------------------------------------------------------------------------------------------ volume and focus
    private void setVolume(int percent) {
        try {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            if (restoreVolume < 0) restoreVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            audio.setStreamVolume(AudioManager.STREAM_ALARM, Math.max(1, Math.round(max * percent / 100f)), 0);
        } catch (RuntimeException e) {
            AppLog.e("alarm volume", e);
        }
    }

    private void restoreVolume() {
        if (restoreVolume < 0) return;
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, restoreVolume, 0);
        } catch (RuntimeException e) {
            AppLog.e("alarm volume", e);
        }
        restoreVolume = -1;
    }

    /** Full audio focus: a radio or another player that is still on stops. */
    private void requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes())
                    .setOnAudioFocusChangeListener(focusListener).build();
            audio.requestAudioFocus(focusRequest);
        } else {
            audio.requestAudioFocus(focusListener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audio.abandonAudioFocus(focusListener);
        }
    }

    // ------------------------------------------------------------------------------------------ ending
    private void stopRadio() {
        if (engine != null) engine.stop();
        engine = null;
        players.clear();
    }

    private void silence() {
        handler.removeCallbacks(tick);
        stopRadio();
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (beeper != null) {
            beeper.release();
            beeper = null;
        }
        if (vibrating) {
            vibrator.cancel();
            vibrating = false;
        }
    }

    private void finishRinging(String why) {
        if (alarm != null) AppLog.d("alarm " + alarm.id + " ends: " + why);
        silence();
        alarm = null;
        session++;
        if (running == this) running = null;
        restoreVolume();
        abandonFocus();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        stopForeground(true);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (alarm != null) finishRinging("service destroyed");
        handler.removeCallbacksAndMessages(null);
        if (running == this) running = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------ notification
    private PendingIntent action(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, AlarmService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification.Action button(int icon, String label, String what) {
        return new Notification.Action.Builder(Icon.createWithResource(this, icon), label, action(what)).build();
    }

    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this).setPriority(Notification.PRIORITY_MAX);
        PendingIntent screen = PendingIntent.getActivity(this, 2, new Intent(this, AlarmActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = station == null ? getString(fallback ? R.string.alarm_tone : R.string.loading)
                : station.name + (title.isEmpty() ? "" : " • " + title);
        b.setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.alarm_ringing_title, String.format(java.util.Locale.getDefault(), "%02d:%02d", alarm.hour, alarm.minute)))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Ui.SUN)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(screen)
                .setFullScreenIntent(screen, true)
                .addAction(button(R.drawable.ic_alarm, getString(R.string.alarm_snooze, alarm.snoozeMin), ACTION_SNOOZE))
                .addAction(button(R.drawable.ic_stop, getString(R.string.alarm_stop), ACTION_DISMISS));
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void startForegroundNow() {
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            else startForeground(NOTIF_ID, n);
        } catch (RuntimeException e) {
            AppLog.e("alarm startForeground", e);          // ring anyway
        }
    }

    private void updateNotification() {
        if (alarm == null) return;
        try {
            ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotification());
        } catch (RuntimeException e) {
            AppLog.e("alarm notification", e);
        }
    }
}
