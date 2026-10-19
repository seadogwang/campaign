package com.company.campaign.security.data;

import java.time.Duration;
import java.util.Objects;

/**
 * Deterministic retention decision for one {@link RetentionRequest}.
 *
 * <p>{@link #storeRaw()} is true only when the original value may be persisted. When retention is allowed
 * but raw storage is not, only an already-redacted projection may be kept. A refused decision has no
 * retention period.
 */
public record RetentionDecision(
        boolean allowed,
        boolean storeRaw,
        String reasonCode,
        Duration retentionPeriod
) {
    public RetentionDecision {
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (!allowed && storeRaw) {
            throw new IllegalArgumentException("A refused retention decision cannot store raw data");
        }
        if (allowed && retentionPeriod == null) {
            throw new IllegalArgumentException("An allowed retention decision needs a retention period");
        }
    }

    public static RetentionDecision refused(String reasonCode) {
        return new RetentionDecision(false, false, reasonCode, null);
    }

    public static RetentionDecision allowed(boolean storeRaw, String reasonCode, Duration retentionPeriod) {
        return new RetentionDecision(true, storeRaw, reasonCode, retentionPeriod);
    }
}