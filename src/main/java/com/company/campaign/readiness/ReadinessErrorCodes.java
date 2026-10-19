package com.company.campaign.readiness;

/**
 * Stable machine-readable error codes for the T25 readiness slice.
 *
 * <p>These values are part of the contract: an asset loader, a store adapter, a test or a host can branch
 * on them, so an existing code is never renamed or reused for a different meaning. A new failure mode gets
 * a new code.
 *
 * <p>The vocabulary is deliberately disjoint from {@link ReadinessBlockerCodes} and
 * {@link ReadinessReasonCodes}: an error code is thrown, while a blocker code labels an assessment. Nothing
 * here is ever a label, so the two cannot be confused by a reader or a metric.
 */
public final class ReadinessErrorCodes {

    /** A readiness context, level, environment or bounded field is missing or outside its grammar. */
    public static final String READINESS_CONTEXT_INVALID = "READINESS_CONTEXT_INVALID";

    /** An evidence record or submission is not a bounded machine contract. */
    public static final String READINESS_EVIDENCE_INVALID = "READINESS_EVIDENCE_INVALID";

    /** An evidence record names a requirement code outside the closed catalog. */
    public static final String READINESS_REQUIREMENT_UNKNOWN = "READINESS_REQUIREMENT_UNKNOWN";

    /** An evidence record names a reason code outside the closed reason catalog. */
    public static final String READINESS_REASON_UNKNOWN = "READINESS_REASON_UNKNOWN";

    /** An evidence origin is not admissible for the requirement it claims, or contradicts its scope. */
    public static final String READINESS_EVIDENCE_ORIGIN_REJECTED = "READINESS_EVIDENCE_ORIGIN_REJECTED";

    /** An evidence record's declared self-hash is not the hash of its own material. */
    public static final String READINESS_EVIDENCE_HASH_MISMATCH = "READINESS_EVIDENCE_HASH_MISMATCH";

    /** An assessment request or an assessment record is not a bounded contract. */
    public static final String READINESS_ASSESSMENT_INVALID = "READINESS_ASSESSMENT_INVALID";

    /** A bounded store read or write would exceed its configured limit. */
    public static final String READINESS_STORE_LIMIT_EXCEEDED = "READINESS_STORE_LIMIT_EXCEEDED";

    /** The persistence boundary could not complete; nothing was committed. */
    public static final String READINESS_STORE_UNAVAILABLE = "READINESS_STORE_UNAVAILABLE";

    /** An audit record is not a bounded, redacted contract. */
    public static final String READINESS_AUDIT_INVALID = "READINESS_AUDIT_INVALID";

    /** A migration asset set is malformed, unordered, duplicated or not expand-only. */
    public static final String READINESS_MIGRATION_INVALID = "READINESS_MIGRATION_INVALID";

    /** An ordered migration asset's checksum does not match the pinned contract. */
    public static final String READINESS_MIGRATION_CHECKSUM_MISMATCH = "READINESS_MIGRATION_CHECKSUM_MISMATCH";

    /** A migration asset contains a destructive statement. */
    public static final String READINESS_MIGRATION_DESTRUCTIVE = "READINESS_MIGRATION_DESTRUCTIVE";

    private ReadinessErrorCodes() {
    }
}