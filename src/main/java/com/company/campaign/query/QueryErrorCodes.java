package com.company.campaign.query;

/**
 * Stable machine-readable failure codes owned by the T08 Query AST and Semantic Query Planner.
 *
 * <p>These values are part of the wire contract: a host, API error envelope or later task branches on them, so
 * an existing code is never renamed or reused for a different meaning and a new failure mode gets a new code.
 * Every code's message and details are fixed prose and closed detail names; no code ever echoes a payload
 * value, an operand, a tenant/program/scope identifier, a definition identifier or a thrown cause.
 */
public final class QueryErrorCodes {

    /** A required public planning input — result, snapshot or trusted context — was null. */
    public static final String QUERY_INPUT_MISSING = "SEGMENT_QUERY_INPUT_MISSING";

    /** The supplied validation result is not a genuine successful T07 result with a canonical artifact. */
    public static final String QUERY_VALIDATION_RESULT_INVALID = "SEGMENT_QUERY_VALIDATION_RESULT_INVALID";

    /** The declared canonical AST hash is not the canonical hash of the supplied canonical artifact. */
    public static final String QUERY_CANONICAL_HASH_MISMATCH = "SEGMENT_QUERY_CANONICAL_HASH_MISMATCH";

    /** A Metadata binding pin (snapshot ID, version, schema version or content hash) does not match. */
    public static final String QUERY_METADATA_BINDING_MISMATCH = "SEGMENT_QUERY_METADATA_BINDING_MISMATCH";

    /** The Metadata Snapshot owner (tenant/program) does not match the trusted execution context. */
    public static final String QUERY_METADATA_OWNER_MISMATCH = "SEGMENT_QUERY_METADATA_OWNER_MISMATCH";

    /** The trusted context pins a Metadata Snapshot other than the one the result and snapshot name. */
    public static final String QUERY_CONTEXT_SNAPSHOT_MISMATCH = "SEGMENT_QUERY_CONTEXT_SNAPSHOT_MISMATCH";

    /** The trusted time context is missing, unparseable or self-contradictory. */
    public static final String QUERY_TIME_CONTEXT_INVALID = "SEGMENT_QUERY_TIME_CONTEXT_INVALID";

    /** An accepted upstream artifact combines semantics T08 cannot plan safely. */
    public static final String QUERY_UNSUPPORTED_SEMANTIC_COMBINATION =
            "SEGMENT_QUERY_UNSUPPORTED_SEMANTIC_COMBINATION";

    /** Resolving a window's concrete boundaries overflowed the representable instant range. */
    public static final String QUERY_WINDOW_OVERFLOW = "SEGMENT_QUERY_WINDOW_OVERFLOW";

    /** An internal plan invariant is not satisfied; no partially consistent plan is ever published. */
    public static final String QUERY_PLAN_INCONSISTENT = "SEGMENT_QUERY_PLAN_INCONSISTENT";

    /**
     * A resolved plan dependency is not authorized for the exact trusted context being planned. Denial,
     * not-found, DataScope denial or unavailability, an invalid request, a missing decision and any thrown
     * boundary failure all share this one stable non-disclosing code.
     */
    public static final String QUERY_AUTHORIZATION_DENIED = "SEGMENT_QUERY_AUTHORIZATION_DENIED";

    /** A public codec or AST boundary received a null or malformed required input. */
    public static final String QUERY_AST_INVALID = "SEGMENT_QUERY_AST_INVALID";

    /**
     * A reference-bearing validation result was planned without the mandatory trusted reference context.
     *
     * <p>Planning a reference needs the host-owned catalog, program policy, DataScope, availability and
     * retention ports. Without them there is no trusted fact to re-resolve, so planning fails closed rather
     * than freezing an unproven dependency or degrading to "no reference at all".
     */
    public static final String QUERY_REFERENCE_CONTEXT_REQUIRED = "SEGMENT_QUERY_REFERENCE_CONTEXT_REQUIRED";

    /**
     * Re-resolving a reference at plan time disagreed with the accepted validation-time graph.
     *
     * <p>An owner, program-policy, permission, availability, DataScope or version/hash change between
     * validation and planning makes the frozen graph unreproducible. One stable non-disclosing code covers
     * every one of those causes, so a caller can never tell which referenced version moved or why.
     */
    public static final String QUERY_REFERENCE_DRIFT = "SEGMENT_QUERY_REFERENCE_DRIFT";

    /**
     * The execution guard refused a reference-bearing plan at execution time.
     *
     * <p>A denied segment permission, a withdrawn or hard-deleted version, a changed version identity, a
     * released retention hold or a moved effective DataScope all share this one code. The {@code invariant}
     * detail names which re-check failed; nothing else about the referenced version is disclosed.
     */
    public static final String QUERY_REFERENCE_EXECUTION_DENIED = "SEGMENT_QUERY_REFERENCE_EXECUTION_DENIED";

    private QueryErrorCodes() {
    }
}