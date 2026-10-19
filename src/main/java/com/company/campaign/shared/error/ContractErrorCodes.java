package com.company.campaign.shared.error;

/**
 * Stable machine-readable error codes for base contracts.
 *
 * <p>These values are part of the wire contract: callers, tests and later task modules may branch on
 * them, so existing codes must never be renamed or reused for a different meaning. A new failure mode
 * gets a new code.
 */
public final class ContractErrorCodes {

    /** A schema version string is not a well-formed {@code major.minor} value. */
    public static final String SCHEMA_VERSION_MALFORMED = "SCHEMA_VERSION_MALFORMED";

    /** A schema version is well-formed but is not the exact version the contract requires. */
    public static final String SCHEMA_VERSION_INCOMPATIBLE = "SCHEMA_VERSION_INCOMPATIBLE";

    /** A content hash is not a well-formed {@code sha256:<64 lowercase hex>} value. */
    public static final String CONTENT_HASH_INVALID = "CONTENT_HASH_INVALID";

    /** A stored Plan reference is past its expiry instant. */
    public static final String PLAN_EXPIRED = "PLAN_EXPIRED";

    /** A JSON document contains the same object key more than once. */
    public static final String JSON_DUPLICATE_KEY = "JSON_DUPLICATE_KEY";

    /** A JSON document is syntactically invalid. */
    public static final String JSON_MALFORMED = "JSON_MALFORMED";

    /** A value cannot be represented in canonical JSON (for example NaN or infinity). */
    public static final String JSON_NON_CANONICAL_VALUE = "JSON_NON_CANONICAL_VALUE";

    /** A contract DTO received a property that is not part of the contract. */
    public static final String CONTRACT_UNKNOWN_FIELD = "CONTRACT_UNKNOWN_FIELD";

    /** A contract value failed its own format rule while being read. */
    public static final String CONTRACT_INVALID_VALUE = "CONTRACT_INVALID_VALUE";

    /** A model-supplied Tool input carried a field reserved for trusted host context or host-only commands. */
    public static final String TOOL_INPUT_RESERVED_FIELD = "TOOL_INPUT_RESERVED_FIELD";

    /** A Tool result envelope contradicts itself (for example a result value paired with a non-RESOLVED disposition). */
    public static final String TOOL_RESULT_INVARIANT = "TOOL_RESULT_INVARIANT";

    // T02 — trusted context, authorization and DataScope.

    /** A permission wire name does not match any of the closed campaign permission set. */
    public static final String PERMISSION_UNKNOWN = "PERMISSION_UNKNOWN";

    /** A verified identity does not belong to the membership it was paired with. */
    public static final String TRUSTED_IDENTITY_MISMATCH = "TRUSTED_IDENTITY_MISMATCH";

    /** A membership is present but not active, so no trusted context may be derived from it. */
    public static final String MEMBERSHIP_INACTIVE = "MEMBERSHIP_INACTIVE";

    /** A membership and its execution-context snapshot disagree on tenant, program, user or DataScope. */
    public static final String TRUSTED_CONTEXT_MISMATCH = "TRUSTED_CONTEXT_MISMATCH";

    /** A trusted context input is missing or malformed. */
    public static final String TRUSTED_CONTEXT_INVALID = "TRUSTED_CONTEXT_INVALID";

    /** The principal does not hold the permission a resource kind requires. */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    /** Non-disclosing outward failure used for both absent and cross-boundary resources. */
    public static final String RESOURCE_NOT_FOUND = "RESOURCE_NOT_FOUND";

    /** An authorization request is structurally invalid and cannot be evaluated. */
    public static final String AUTHORIZATION_INVALID_REQUEST = "AUTHORIZATION_INVALID_REQUEST";

    /** The DataScope policy refused the requested resource. */
    public static final String DATASCOPE_DENIED = "DATASCOPE_DENIED";

    /** No trustworthy DataScope decision was available; the request fails closed. */
    public static final String DATASCOPE_UNAVAILABLE = "DATASCOPE_UNAVAILABLE";

    /** A caller required a field that projection withheld. */
    public static final String PROJECTION_FIELD_DENIED = "PROJECTION_FIELD_DENIED";

    /** A payload exceeds the configured byte limit. */
    public static final String PAYLOAD_SIZE_LIMIT_EXCEEDED = "PAYLOAD_SIZE_LIMIT_EXCEEDED";

    /** A payload nests deeper than the configured depth limit. */
    public static final String PAYLOAD_DEPTH_LIMIT_EXCEEDED = "PAYLOAD_DEPTH_LIMIT_EXCEEDED";

    /** A payload contains a collection larger than the configured collection limit. */
    public static final String PAYLOAD_COLLECTION_LIMIT_EXCEEDED = "PAYLOAD_COLLECTION_LIMIT_EXCEEDED";

    /** Retention was asked to store a raw RESTRICTED payload. */
    public static final String UNSAFE_RETENTION_REQUEST = "UNSAFE_RETENTION_REQUEST";

    /** A retention request is structurally invalid. */
    public static final String RETENTION_INVALID_REQUEST = "RETENTION_INVALID_REQUEST";

    /**
     * An audit resource fingerprint is inconsistent with its decision, or is present on a decision that
     * must not carry one.
     */
    public static final String AUDIT_REFERENCE_INVALID = "AUDIT_REFERENCE_INVALID";

    /**
     * An audit event field (reason code, trusted identifier or duration) is missing or outside its bounded
     * grammar.
     */
    public static final String AUDIT_FIELD_INVALID = "AUDIT_FIELD_INVALID";

    private ContractErrorCodes() {
    }
}