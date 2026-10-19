package com.company.campaign.runtime.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * A deterministic clock a test can advance explicitly.
 *
 * <p>It reads no system time: the instant it answers is exactly the one a test set, so a lease expiry, a
 * bounded retry instant and a frozen Wait deadline can all be exercised without sleeping.
 */
public final class MutableClock extends Clock {

    private Instant instant;

    public MutableClock(Instant start) {
        this.instant = Objects.requireNonNull(start, "start");
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }

    /** Moves the clock forward by one exact bounded duration. */
    public MutableClock advance(Duration duration) {
        this.instant = instant.plus(Objects.requireNonNull(duration, "duration"));
        return this;
    }

    /** Moves the clock to one exact instant. */
    public MutableClock set(Instant replaced) {
        this.instant = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** Redacted diagnostics: only the instant is printed, which carries no owner value. */
    @Override
    public String toString() {
        return "MutableClock[instant=" + instant + ']';
    }
}