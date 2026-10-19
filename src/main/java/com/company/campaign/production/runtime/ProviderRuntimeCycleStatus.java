package com.company.campaign.production.runtime;

/**
 * The closed, bounded status of the last production cycle.
 *
 * <p>It is deliberately four states with no free-form text: a snapshot can publish which one applies without
 * disclosing a tenant, a work item, a failure message or any provider value.
 */
public enum ProviderRuntimeCycleStatus {

    /** No cycle has run yet since the host started. */
    NEVER_RUN,

    /** Every item the cycle selected was processed without an isolated failure. */
    COMPLETED,

    /** The cycle completed but isolated at least one failed item, or could not validate its tenant list. */
    DEGRADED,

    /** The cycle could not run at all because its own bounded inputs were unusable. */
    FAILED;

    /** True when a cycle has actually run. */
    public boolean ran() {
        return this != NEVER_RUN;
    }
}