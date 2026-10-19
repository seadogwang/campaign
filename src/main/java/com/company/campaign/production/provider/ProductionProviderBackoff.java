package com.company.campaign.production.provider;

/**
 * The one bounded wait strategy a reconciliation query uses before an already-decided retry.
 *
 * <p>It is injected rather than hard-coded so the adapter's core logic holds no sleep, no clock and no
 * randomness: the adapter decides whether another attempt is permitted, and this strategy decides how long to
 * wait before it. It is used for reconciliation queries only — a submission is never retried inside the adapter,
 * because a possibly-applied remote effect must be reconciled by its original key first.
 */
@FunctionalInterface
public interface ProductionProviderBackoff {

    /** Waits the bounded delay of the given one-based attempt; an interruption fails the retry closed. */
    void await(int attempt);

    /** The inert strategy: it never waits, for a caller that manages its own timing. */
    static ProductionProviderBackoff none() {
        return attempt -> {
        };
    }
}