package org.awctool.radio;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.UserManager;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole way of an alarm on Android 14: saved, handed to the AlarmManager, woken, ringing with the radio, snoozed,
 * stopped; and the safety net when the radio cannot play. Sound, wake-ups and the lock screen of a real phone are not
 * part of this: players and the AlarmManager are Robolectric's stand-ins.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "en-rUS-w393dp-h851dp-xhdpi")
public class AlarmFlowTest {
    private Context app;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        ShadowAlarmManager.setCanScheduleExactAlarms(true);
        app = Harness.app();
    }

    private List<ShadowAlarmManager.ScheduledAlarm> appointments() {
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        return Shadows.shadowOf(am).getScheduledAlarms();
    }

    private Alarm saved(String station, int days) {
        Alarm a = new Alarm();
        a.station = station;
        a.days = days;
        a.fadeSec = 30;
        return Alarms.save(app, a);
    }

    /** What the receiver started: the intent for the ringing service. */
    private Intent started() {
        Intent i;
        while ((i = Shadows.shadowOf(Harness.application()).getNextStartedService()) != null) {
            if (i.getComponent() != null && i.getComponent().getClassName().equals(AlarmService.class.getName())) return i;
        }
        return null;
    }

    /** The AlarmManager's appointment comes due. */
    private void due(ShadowAlarmManager.ScheduledAlarm s) {
        Intent i = Shadows.shadowOf(s.operation).getSavedIntent();
        new AlarmReceiver().onReceive(app, i);
    }

    private ServiceController<AlarmService> ring(Intent i) {
        ServiceController<AlarmService> c = Robolectric.buildService(AlarmService.class, i).create();
        c.get().onStartCommand(i, 0, 1);
        return c;
    }

    private static int volume(Context c) {
        return ((AudioManager) c.getSystemService(Context.AUDIO_SERVICE)).getStreamVolume(AudioManager.STREAM_ALARM);
    }

    private boolean vibrating() {
        return Shadows.shadowOf((Vibrator) app.getSystemService(Context.VIBRATOR_SERVICE)).isVibrating();
    }

    @Test
    public void savedAlarmsAreAlarmClockAppointments() {
        Alarm a = saved("Flash FM", 0);
        Alarm b = saved("V-Rock", Alarm.EVERY_DAY);
        Assert.assertEquals(2, appointments().size());
        for (ShadowAlarmManager.ScheduledAlarm s : appointments()) {
            Assert.assertNotNull("shown by Android as the next alarm", s.getAlarmClockInfo());
        }
        Assert.assertEquals(Math.min(a.nextAt, b.nextAt), Alarms.nextAt(app));
        Assert.assertTrue(a.nextAt > System.currentTimeMillis());
        a.enabled = false;
        Alarms.save(app, a);
        Assert.assertEquals(1, appointments().size());
        Alarms.delete(app, b.id);
        Assert.assertEquals(0, appointments().size());
        Assert.assertEquals(1, Alarms.list(app).size());
        Assert.assertTrue("ids are never reused", saved("", 0).id > b.id);
    }

    @Test
    public void ringsSnoozesAndStops() throws Exception {
        ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).setStreamVolume(AudioManager.STREAM_ALARM, 2, 0);
        Alarm a = saved("Flash FM", 0);
        ShadowAlarmManager.ScheduledAlarm first = appointments().get(0);
        due(first);
        Intent i = started();
        Assert.assertNotNull("the ringing service starts", i);
        Assert.assertFalse("a one-time alarm is off afterwards", Alarms.get(app, a.id).enabled);

        ServiceController<AlarmService> c = ring(i);
        AlarmService s = c.get();
        Assert.assertSame(s, AlarmService.running);
        Notification n = Shadows.shadowOf(s).getLastForegroundNotification();
        Assert.assertNotNull(n.fullScreenIntent);
        Assert.assertEquals(2, n.actions.length);
        int max = ((AudioManager) app.getSystemService(Context.AUDIO_SERVICE)).getStreamMaxVolume(AudioManager.STREAM_ALARM);
        Assert.assertEquals("the alarm's own volume", Math.round(max * 0.7f), volume(app));
        Harness.settle();
        for (int k = 0; k < 300; k++) Harness.pass(200);                   // one minute
        Assert.assertFalse("the radio plays, no alarm tone", s.fallback);
        Assert.assertEquals("Flash FM", s.station.name);
        Assert.assertFalse(s.title.isEmpty() && s.artist.isEmpty());
        Assert.assertFalse(vibrating());

        s.onStartCommand(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_SNOOZE), 0, 2);
        Assert.assertNull(AlarmService.running);
        Assert.assertTrue(Shadows.shadowOf(s).isStoppedBySelf());
        Assert.assertEquals("the volume as before", 2, volume(app));
        Alarm snoozed = Alarms.get(app, a.id);
        long wait = snoozed.snoozeAt - System.currentTimeMillis();
        Assert.assertTrue("10 minutes: " + wait, wait > 9 * 60_000 && wait <= 10 * 60_000);
        Assert.assertEquals(1, appointments().size());

        due(appointments().get(0));                                        // the snooze comes due
        Intent again = started();
        Assert.assertNotNull(again);
        Assert.assertEquals(0, Alarms.get(app, a.id).snoozeAt);
        ServiceController<AlarmService> c2 = ring(again);
        c2.get().onStartCommand(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_DISMISS), 0, 2);
        Assert.assertNull(AlarmService.running);
        Assert.assertEquals(0, Alarms.nextAt(app));
    }

    @Test
    public void aCancelledSnoozeDoesNotRing() {
        Alarm a = saved("Flash FM", 0);
        due(appointments().get(0));
        started();
        Alarms.snooze(app, a.id);
        ShadowAlarmManager.ScheduledAlarm snooze = appointments().get(0);
        new AlarmReceiver().onReceive(app, new Intent(app, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_CANCEL_SNOOZE)
                .putExtra(Alarms.EXTRA_ID, a.id));
        Assert.assertEquals(0, appointments().size());
        due(snooze);                                                       // a stale wake-up
        Assert.assertNull(started());
    }

    private void expectTone(Alarm a, long within) throws Exception {
        Alarms.fire(app, a.id, false);
        ServiceController<AlarmService> c = ring(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, a.toJson().toString()));
        AlarmService s = c.get();
        Harness.settle();
        for (long t = 0; t < within && !s.fallback; t += 200) Harness.pass(200);
        Assert.assertTrue("the phone's own alarm tone takes over", s.fallback);
        Assert.assertTrue("and it vibrates", vibrating());
        Assert.assertSame("still ringing", s, AlarmService.running);
        s.answer(AlarmService.ACTION_DISMISS);
        Assert.assertFalse(vibrating());
    }

    @Test
    public void missingPackUsesTheAlarmTone() throws Exception {
        expectTone(saved("A Station That Is Gone", 0), 2000);
    }

    @Test
    public void noFolderUsesTheAlarmTone() throws Exception {
        app.getSharedPreferences("pack", 0).edit().clear().commit();
        expectTone(saved("", 0), 2000);
    }

    @Test
    public void silentPlayersUseTheAlarmTone() throws Exception {
        // every player takes forever to get ready: nothing is ever heard
        ShadowMediaPlayer.setMediaInfoProvider(source -> new ShadowMediaPlayer.MediaInfo(600_000, 3_600_000));
        expectTone(saved("Flash FM", 0), AlarmService.RADIO_TIMEOUT_MS + 3000);
    }

    @Test
    public void unreadablePlayersUseTheAlarmTone() throws Exception {
        ShadowMediaPlayer.setMediaInfoProvider(source -> {
            throw new IllegalArgumentException("broken file");
        });
        expectTone(saved("Flash FM", 0), AlarmService.RADIO_TIMEOUT_MS + 3000);
    }

    @Test
    public void beforeTheFirstUnlockTheAlarmToneRings() throws Exception {
        Alarm a = saved("Flash FM", 0);
        Shadows.shadowOf((UserManager) app.getSystemService(Context.USER_SERVICE)).setUserUnlocked(false);
        expectTone(a, 200);
    }

    @Test
    public void nobodyThereEndsAfterHalfAnHour() throws Exception {
        Alarm a = saved("Flash FM", 0);
        ServiceController<AlarmService> c = ring(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, a.toJson().toString()));
        Harness.settle();
        for (int minute = 0; minute < 29; minute++) Harness.pass(60_000);
        Assert.assertNotNull(AlarmService.running);
        Harness.pass(62_000);
        Assert.assertNull(AlarmService.running);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Assert.assertTrue("a note says the alarm was missed", Shadows.shadowOf(nm).getAllNotifications().stream()
                .anyMatch(n -> "Missed alarm".contentEquals(n.extras.getCharSequence(Notification.EXTRA_TITLE))));
        Assert.assertTrue(c.get() != null);
    }

    @Test
    public void tryingOutChangesNothing() throws Exception {
        Alarm a = saved("Flash FM", Alarm.EVERY_DAY);
        long next = a.nextAt;
        AlarmReceiver.ring(app, a, true);
        ServiceController<AlarmService> c = ring(started());
        Harness.settle();
        c.get().answer(AlarmService.ACTION_SNOOZE);                       // from the notification
        Assert.assertEquals(0, Alarms.get(app, a.id).snoozeAt);
        Assert.assertEquals(next, Alarms.get(app, a.id).nextAt);
        ServiceController<AlarmService> c2 = ring(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, a.toJson().toString()).putExtra(AlarmService.EXTRA_TEST, true));
        Harness.settle();
        Harness.pass(61_000);
        Assert.assertNull("a try-out ends by itself", AlarmService.running);
        Assert.assertNotNull(c2.get());
    }

    @Test
    public void restartAndClockChangePlanAgain() {
        saved("Flash FM", Alarm.EVERY_DAY);
        saved("", 0);
        AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        for (String action : new String[]{Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED}) {
            for (ShadowAlarmManager.ScheduledAlarm s : new ArrayList<>(appointments())) am.cancel(s.operation);   // forgotten
            Assert.assertEquals(0, appointments().size());
            new AlarmReceiver().onReceive(app, new Intent(action));
            Assert.assertEquals(action, 2, appointments().size());
        }
    }

    @Test
    public void anAlarmDuringTheRestartStillRings() throws Exception {
        Alarm a = saved("Flash FM", 0);
        org.json.JSONArray list = new org.json.JSONArray().put(Alarm.fromJson(a.toJson()).toJson()
                .put("next", System.currentTimeMillis() - 3 * 60_000));
        app.createDeviceProtectedStorageContext().getSharedPreferences("alarms", 0).edit().putString("list", list.toString()).commit();
        new AlarmReceiver().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        Assert.assertEquals(1, appointments().size());
        Assert.assertTrue("due at once", appointments().get(0).getTriggerAtMs() <= System.currentTimeMillis());
        due(appointments().get(0));
        Assert.assertNotNull(started());
    }

    @Test
    public void aRefusedServiceStillRings() {
        Alarm a = saved("Flash FM", 0);
        Context refusing = new android.content.ContextWrapper(app) {
            @Override
            public android.content.ComponentName startForegroundService(Intent service) {
                throw new IllegalStateException("not allowed from the background");
            }
        };
        AlarmReceiver.ring(refusing, a, false);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        Assert.assertTrue("a repeating alarm notification", Shadows.shadowOf(nm).getAllNotifications().stream()
                .anyMatch(n -> (n.flags & Notification.FLAG_INSISTENT) != 0));
        Assert.assertNotNull(nm.getNotificationChannel("alarm_backup").getSound());
    }

    @Test
    public void ringingScreenButtonsAnswer() throws Exception {
        Alarm a = saved("Flash FM", 0);
        Alarms.fire(app, a.id, false);
        ServiceController<AlarmService> c = ring(new Intent(app, AlarmService.class).setAction(AlarmService.ACTION_RING)
                .putExtra(AlarmService.EXTRA_ALARM, a.toJson().toString()));
        Harness.settle();
        for (int k = 0; k < 20; k++) Harness.pass(100);
        ActivityController<AlarmActivity> screen = Robolectric.buildActivity(AlarmActivity.class).setup();
        Harness.pass(600);
        screen.get().onBackPressed();
        Assert.assertNotNull("back does not end an alarm", AlarmService.running);
        press(screen.get().getWindow().getDecorView(), "Stop alarm and keep listening");
        Assert.assertNull(AlarmService.running);
        Assert.assertTrue(screen.get().isFinishing());
        Intent radio = null, i;
        while ((i = Shadows.shadowOf(Harness.application()).getNextStartedService()) != null) {
            if (RadioService.ACTION_PLAY.equals(i.getAction())) radio = i;
        }
        Assert.assertNotNull("the radio takes over", radio);
        Assert.assertEquals("Flash FM", radio.getStringExtra(RadioService.EXTRA_STATION));
        Assert.assertNotNull(c.get());
    }

    @Test
    public void theEditorSavesWhatWasChosen() {
        ActivityController<AlarmEditActivity> e = Robolectric.buildActivity(AlarmEditActivity.class,
                new Intent().putExtra("station", "V-Rock")).setup();
        Harness.settle();
        Alarm editing = ReflectionHelpers.getField(e.get(), "alarm");
        Assert.assertEquals("V-Rock", editing.station);
        editing.hour = 5;
        editing.minute = 55;
        press(e.get().getWindow().getDecorView(), "Mo");
        press(e.get().getWindow().getDecorView(), "Fr");
        press(e.get().getWindow().getDecorView(), "Save alarm");
        List<Alarm> all = Alarms.list(app);
        Assert.assertEquals(1, all.size());
        Assert.assertEquals(1 | 1 << 4, all.get(0).days);
        Assert.assertEquals(5, all.get(0).hour);
        Assert.assertEquals("V-Rock", all.get(0).station);
        Assert.assertEquals(1, appointments().size());
    }

    private static void press(View root, String text) {
        View found = find(root, text);
        Assert.assertNotNull("no button " + text, found);
        while (!found.isClickable()) found = (View) found.getParent();
        found.performClick();
    }

    private static View find(View v, String text) {
        if (v instanceof TextView && ((TextView) v).getText().toString().equals(text)) return v;
        if (v instanceof ViewGroup) {
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
                View f = find(((ViewGroup) v).getChildAt(i), text);
                if (f != null) return f;
            }
        }
        return null;
    }
}
