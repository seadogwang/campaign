package com.company.campaign.query.fixture;

/**
 * Stable machine-readable codes owned by the T09A deterministic fixture executor.
 *
 * <p>These values are part of the wire contract, so an existing code is never renamed or reused for a
 * different meaning and a new failure mode gets a new code. Every message is fixed prose and the only detail
 * a failure carries is a closed {@code invariant} name, so no fixture identifier, member identifier, payload
 * row, operand, tenant/program/scope value, currency or thrown cause is ever echoed outward.
 */
public final class FixtureErrorCodes {

    /** A required public executor/loader input — descriptor, plan, dataset, mapping or scope binding — was null. */
    public static final String FIXTURE_INPUT_MISSING = "SEGMENT_FIXTURE_INPUT_MISSING";

    /** A declared fixture payload does not exist or is not a regular file beneath the evaluation root. */
    public static final String FIXTURE_PAYLOAD_NOT_FOUND = "SEGMENT_FIXTURE_PAYLOAD_NOT_FOUND";

    /** A declared fixture payload exists but cannot be read as bytes. */
    public static final String FIXTURE_PAYLOAD_UNREADABLE = "SEGMENT_FIXTURE_PAYLOAD_UNREADABLE";

    /** A payload's bytes do not match the SHA-256 hash the descriptor declares for them. */
    public static final String FIXTURE_HASH_MISMATCH = "SEGMENT_FIXTURE_HASH_MISMATCH";

    /** A payload is malformed JSON, carries a duplicate key or declares a field of the wrong type. */
    public static final String FIXTURE_PAYLOAD_MALFORMED = "SEGMENT_FIXTURE_PAYLOAD_MALFORMED";

    /** A payload object declares a field outside the closed dataset shape. */
    public static final String FIXTURE_UNKNOWN_FIELD = "SEGMENT_FIXTURE_UNKNOWN_FIELD";

    /** A payload object omits a required dataset field. */
    public static final String FIXTURE_MISSING_FIELD = "SEGMENT_FIXTURE_MISSING_FIELD";

    /** A payload declares the same member, order, event or refund identifier more than once. */
    public static final String FIXTURE_DUPLICATE_ID = "SEGMENT_FIXTURE_DUPLICATE_ID";

    /** A payload row relates to a member, order or dataset row that does not exist. */
    public static final String FIXTURE_DANGLING_REFERENCE = "SEGMENT_FIXTURE_DANGLING_REFERENCE";

    /** A payload's declared identity, role, time context or dataset name contradicts its descriptor. */
    public static final String FIXTURE_IDENTITY_MISMATCH = "SEGMENT_FIXTURE_IDENTITY_MISMATCH";

    /** A fixture's declared time context or the plan's trusted time context is missing or self-contradictory. */
    public static final String FIXTURE_TIME_CONTEXT_INVALID = "SEGMENT_FIXTURE_TIME_CONTEXT_INVALID";

    /** A payload contains members outside, or omits members inside, the descriptor's declared member universe. */
    public static final String FIXTURE_UNIVERSE_MISMATCH = "SEGMENT_FIXTURE_UNIVERSE_MISMATCH";

    /** A server-owned fixture mapping is internally inconsistent and cannot be published. */
    public static final String FIXTURE_MAPPING_INVALID = "SEGMENT_FIXTURE_MAPPING_INVALID";

    /** A plan dependency has no exact mapping entry, or its entity/version/subject pin does not match one. */
    public static final String FIXTURE_MAPPING_MISSING = "SEGMENT_FIXTURE_MAPPING_MISSING";

    /** The plan, the fixture dataset or the trusted context disagree on a pinned value before evaluation. */
    public static final String FIXTURE_PLAN_MISMATCH = "SEGMENT_FIXTURE_PLAN_MISMATCH";

    /** No server-owned DataScope membership binding exists for the plan's mandatory DataScope. */
    public static final String FIXTURE_SCOPE_MISSING = "SEGMENT_FIXTURE_SCOPE_MISSING";

    /**
     * No local membership dataset answers for one exact frozen Audience Version pin.
     *
     * <p>An unknown membership set and an empty one are different facts, and conflating them would silently
     * evaluate a reference as "no members" instead of refusing. The lookup is keyed by the full pin — audience,
     * version identity and content hash — so a set registered for a different set of bytes can never answer.
     */
    public static final String FIXTURE_REFERENCE_MEMBERSHIP_MISSING =
            "SEGMENT_FIXTURE_REFERENCE_MEMBERSHIP_MISSING";

    /** A conversion is required but no explicit immutable FX-rate snapshot was supplied. */
    public static final String FIXTURE_FX_REQUIRED = "SEGMENT_FIXTURE_FX_REQUIRED";

    /** The supplied FX-rate snapshot is unbound to the plan or lacks a needed exact rate. */
    public static final String FIXTURE_FX_INVALID = "SEGMENT_FIXTURE_FX_INVALID";

    /** Amounts under a single-currency requirement span more than one currency, or a currency is inconsistent. */
    public static final String FIXTURE_CURRENCY_MISMATCH = "SEGMENT_FIXTURE_CURRENCY_MISMATCH";

    /** An accepted plan combination has no deterministic fixture semantics (for example an unmapped subject). */
    public static final String FIXTURE_UNSUPPORTED_SEMANTIC = "SEGMENT_FIXTURE_UNSUPPORTED_SEMANTIC";

    /** An internal execution result violated its own determinism, ordering or hash invariant. */
    public static final String FIXTURE_RESULT_INVALID = "SEGMENT_FIXTURE_RESULT_INVALID";

    private FixtureErrorCodes() {
    }
}