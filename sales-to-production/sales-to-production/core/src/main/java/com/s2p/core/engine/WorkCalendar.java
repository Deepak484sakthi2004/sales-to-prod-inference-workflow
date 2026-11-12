package com.s2p.core.engine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Monday to Saturday are working days; Sunday is closed for production, dispatch and delivery. */
public final class WorkCalendar {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private WorkCalendar() {
    }

    public static boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static LocalDate addWorkingDays(LocalDate d, int n) {
        LocalDate r = d;
        int left = n;
        while (left > 0) {
            r = r.plusDays(1);
            if (isWorkingDay(r)) {
                left--;
            }
        }
        return r;
    }

    public static LocalDate minusWorkingDays(LocalDate d, int n) {
        LocalDate r = d;
        int left = n;
        while (left > 0) {
            r = r.minusDays(1);
            if (isWorkingDay(r)) {
                left--;
            }
        }
        return r;
    }

    /** Transit runs on calendar days; a delivery that would land on a Sunday lands on Monday. */
    public static LocalDate deliveryDate(LocalDate dispatch, int transitDays) {
        LocalDate d = dispatch.plusDays(transitDays);
        return isWorkingDay(d) ? d : d.plusDays(1);
    }

    /** Latest day production can finish and still deliver by the given date. */
    public static LocalDate latestFinishFor(LocalDate delivery, int transitDays, int qcWorkingDays) {
        LocalDate dispatch = delivery.minusDays(transitDays);
        while (!isWorkingDay(dispatch)) {
            dispatch = dispatch.minusDays(1);
        }
        return minusWorkingDays(dispatch, qcWorkingDays);
    }

    /** Signed number of working days from a to b: positive when b is later. */
    public static int workingDaysBetween(LocalDate a, LocalDate b) {
        if (a.equals(b)) {
            return 0;
        }
        int sign = b.isAfter(a) ? 1 : -1;
        LocalDate from = sign > 0 ? a : b;
        LocalDate to = sign > 0 ? b : a;
        int n = 0;
        for (LocalDate d = from.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkingDay(d)) {
                n++;
            }
        }
        return sign * n;
    }

    public static String fmt(LocalDate d) {
        return d == null ? "n/a" : FMT.format(d);
    }
}
