package org.awctool.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Android calls this when an alarm is due, and when the appointments have to be made again: after a restart (also before
 * the first unlock), after an update of the app, and when the clock or the time zone changed.
 */
public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_CANCEL_SNOOZE = "org.awctool.radio.ALARM_CANCEL_SNOOZE";

    @Override
    public void onReceive(Context context, Intent intent) {
        AppLog.init(context);
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        try {
            if (Alarms.ACTION_FIRE.equals(action)) {
                int id = intent.getIntExtra(Alarms.EXTRA_ID, 0);
                boolean snooze = intent.getBooleanExtra(Alarms.EXTRA_SNOOZE, false);
                Alarm ringing = Alarms.fire(context, id, snooze);
                AppLog.d("alarm " + id + (snooze ? " (snooze)" : "") + " is due" + (ringing == null ? ", but not wanted any more" : ""));
                if (ringing != null) ring(context, ringing, false);
            } else if (ACTION_CANCEL_SNOOZE.equals(action)) {
                Alarms.cancelSnooze(context, intent.getIntExtra(Alarms.EXTRA_ID, 0));
            } else {
                Alarms.replanAll(context, action.substring(action.lastIndexOf('.') + 1));
            }
        } catch (RuntimeException e) {
            AppLog.e("alarm receiver " + action, e);
        }
    }

    /** Starts the ringing (or, with test set, a try-out from the alarm editor). */
    static void ring(Context context, Alarm alarm, boolean test) {
        Intent i;
        try {
            i = new Intent(context, AlarmService.class).setAction(AlarmService.ACTION_RING)
                    .putExtra(AlarmService.EXTRA_ALARM, alarm.toJson().toString())
                    .putExtra(AlarmService.EXTRA_TEST, test);
        } catch (org.json.JSONException e) {
            AppLog.e("ring", e);
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (RuntimeException e) {
            // Android 12+ refuses this without the permission for exact alarms: ring with a notification instead
            AppLog.e("the ringing service cannot start", e);
            Alarms.ringByNotification(context, alarm);
        }
    }
}
