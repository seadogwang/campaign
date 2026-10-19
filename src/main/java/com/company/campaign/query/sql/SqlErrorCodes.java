package com.company.campaign.query.sql;

/**
 * Stable machine-readable codes owned by the T09B storage catalog, closed SQL AST and PostgreSQL compiler.
 *
 * <p>These values are part of the wire contract: a host, an API error envelope or a later task branches on
 * them, so an existing code is never renamed or reused for a different meaning and a new failure mode gets a
 * new code. Every code's message and details are fixed prose and closed detail names; no code ever echoes a
 * catalog identifier, a tenant/program/scope value, an operand, a bind value, a rendered SQL fragment or a
 * thrown cause.
 */
public final class SqlErrorCodes {

    /** A required public compiler/catalog/policy/AST input was null. */
    public static final String SQL_INPUT_MISSING = "SEGMENT_SQL_INPUT_MISSING";

    /** A PostgreSQL schema/table/column identifier is outside the one allowlisted identifier grammar. */
    public static final String SQL_IDENTIFIER_INVALID = "SEGMENT_SQL_IDENTIFIER_INVALID";

    /** A server-owned storage catalog entry is internally inconsistent and cannot be published. */
    public static final String SQL_CATALOG_INVALID = "SEGMENT_SQL_CATALOG_INVALID";

    /** Two server-owned catalog entries claim the same exact ID/subject/entity-version lookup key. */
    public static final String SQL_MAPPING_CONFLICT = "SEGMENT_SQL_MAPPING_CONFLICT";

    /** A plan dependency has no exact storage catalog entry, or its pin does not match one. */
    public static final String SQL_MAPPING_MISSING = "SEGMENT_SQL_MAPPING_MISSING";

    /** The plan, its trusted context and its own resolved facts contradict one another before compilation. */
    public static final String SQL_PLAN_MISMATCH = "SEGMENT_SQL_PLAN_MISMATCH";

    /** The supplied FX binding or catalog FX strategy cannot represent the required conversion exactly. */
    public static final String SQL_FX_INVALID = "SEGMENT_SQL_FX_INVALID";

    /** An accepted plan combination has no exact allowlisted PostgreSQL lowering. */
    public static final String SQL_UNSUPPORTED_SEMANTIC = "SEGMENT_SQL_UNSUPPORTED_SEMANTIC";

    /** A bounded compile-policy limit — IN-list width, parameter count or subquery depth — was exceeded. */
    public static final String SQL_LIMIT_EXCEEDED = "SEGMENT_SQL_LIMIT_EXCEEDED";

    /** A public SQL AST node received a null or malformed required input. */
    public static final String SQL_AST_INVALID = "SEGMENT_SQL_AST_INVALID";

    /** A compiled artifact violated its own coherence, hash or non-forgeability invariant. */
    public static final String SQL_ARTIFACT_INVALID = "SEGMENT_SQL_ARTIFACT_INVALID";

    /**
     * A reference-bearing plan was compiled without the mandatory execution guard and its trusted context.
     *
     * <p>SQL generation must not be able to weaken or remove the execution-time re-check, so compiling an
     * Existing Audience Reference without a complete, host-built {@code ReferenceExecutionCheck} fails closed
     * instead of emitting membership SQL that nothing verified. A plan with no reference is unaffected.
     */
    public static final String SQL_REFERENCE_GUARD_REQUIRED = "SEGMENT_SQL_REFERENCE_GUARD_REQUIRED";

    private SqlErrorCodes() {
    }
}