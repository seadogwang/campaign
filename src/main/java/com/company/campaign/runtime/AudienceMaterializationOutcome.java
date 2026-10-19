package com.company.campaign.runtime;

/**
 * The closed outcome of one host audience materialization query.
 *
 * <p>Exactly one outcome is {@link #COMPLETE}: only then may the result claim a complete, distinct, bounded
 * member set whose count agrees with the returned set. Every other outcome is a bounded failure the consumer
 * must turn into a {@code FAILED} Execution with zero snapshot, enrollment, Participant and task writes — a
 * partial or truncated answer is never representable, because {@code COMPLETE} is the only outcome that carries
 * a member set at all.
 */
public enum AudienceMaterializationOutcome {

    /** The host returned a complete, distinct, bounded member set plus exact freshness evidence. */
    COMPLETE(MaterializationFailure.NONE),

    /** The host evidence is older than the frozen freshness policy allows. */
    DATA_STALE(MaterializationFailure.STALE),

    /** The host query did not answer within its bound. */
    TIMEOUT(MaterializationFailure.TIMEOUT),

    /** The host query failed. */
    QUERY_FAILED(MaterializationFailure.QUERY_FAILED),

    /** The host refused the query for the requesting DataScope. */
    SCOPE_REJECTED(MaterializationFailure.SCOPE_REJECTED),

    /** The host cannot return a complete set within the declared recipient limit; nothing is truncated. */
    OVER_LIMIT(MaterializationFailure.OVER_LIMIT),

    /** The host result contradicts its own completeness or freshness evidence. */
    EVIDENCE_INVALID(MaterializationFailure.EVIDENCE_INVALID);

    private final MaterializationFailure failure;

    AudienceMaterializationOutcome(MaterializationFailure failure) {
        this.failure = failure;
    }

    /** True when the result carries a complete member set. */
    public boolean isComplete() {
        return this == COMPLETE;
    }

    /** The bounded Execution failure reason an accepted materialization records for this outcome. */
    public MaterializationFailure failure() {
        return failure;
    }

    /**
     * The closed failure reasons an Execution stores, mapped one-to-one from the non-complete outcomes.
     *
     * <p>{@link #NONE} is not a failure: it is the reason a successful Execution carries.
     */
    public enum MaterializationFailure {

        NONE(RuntimeErrorCodes.RUNTIME_INPUT_INVALID),
        STALE(RuntimeErrorCodes.RUNTIME_AUDIENCE_STALE),
        TIMEOUT(RuntimeErrorCodes.RUNTIME_AUDIENCE_TIMEOUT),
        QUERY_FAILED(RuntimeErrorCodes.RUNTIME_AUDIENCE_QUERY_FAILED),
        SCOPE_REJECTED(RuntimeErrorCodes.RUNTIME_AUDIENCE_SCOPE_REJECTED),
        OVER_LIMIT(RuntimeErrorCodes.RUNTIME_AUDIENCE_OVER_LIMIT),
        EVIDENCE_INVALID(RuntimeErrorCodes.RUNTIME_AUDIENCE_EVIDENCE_INVALID);

        private final String code;

        MaterializationFailure(String code) {
            this.code = code;
        }

        /** The exact bounded machine code this failure reason is persisted as. */
        public String code() {
            return code;
        }
    }
}