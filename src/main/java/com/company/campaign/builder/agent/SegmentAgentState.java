package com.company.campaign.builder.agent;

/**
 * The closed state machine of the formal T11 Campaign Builder Agent adapter.
 *
 * <p>The happy path is fixed:
 * {@code UNDERSTANDING → DISCOVERING → RESOLVING → GENERATING_DSL → VALIDATING → PLANNING → PREVIEWING →
 * READY_FOR_PREVIEW}. The terminal outcomes are exactly {@code NEEDS_INPUT}, {@code UNSUPPORTED},
 * {@code DENIED}, {@code INVALID} and {@code READY_FOR_PREVIEW}.
 *
 * <p>The user-save terminal state of the wider v1.3 flow is deliberately absent because T16/T17 are not
 * implemented, and no success state names a saved, applied, published, active or live-ready outcome: this
 * adapter produces no saved, applied or published artifact.
 */
public enum SegmentAgentState {

    /** Build the structured Intent from bounded conversation presentation data. */
    UNDERSTANDING,

    /**
     * Discover the permitted Metadata through the closed Tool boundary.
     *
     * <p>The bounded safe model request is built from the exact projection this phase authorized, so it is
     * issued inside this phase as well: the Metadata discovery Tool is always admitted before any projection
     * can reach the model, and the model output is parsed before the run advances to {@link #RESOLVING}.
     */
    DISCOVERING,

    /** Deterministically resolve or clarify the Intent through T10. */
    RESOLVING,

    /** Compile only a fully resolved Intent into the host-owned T05 DSL. */
    GENERATING_DSL,

    /** Validate and canonicalize the document through T07. */
    VALIDATING,

    /** Plan through T08. */
    PLANNING,

    /** Run the genuine T09C Safe Preview. */
    PREVIEWING,

    /** Terminal success: safe Preview and grounded explanation are ready. */
    READY_FOR_PREVIEW,

    /** Terminal: genuine T10 clarification is required; nothing was generated. */
    NEEDS_INPUT,

    /** Terminal: the requested capability does not exist in this system. */
    UNSUPPORTED,

    /** Terminal: the capability exists but this principal or DataScope cannot use it. */
    DENIED,

    /** Terminal: the request was understood but violates a contract. */
    INVALID;

    /** True for the five terminal outcomes and false for every in-flight state. */
    public boolean isTerminal() {
        return this == READY_FOR_PREVIEW || this == NEEDS_INPUT || this == UNSUPPORTED
                || this == DENIED || this == INVALID;
    }
}