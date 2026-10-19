package com.company.campaign.action;

/**
 * The closed outcome vocabulary one Provider result may carry.
 *
 * <p>It is exactly the accepted v1.3 result semantics and deliberately distinguishes four things a caller must
 * never collapse: the effect is applied, the effect is definitively not applied, the effect is still pending
 * remotely, and the effect's remote state cannot be established at all. {@link #PENDING} and {@link #UNKNOWN}
 * both require reconciliation by key before any retry; neither is ever a failure.
 */
public enum ProviderOutcome {

    /** The effect is applied remotely; a bounded Provider reference must exist. */
    SUCCEEDED(true, false),

    /** The effect is definitively not applied; a bounded retry may be considered. */
    DEFINITELY_NOT_APPLIED(false, true),

    /** The effect is still pending remotely; reconciliation is required. */
    PENDING(false, false),

    /** The effect's remote state cannot be established; reconciliation is required. */
    UNKNOWN(false, false);

    private final boolean applied;
    private final boolean definitivelyNotApplied;

    ProviderOutcome(boolean applied, boolean definitivelyNotApplied) {
        this.applied = applied;
        this.definitivelyNotApplied = definitivelyNotApplied;
    }

    /** True when this outcome establishes that the remote effect exists. */
    public boolean applied() {
        return applied;
    }

    /** True when this outcome definitively establishes that no remote effect exists. */
    public boolean definitivelyNotApplied() {
        return definitivelyNotApplied;
    }

    /** True when this outcome leaves the remote state unresolved, so reconciliation is required. */
    public boolean requiresReconciliation() {
        return !applied && !definitivelyNotApplied;
    }
}