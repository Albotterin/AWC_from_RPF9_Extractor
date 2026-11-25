package org.awctool.radio;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * One radio alarm and the rules for when it rings. No Android classes, so that the rules are tested on the PC
 * (android/test/AlarmTest.java); Alarms stores them and hands the times to Android.
 */
final class Alarm {
    static final int EVERY_DAY = 0x7F, WORKDAYS = 0x1F, WEEKEND = 0x60;
    /** An alarm that came due this little time ago (phone restarting, clock set) still rings; older ones count as missed. */
    static final long LATE_MS = 10L * 60 * 1000;

    int id;
    int hour = 7, minute;
    int days;                      // bit 0 = Monday ... bit 6 = Sunday; 0 = only once
    boolean enabled = true;
    String station = "";           // the station's name; "" = the station heard last
    int volume = 70;               // percent of the phone's alarm volume while it rings
    int fadeSec = 30;              // soft start: seconds until the full volume, 0 = at once
    int snoozeMin = 10;
    boolean vibrate;
    long nextAt;                   // UNIX millis of the next regular ring, 0 = none
    long snoozeAt;                 // UNIX millis of a pending snooze, 0 = none

    Alarm copy() {
        try {
            return fromJson(toJson());
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Monday = 0 ... Sunday = 6. */
    static int dayIndex(int calendarDay) {
        return (calendarDay + 5) % 7;
    }

    /** The first moment after `after` at which the wall clock of `zone` shows hour:minute on one of the days. */
    static long nextRing(int hour, int minute, int days, long after, TimeZone zone) {
        for (int d = 0; d <= 8; d++) {
            Calendar c = new GregorianCalendar(zone);
            c.setTimeInMillis(after);
            c.set(Calendar.HOUR_OF_DAY, 12);                       // noon first: adding days is then safe around DST
            c.add(Calendar.DAY_OF_MONTH, d);
            c.set(Calendar.HOUR_OF_DAY, hour);                     // a time that does not exist (spring) moves forward
            c.set(Calendar.MINUTE, minute);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            long t = c.getTimeInMillis();
            if (t > after && (days == 0 || (days & (1 << dayIndex(c.get(Calendar.DAY_OF_WEEK)))) != 0)) return t;
        }
        return 0;
    }

    /** The user saved or switched the alarm: plan it from now, forget a snooze. */
    void arm(long now, TimeZone zone) {
        snoozeAt = 0;
        nextAt = enabled ? nextRing(hour, minute, days, now, zone) : 0;
    }

    /** It rang just now: plan the following time; a one-time alarm switches itself off. */
    void fired(boolean snooze, long now, TimeZone zone) {
        if (snooze) {
            snoozeAt = 0;
            return;
        }
        snoozeAt = 0;
        if (days == 0) {
            enabled = false;
            nextAt = 0;
        } else {
            nextAt = nextRing(hour, minute, days, Math.max(now, nextAt), zone);
        }
    }

    void snooze(long now) {
        snoozeAt = now + snoozeMin * 60_000L;
    }

    /**
     * After a restart, an app update or a change of the clock or time zone. A time that has just passed is kept (Android
     * then rings immediately); one that is older is dropped.
     *
     * @return true if a ring was missed
     */
    boolean replan(long now, TimeZone zone) {
        boolean missed = false;
        if (snoozeAt != 0 && now - snoozeAt > LATE_MS) {
            snoozeAt = 0;
            missed = true;
        }
        if (!enabled) {
            nextAt = 0;
        } else if (nextAt != 0 && nextAt <= now) {
            if (now - nextAt > LATE_MS) {
                missed = true;
                if (days == 0) {
                    enabled = false;
                    nextAt = 0;
                } else {
                    nextAt = nextRing(hour, minute, days, now, zone);
                }
            }
        } else {
            nextAt = nextRing(hour, minute, days, now, zone);
        }
        return missed;
    }

    /** When it rings next (a snooze or the regular time), 0 = never. */
    long upcoming() {
        long regular = enabled ? nextAt : 0;
        if (snoozeAt == 0) return regular;
        return regular == 0 ? snoozeAt : Math.min(regular, snoozeAt);
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("h", hour).put("m", minute).put("days", days).put("on", enabled)
                .put("station", station).put("vol", volume).put("fade", fadeSec).put("snooze", snoozeMin)
                .put("vib", vibrate).put("next", nextAt).put("snoozeAt", snoozeAt);
    }

    static Alarm fromJson(JSONObject o) throws JSONException {
        Alarm a = new Alarm();
        a.id = o.getInt("id");
        a.hour = clamp(o.getInt("h"), 0, 23);
        a.minute = clamp(o.getInt("m"), 0, 59);
        a.days = o.optInt("days", 0) & EVERY_DAY;
        a.enabled = o.optBoolean("on", true);
        a.station = o.optString("station", "");
        a.volume = clamp(o.optInt("vol", 70), 10, 100);
        a.fadeSec = clamp(o.optInt("fade", 30), 0, 600);
        a.snoozeMin = clamp(o.optInt("snooze", 10), 1, 60);
        a.vibrate = o.optBoolean("vib", false);
        a.nextAt = o.optLong("next", 0);
        a.snoozeAt = o.optLong("snoozeAt", 0);
        return a;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
