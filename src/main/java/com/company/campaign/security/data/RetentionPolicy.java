package com.company.campaign.security.data;

/**
 * Port that decides how long, and in what form, a classified payload may be retained.
 *
 * <p>The default implementation in {@link DefaultRetentionPolicy} refuses raw {@code RESTRICTED} storage.
 * A caller that must fail closed rather than inspect the decision can use {@link #requireRetainable}.
 */
@FunctionalInterface
public interface RetentionPolicy {

    RetentionDecision decide(RetentionRequest request);

    /** Throws {@link UnsafeRetentionException} when the request is not allowed. */
    default void requireRetainable(RetentionRequest request) {
        RetentionDecision decision = decide(request);
        if (!decision.allowed()) {
            throw new UnsafeRetentionException(decision.reasonCode(), decision.reasonCode());
        }
    }
}