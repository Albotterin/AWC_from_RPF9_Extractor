package org.awctool.radio;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Random;
import java.util.TimeZone;

/** The rules of the alarm (when does it ring, snooze, restart, clock changes) and the station colours; plain Java. */
public final class AlarmTest {
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static long at(TimeZone z, int year, int month, int day, int hour, int minute) {
        Calendar c = new GregorianCalendar(z);
        c.clear();
        c.set(year, month - 1, day, hour, minute);
        return c.getTimeInMillis();
    }

    private static long at(int month, int day, int hour, int minute) {
        return at(BERLIN, 2026, month, day, hour, minute);
    }

    private static String show(long t, TimeZone z) {
        Calendar c = new GregorianCalendar(z);
        c.setTimeInMillis(t);
        return String.format("%d-%02d-%02d %02d:%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    private static void next(int hour, int minute, int days, long after, long want, TimeZone z, String what) {
        long got = Alarm.nextRing(hour, minute, days, after, z);
        check(got == want, what + ": " + show(got, z) + " instead of " + show(want, z));
    }

    private static void nextRing() {
        // 25 November 2026 is a Wednesday
        check(Alarm.dayIndex(Calendar.MONDAY) == 0 && Alarm.dayIndex(Calendar.SUNDAY) == 6, "day bits");
        long wed = at(11, 25, 12, 0);
        next(14, 30, 0, wed, at(11, 25, 14, 30), BERLIN, "later today");
        next(7, 0, 0, wed, at(11, 26, 7, 0), BERLIN, "tomorrow");
        next(12, 0, 0, wed, at(11, 26, 12, 0), BERLIN, "this very minute means tomorrow");
        next(12, 0, 0, wed - 1, wed, BERLIN, "one millisecond before");
        next(7, 0, Alarm.WORKDAYS, at(11, 27, 8, 0), at(11, 30, 7, 0), BERLIN, "Friday morning gone: Monday");
        next(9, 0, Alarm.WEEKEND, wed, at(11, 28, 9, 0), BERLIN, "weekend: Saturday");
        next(9, 0, 1 << 2, wed, at(12, 2, 9, 0), BERLIN, "only Wednesdays, today gone: in a week");
        next(23, 59, 1 << 1, wed, at(12, 1, 23, 59), BERLIN, "Tuesday");
        next(7, 0, 0, at(12, 31, 8, 0), at(BERLIN, 2027, 1, 1, 7, 0), BERLIN, "new year");
        // clocks jump forward on 29 March 2026 (02:00 -> 03:00): 02:30 does not exist and rings at 03:30
        next(2, 30, 0, at(3, 28, 23, 0), at(3, 29, 3, 30), BERLIN, "missing hour");
        next(7, 0, 0, at(3, 28, 23, 0), at(3, 29, 7, 0), BERLIN, "morning after the spring change");
        // and back on 25 October 2026: 02:30 happens twice, the alarm rings once
        long first = Alarm.nextRing(2, 30, Alarm.EVERY_DAY, at(10, 24, 23, 0), BERLIN);
        long second = Alarm.nextRing(2, 30, Alarm.EVERY_DAY, first, BERLIN);
        check(second - first > 20L * 3600 * 1000, "the double hour rings once: " + (second - first));

        // many random cases in several zones: in the future, the right weekday and time, and nothing earlier possible
        Random r = new Random(7);
        String[] zones = {"Europe/Berlin", "America/New_York", "Australia/Lord_Howe", "Asia/Kolkata", "America/Havana", "UTC"};
        for (int i = 0; i < 20000; i++) {
            TimeZone z = TimeZone.getTimeZone(zones[r.nextInt(zones.length)]);
            long after = at(z, 2026, 1, 1, 0, 0) + (long) (r.nextDouble() * 730 * 86400000L);
            int h = r.nextInt(24), m = r.nextInt(60), days = r.nextInt(128);
            long t = Alarm.nextRing(h, m, days, after, z);
            check(t > after && t - after <= 8L * 86400000, "range " + show(after, z) + " " + h + ":" + m + " " + days + " " + z.getID());
            Calendar c = new GregorianCalendar(z);
            c.setTimeInMillis(t);
            check(days == 0 || (days & (1 << Alarm.dayIndex(c.get(Calendar.DAY_OF_WEEK)))) != 0, "weekday");
            boolean exact = c.get(Calendar.HOUR_OF_DAY) == h && c.get(Calendar.MINUTE) == m;
            Calendar noon = (Calendar) c.clone();
            noon.set(Calendar.HOUR_OF_DAY, 0);
            check(exact || z.inDaylightTime(c.getTime()) != z.inDaylightTime(noon.getTime()), "time " + show(t, z) + " for " + h + ":" + m + " " + z.getID());
            if (days == 0) check(t - after <= 25L * 3600 * 1000 + 3600000, "a one-time alarm rings within a day: " + show(after, z) + " -> " + show(t, z));
        }
    }

    private static Alarm alarm(int days) {
        Alarm a = new Alarm();
        a.id = 3;
        a.hour = 7;
        a.days = days;
        return a;
    }

    private static void life() throws Exception {
        long evening = at(11, 25, 22, 0), ring = at(11, 26, 7, 0);
        Alarm once = alarm(0);
        once.arm(evening, BERLIN);
        check(once.nextAt == ring && once.upcoming() == ring, "armed");
        once.fired(false, ring, BERLIN);
        check(!once.enabled && once.nextAt == 0 && once.upcoming() == 0, "a one-time alarm switches off");
        once.snooze(ring + 20_000);
        check(!once.enabled && once.upcoming() == ring + 20_000 + 600_000, "but can still snooze");
        once.fired(true, once.snoozeAt, BERLIN);
        check(once.upcoming() == 0, "snooze over");

        Alarm daily = alarm(Alarm.EVERY_DAY);
        daily.arm(evening, BERLIN);
        daily.fired(false, ring - 30, BERLIN);                         // Android woke us a moment early
        check(daily.nextAt == at(11, 27, 7, 0), "next day, not the same one again: " + show(daily.nextAt, BERLIN));
        daily.snooze(ring);
        check(daily.upcoming() == ring + 600_000, "the snooze comes first");
        daily.fired(true, ring + 600_000, BERLIN);
        check(daily.enabled && daily.upcoming() == at(11, 27, 7, 0), "the regular time stays");
        daily.snooze(ring);
        daily.arm(ring + 1000, BERLIN);
        check(daily.snoozeAt == 0, "saving forgets the snooze");
        daily.enabled = false;
        daily.arm(ring, BERLIN);
        check(daily.upcoming() == 0, "switched off");

        // restart: still in the future
        Alarm a = alarm(0);
        a.arm(evening, BERLIN);
        check(!a.replan(evening + 3600_000, BERLIN) && a.nextAt == ring, "restart before");
        check(!a.replan(ring + 5 * 60_000, BERLIN) && a.nextAt == ring && a.enabled, "restart 5 minutes late: rings now");
        check(a.replan(ring + 11 * 60_000, BERLIN) && a.nextAt == 0 && !a.enabled, "restart 11 minutes late: missed");
        Alarm d = alarm(Alarm.EVERY_DAY);
        d.arm(evening, BERLIN);
        check(d.replan(at(11, 28, 12, 0), BERLIN) && d.enabled && d.nextAt == at(11, 29, 7, 0), "days off: missed, next planned");
        d.snooze(at(11, 28, 12, 0));
        check(!d.replan(at(11, 28, 12, 15), BERLIN) && d.snoozeAt != 0, "late snooze still rings");
        check(d.replan(at(11, 28, 13, 0), BERLIN) && d.snoozeAt == 0, "old snooze dropped");
        // time zone change: the wall clock counts
        TimeZone london = TimeZone.getTimeZone("Europe/London");
        Alarm t = alarm(Alarm.EVERY_DAY);
        t.arm(evening, BERLIN);
        check(!t.replan(evening, london) && t.nextAt == at(london, 2026, 11, 26, 7, 0), "7 o'clock in the new zone");
        Alarm off = alarm(Alarm.EVERY_DAY);
        off.enabled = false;
        off.nextAt = 123;
        check(!off.replan(evening, BERLIN) && off.nextAt == 0, "an alarm that is off is never missed");

        Alarm full = alarm(Alarm.WEEKEND);
        full.station = "Flash FM";
        full.volume = 45;
        full.fadeSec = 60;
        full.snoozeMin = 5;
        full.vibrate = true;
        full.nextAt = ring;
        full.snoozeAt = ring + 5;
        Alarm copy = Alarm.fromJson(full.toJson());
        check(copy != full && copy.toJson().toString().equals(full.toJson().toString()), "saved and read back");
        Alarm bare = Alarm.fromJson(new org.json.JSONObject("{\"id\":9,\"h\":99,\"m\":-3,\"vol\":0}"));
        check(bare.hour == 23 && bare.minute == 0 && bare.volume == 10 && bare.enabled && bare.station.isEmpty(), "bad values are repaired");
    }

    private static void colors() {
        Random r = new Random(11);
        for (int i = 0; i < 3000; i++) {
            int[] px = new int[400];
            int a = 0xFF000000 | r.nextInt(0x1000000), b = 0xFF000000 | r.nextInt(0x1000000);
            for (int k = 0; k < px.length; k++) px[k] = k < 250 ? a : b;
            StationColor c = StationColor.of(px);
            check(StationColor.contrast(0xFFFFFFFF, c.tint) >= 7.9, "white on " + Integer.toHexString(c.tint));
            check(StationColor.contrast(c.light, 0xFF15122A) >= 3.0, "light " + Integer.toHexString(c.light) + " on the night");
        }
        int[] redOnWhite = new int[100];
        for (int k = 0; k < 100; k++) redOnWhite[k] = k < 30 ? 0xFFE02020 : 0xFFFFFFFF;
        float hue = StationColor.toHsl(StationColor.of(redOnWhite).tint)[0];
        check(hue < 10 || hue > 350, "white does not count: " + hue);
        int[] grey = new int[100];
        java.util.Arrays.fill(grey, 0xFF444444);
        check(StationColor.of(grey).tint == StationColor.plain().tint && StationColor.of(new int[0]).tint == StationColor.plain().tint, "plain");
    }

    public static void main(String[] args) throws Exception {
        nextRing();
        life();
        colors();
        System.out.println("Alarm: ALL OK (" + checks + " checks)");
    }
}
