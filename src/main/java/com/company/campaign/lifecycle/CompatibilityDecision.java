package com.company.campaign.lifecycle;

import java.util.Objects;

/**
 * Result of one frozen-input compatibility recheck, with a stable internal reason code.
 *
 * <p>A null outcome is not representable; a port that cannot decide must return
 * {@link #unavailable}, which the lifecycle boundary treats as a denial. The reason code is a bounded
 * machine code and never carries a payload value.
 */
public record CompatibilityDecision(CompatibilityOutcome outcome, String reasonCode) {

    public CompatibilityDecision {
        Objects.requireNonNull(outcome, "outcome");
        reasonCode = reasonCode == null ? "" : reasonCode;
    }

    public static CompatibilityDecision compatible() {
        return new CompatibilityDecision(CompatibilityOutcome.COMPATIBLE, "COMPATIBILITY_COMPATIBLE");
    }

    public static CompatibilityDecision incompatible(String reasonCode) {
        return new CompatibilityDecision(CompatibilityOutcome.INCOMPATIBLE, reasonCode);
    }

    public static CompatibilityDecision unavailable(String reasonCode) {
        return new CompatibilityDecision(CompatibilityOutcome.UNAVAILABLE, reasonCode);
    }
}