package com.s2p.core.knowledge;

import com.s2p.core.state.Source;

import java.time.OffsetDateTime;

/**
 * How old a source's data is, judged against its SLA. TOO_OLD_TO_COMMIT and UNAVAILABLE mean the system may still
 * show an indicative figure but must not make a commitment from it.
 */
public record Freshness(Source source, OffsetDateTime asOf, long ageMinutes, long slaMinutes, String status,
                        String note) {
    public static final String FRESH = "FRESH";
    public static final String STALE = "STALE";
    public static final String TOO_OLD_TO_COMMIT = "TOO_OLD_TO_COMMIT";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    public boolean blocksCommitment() {
        return status.equals(TOO_OLD_TO_COMMIT) || status.equals(UNAVAILABLE);
    }

    public boolean fresh() {
        return status.equals(FRESH);
    }

    public String describe() {
        String base = source + ": " + (asOf == null ? "no data" : ageText(ageMinutes) + " old") + " (SLA "
                + ageText(slaMinutes) + ") " + status;
        return note == null ? base : base + " - " + note;
    }

    public static String ageText(long minutes) {
        if (minutes < 1) {
            return "<1 min";
        }
        if (minutes < 120) {
            return minutes + " min";
        }
        if (minutes < 48 * 60) {
            return (minutes / 60) + " h " + (minutes % 60) + " min";
        }
        return (minutes / (60 * 24)) + " days";
    }
}
