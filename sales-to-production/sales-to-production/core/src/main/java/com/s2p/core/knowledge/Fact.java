package com.s2p.core.knowledge;

/** One piece of data an answer depends on, with where it came from and how old it was when used. */
public record Fact(String source, String ref, String statement, long sourceVersion, long ageMinutes) {
    public String describe() {
        return statement + "  [" + source + " " + ref + ", v" + sourceVersion + ", " + Freshness.ageText(ageMinutes)
                + " old]";
    }
}
