package com.company.campaign.builder.segment;

/**
 * The closed set of stable stage-level failures of the development AI-to-SQL service.
 *
 * <p>A failure is reported by exactly one of these codes plus the terminal disposition. No code, message or
 * result ever carries a provider payload, a trusted context, a tenant/user/DataScope value, a raw member id,
 * a hidden Metadata definition or a database connection fact.
 */
public final class DevelopmentAiToSqlErrorCodes {

    /** The model returned untrusted output that failed the strict adapter boundary. */
    public static final String MODEL_OUTPUT_REJECTED = "SEGMENT_AI_SQL_MODEL_OUTPUT_REJECTED";

    /** Deterministic resolution did not reach {@code RESOLVED}. */
    public static final String RESOLUTION_INCOMPLETE = "SEGMENT_AI_SQL_RESOLUTION_INCOMPLETE";

    /** Deterministic compilation of the resolved Intent failed. */
    public static final String COMPILATION_FAILED = "SEGMENT_AI_SQL_COMPILATION_FAILED";

    /** T07 validation rejected the canonical document. */
    public static final String VALIDATION_REJECTED = "SEGMENT_AI_SQL_VALIDATION_REJECTED";

    /** T07 authorization denied access to a required dependency. */
    public static final String AUTHORIZATION_DENIED = "SEGMENT_AI_SQL_AUTHORIZATION_DENIED";

    /** T08 planning failed. */
    public static final String PLANNING_FAILED = "SEGMENT_AI_SQL_PLANNING_FAILED";

    /** T09B SQL compilation failed closed against the exact server-owned catalog/policy/FX pins. */
    public static final String SQL_COMPILATION_FAILED = "SEGMENT_AI_SQL_COMPILATION_FAILED";

    private DevelopmentAiToSqlErrorCodes() {
    }
}