package com.s2p.core.ai;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What the system knows about its own use of the model: how often it was reached, how often it failed, and how often
 * its readings were rejected because the quoted text was not in the source or did not support the value.
 * After three failures in a row the model is skipped for a minute, so a down service does not slow every mail.
 */
public final class AiTelemetry {
    private static final int TRIP_AFTER = 3;
    private static final Duration OPEN_FOR = Duration.ofSeconds(60);

    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong rejectedFields = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant lastFailureAt;
    private volatile String lastError;
    private volatile String lastModel;

    public void success(String model, long latencyMs) {
        calls.incrementAndGet();
        totalLatencyMs.addAndGet(latencyMs);
        consecutiveFailures.set(0);
        if (model != null) {
            lastModel = model;
        }
    }

    public void failure(String what, String error) {
        calls.incrementAndGet();
        failures.incrementAndGet();
        consecutiveFailures.incrementAndGet();
        lastFailureAt = Instant.now();
        lastError = what + ": " + error;
    }

    public boolean circuitOpen() {
        Instant at = lastFailureAt;
        boolean open = consecutiveFailures.get() >= TRIP_AFTER && at != null && Instant.now().isBefore(at.plus(OPEN_FOR));
        if (open) {
            skipped.incrementAndGet();
        }
        return open;
    }

    public void rejectedField() {
        rejectedFields.incrementAndGet();
    }

    public String lastError() {
        return lastError;
    }

    public long failures() {
        return failures.get();
    }

    public long rejectedFields() {
        return rejectedFields.get();
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        long ok = calls.get() - failures.get();
        out.add(calls.get() + " model call(s), " + failures.get() + " failed, " + skipped.get() + " skipped while the "
                + "service was failing" + (ok > 0 ? "; average " + totalLatencyMs.get() / ok + " ms" : "")
                + (lastModel == null ? "" : "; model " + lastModel));
        out.add(rejectedFields.get() + " model reading(s) rejected because the quoted text was not in the source or did "
                + "not support the value");
        if (lastError != null) {
            out.add("last failure: " + lastError);
        }
        return out;
    }
}
