package com.company.campaign.production.model;

/**
 * The injected, bounded backoff strategy of the production model adapter's retry policy.
 *
 * <p>It is a port so the adapter's core logic never sleeps, never reads the ambient clock and never derives a
 * delay of its own: the host injects the strategy, a test injects a deterministic recording strategy, and the
 * adapter only asks for the bounded wait before a retry it has already decided to attempt.
 */
@FunctionalInterface
public interface ProductionModelBackoff {

    /**
     * Awaits the bounded backoff before the given retry attempt.
     *
     * @param attempt the one-based number of the attempt about to run; never {@code 0}
     */
    void await(int attempt);

    /** The inert strategy: it waits for nothing, for a caller that supplies no backoff. */
    static ProductionModelBackoff none() {
        return attempt -> {
        };
    }
}