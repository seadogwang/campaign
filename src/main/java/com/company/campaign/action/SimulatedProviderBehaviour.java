package com.company.campaign.action;

/**
 * The closed, deterministic behaviours one simulated Provider can reproduce.
 *
 * <p>Every value here exists because the accepted reliability requirements name it, and none of them is
 * derivable from a random source: the same key under the same behaviour always plays the same sequence, so a
 * test proves a property rather than observing a sample.
 */
public enum SimulatedProviderBehaviour {

    /** The first submission applies remotely and reports it immediately. */
    IMMEDIATE_SUCCESS("PROVIDER_APPLIED"),

    /** The first submission is definitively rejected and never applies. */
    DEFINITIVE_REJECTION("PROVIDER_REJECTED"),

    /** The first submission is still pending remotely and applies after one reconciliation. */
    PENDING_THEN_SUCCESS("PROVIDER_PENDING"),

    /** The first submission leaves the remote state unknown and is proven not applied on reconciliation. */
    UNKNOWN_THEN_NOT_APPLIED("PROVIDER_UNKNOWN"),

    /**
     * The effect succeeds remotely but the response times out.
     *
     * <p>This is the canonical uncertain case: the remote effect exists, the caller learns nothing, and only a
     * reconciliation by key can establish it.
     */
    REMOTE_SUCCESS_RESPONSE_TIMEOUT("PROVIDER_RESPONSE_TIMEOUT"),

    /** The effect stays pending for several reconciliations before it applies. */
    DELAYED_RECONCILIATION("PROVIDER_PENDING"),

    /** A second submission of the same key under different request material is refused as a conflict. */
    CHANGED_REQUEST_HASH_CONFLICT("PROVIDER_REQUEST_CONFLICT");

    private final String reasonCode;

    SimulatedProviderBehaviour(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    /** The bounded machine reason code this behaviour reports on its own responses. */
    public String reasonCode() {
        return reasonCode;
    }

    /** True when this behaviour applies the remote effect before the caller learns anything. */
    public boolean appliesBeforeResponding() {
        return this == IMMEDIATE_SUCCESS || this == REMOTE_SUCCESS_RESPONSE_TIMEOUT;
    }

    /** True when this behaviour leaves the remote state unresolved and requires reconciliation. */
    public boolean requiresReconciliation() {
        return this == PENDING_THEN_SUCCESS || this == UNKNOWN_THEN_NOT_APPLIED
                || this == DELAYED_RECONCILIATION;
    }
}