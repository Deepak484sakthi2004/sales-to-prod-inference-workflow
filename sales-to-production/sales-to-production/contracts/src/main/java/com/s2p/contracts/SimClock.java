package com.s2p.contracts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Business clock shared by every process. Every process is started with the same simulated start instant and the
 * same real-time anchor, so they agree on "now" without talking to each other. Tests use a frozen clock and advance it.
 */
public final class SimClock extends Clock {
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final Clock base;
    private volatile Duration offset;

    private SimClock(Clock base, Duration offset) {
        this.base = base;
        this.offset = offset;
    }

    /** Advances in real time from simStart; realAnchor is the wall-clock instant that maps to simStart. */
    public static SimClock live(OffsetDateTime simStart, Instant realAnchor) {
        return new SimClock(Clock.systemUTC(), Duration.between(realAnchor, simStart.toInstant()));
    }

    /** Does not move unless advanced. Used by tests and the in-process scenario runner. */
    public static SimClock frozen(OffsetDateTime simStart) {
        return new SimClock(Clock.fixed(simStart.toInstant(), ZoneOffset.UTC), Duration.ZERO);
    }

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZONE;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    public OffsetDateTime now() {
        return OffsetDateTime.ofInstant(instant(), ZONE);
    }

    public LocalDate today() {
        return now().toLocalDate();
    }
}
