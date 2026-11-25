package org.awctool.radio;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.text.format.DateFormat;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * The saved alarms and their appointments with Android. They live in device protected storage, so that an alarm still
 * rings when the phone restarted at night and has not been unlocked yet. Every change plans the AlarmManager anew.
 */
final class Alarms {
    static final String ACTION_FIRE = "org.awctool.radio.ALARM_FIRE";
    static final String EXTRA_ID = "alarm", EXTRA_SNOOZE = "snooze";
    static final String CHANNEL_INFO = "alarm_info";
    private static final String PREFS = "alarms", KEY_LIST = "list", KEY_NEXT_ID = "nextId";
    private static final String CHANNEL_BACKUP = "alarm_backup";
    private static final int NOTIF_SNOOZE = 2000, NOTIF_MISSED = 3000, NOTIF_BACKUP = 4000;

    private Alarms() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** False between a restart and the first unlock: the station packs and the radio's settings cannot be read then. */
    static boolean unlocked(Context ctx) {
        UserManager um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        return um == null || um.isUserUnlocked();
    }

    /** All alarms, by time of day. */
    static synchronized List<Alarm> list(Context ctx) {
        List<Alarm> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_LIST, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Alarm.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            AppLog.e("reading the alarms", e);
        }
        Collections.sort(out, (a, b) -> a.hour != b.hour ? a.hour - b.hour : a.minute != b.minute ? a.minute - b.minute : a.id - b.id);
        return out;
    }

    static Alarm get(Context ctx, int id) {
        for (Alarm a : list(ctx)) if (a.id == id) return a;
        return null;
    }

    private static synchronized void write(Context ctx, List<Alarm> all) {
        JSONArray arr = new JSONArray();
        try {
            for (Alarm a : all) arr.put(a.toJson());
        } catch (org.json.JSONException e) {
            AppLog.e("saving the alarms", e);
            return;
        }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).commit();        // on disk before anything else happens
    }

    private static synchronized void put(Context ctx, Alarm alarm) {
        List<Alarm> all = list(ctx);
        boolean found = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == alarm.id) {
                all.set(i, alarm);
                found = true;
            }
        }
        if (!found) all.add(alarm);
        write(ctx, all);
    }

    /** Saves a new (id 0) or changed alarm as the user wants it now, and plans it. */
    static synchronized Alarm save(Context ctx, Alarm alarm) {
        if (alarm.id == 0) {
            alarm.id = prefs(ctx).getInt(KEY_NEXT_ID, 1);
            prefs(ctx).edit().putInt(KEY_NEXT_ID, alarm.id + 1).commit();
        }
        alarm.arm(System.currentTimeMillis(), TimeZone.getDefault());
        put(ctx, alarm);
        notifications(ctx).cancel(NOTIF_SNOOZE + alarm.id);
        plan(ctx, alarm);
        AppLog.d("alarm " + alarm.id + (alarm.enabled ? " set for " + new Date(alarm.nextAt) : " off"));
        return alarm;
    }

    static synchronized void delete(Context ctx, int id) {
        List<Alarm> all = list(ctx);
        for (int i = all.size() - 1; i >= 0; i--) {
            Alarm a = all.get(i);
            if (a.id != id) continue;
            a.enabled = false;
            a.snoozeAt = 0;
            plan(ctx, a);
            all.remove(i);
        }
        write(ctx, all);
        notifications(ctx).cancel(NOTIF_SNOOZE + id);
        AppLog.d("alarm " + id + " deleted");
    }

    /** Android woke us for this alarm: returns it (to ring) after planning its next time, or null if it is not wanted. */
    static synchronized Alarm fire(Context ctx, int id, boolean snooze) {
        Alarm a = get(ctx, id);
        if (a == null || (snooze ? a.snoozeAt == 0 : !a.enabled)) return null;
        Alarm ringing = a.copy();
        a.fired(snooze, System.currentTimeMillis(), TimeZone.getDefault());
        put(ctx, a);
        plan(ctx, a);
        notifications(ctx).cancel(NOTIF_SNOOZE + id);
        return ringing;
    }

    /** "Snooze": ring again in a few minutes; a notification offers to cancel that. */
    static synchronized void snooze(Context ctx, int id) {
        Alarm a = get(ctx, id);
        if (a == null) return;
        a.snooze(System.currentTimeMillis());
        put(ctx, a);
        plan(ctx, a);
        AppLog.d("alarm " + id + " snoozes " + a.snoozeMin + " min");
        Intent cancel = new Intent(ctx, AlarmReceiver.class).setAction(AlarmReceiver.ACTION_CANCEL_SNOOZE)
                .setData(Uri.parse("awc://snooze/" + id)).putExtra(EXTRA_ID, id);
        Notification.Builder b = builder(ctx).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(ctx.getString(R.string.alarm_snoozing, time(ctx, a.snoozeAt)))
                .setContentIntent(showIntent(ctx)).setShowWhen(false).setOngoing(true)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stop), ctx.getString(R.string.alarm_cancel_snooze),
                        PendingIntent.getBroadcast(ctx, 0, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
        post(ctx, NOTIF_SNOOZE + id, b.build());
    }

    static synchronized void cancelSnooze(Context ctx, int id) {
        Alarm a = get(ctx, id);
        if (a != null) {
            a.snoozeAt = 0;
            put(ctx, a);
            plan(ctx, a);
        }
        notifications(ctx).cancel(NOTIF_SNOOZE + id);
    }

    /** After a restart, an update or a change of the clock: Android has forgotten the appointments, plan all again. */
    static synchronized void replanAll(Context ctx, String why) {
        List<Alarm> all = list(ctx);
        long now = System.currentTimeMillis();
        for (Alarm a : all) {
            long was = a.upcoming();
            if (a.replan(now, TimeZone.getDefault())) {
                AppLog.d("alarm " + a.id + " was missed (" + new Date(was) + ")");
                missed(ctx, a, was);
            }
        }
        write(ctx, all);
        for (Alarm a : all) plan(ctx, a);
        if (!all.isEmpty()) AppLog.d("alarms planned again (" + why + "): " + all.size());
    }

    static void missed(Context ctx, Alarm a, long when) {
        post(ctx, NOTIF_MISSED + a.id, builder(ctx).setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(ctx.getString(R.string.alarm_missed))
                .setContentText(time(ctx, when))
                .setAutoCancel(true).setContentIntent(showIntent(ctx)).build());
    }

    /** The last resort: a notification that repeats the phone's alarm sound until it is dismissed. */
    static void ringByNotification(Context ctx, Alarm a) {
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        AudioAttributes asAlarm = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_BACKUP, ctx.getString(R.string.notif_alarm),
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setSound(sound, asAlarm);
            ch.enableVibration(true);
            notifications(ctx).createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL_BACKUP);
        } else {
            b = new Notification.Builder(ctx).setSound(sound, asAlarm).setPriority(Notification.PRIORITY_MAX)
                    .setVibrate(new long[]{0, 600, 900, 600});
        }
        Notification n = b.setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(ctx.getString(R.string.alarm_ringing_title, Ui.clock(a.hour, a.minute)))
                .setCategory(Notification.CATEGORY_ALARM).setAutoCancel(true).setContentIntent(showIntent(ctx)).build();
        n.flags |= Notification.FLAG_INSISTENT;
        post(ctx, NOTIF_BACKUP + a.id, n);
    }

    /** The next ring of any alarm: its time, or 0. */
    static long nextAt(Context ctx) {
        long best = 0;
        for (Alarm a : list(ctx)) {
            long t = a.upcoming();
            if (t != 0 && (best == 0 || t < best)) best = t;
        }
        return best;
    }

    static String time(Context ctx, long at) {
        return DateFormat.getTimeFormat(ctx).format(new Date(at));
    }

    // ------------------------------------------------------------------------------------------ Android
    static boolean exactAllowed(Context ctx) {
        if (Build.VERSION.SDK_INT < 31) return true;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am.canScheduleExactAlarms();
    }

    private static PendingIntent showIntent(Context ctx) {
        Intent i = new Intent(ctx, MainActivity.class).setAction(MainActivity.ACTION_SHOW_ALARMS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent fireIntent(Context ctx, int id, boolean snooze) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(ACTION_FIRE)
                .setData(Uri.parse("awc://alarm/" + id + (snooze ? "/snooze" : "")))      // makes the two appointments distinct
                .putExtra(EXTRA_ID, id).putExtra(EXTRA_SNOOZE, snooze);
        return PendingIntent.getBroadcast(ctx, 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void plan(Context ctx, Alarm a) {
        set(ctx, fireIntent(ctx, a.id, false), a.enabled ? a.nextAt : 0);
        set(ctx, fireIntent(ctx, a.id, true), a.snoozeAt);
    }

    private static void set(Context ctx, PendingIntent operation, long at) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (at == 0) {
            am.cancel(operation);
            return;
        }
        try {
            if (exactAllowed(ctx)) {
                // an alarm clock appointment: exact also in deep sleep, and Android shows it in the status bar
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showIntent(ctx)), operation);
                return;
            }
        } catch (SecurityException e) {
            AppLog.e("exact alarm refused", e);
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation);          // may be late; the screen warns
    }

    private static NotificationManager notifications(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static Notification.Builder builder(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return new Notification.Builder(ctx);
        NotificationChannel ch = new NotificationChannel(CHANNEL_INFO, ctx.getString(R.string.notif_alarm_info),
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setSound(null, null);
        notifications(ctx).createNotificationChannel(ch);
        return new Notification.Builder(ctx, CHANNEL_INFO);
    }

    private static void post(Context ctx, int id, Notification n) {
        try {
            notifications(ctx).notify(id, n);
        } catch (SecurityException e) {
            AppLog.e("notification", e);
        }
    }
}
