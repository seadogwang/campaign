package com.company.campaign.runtime;

/**
 * Every bounded machine failure code the T22 phase-A Runtime layer can produce.
 *
 * <p>Each is a stable uppercase code in the base contract's machine-code form, so a caller, an audit record or
 * a persisted Execution failure reason can be compared and asserted without parsing a message. None of them
 * ever carries a member value, a tenant value, a payload body, SQL, provider data or an unrestricted exception
 * message.
 */
public final class RuntimeErrorCodes {

    /** An immutable runtime record carries a version discriminator this build does not support. */
    public static final String RUNTIME_VERSION_UNSUPPORTED = "RUNTIME_VERSION_UNSUPPORTED";

    /** A bounded runtime input is absent, of the wrong type or of the wrong shape. */
    public static final String RUNTIME_INPUT_INVALID = "RUNTIME_INPUT_INVALID";

    /** A runtime collection, count or duration exceeds its frozen bound. */
    public static final String RUNTIME_BOUND_EXCEEDED = "RUNTIME_BOUND_EXCEEDED";

    /** The exact T21 source work item a delivery names is not held by the host source. */
    public static final String RUNTIME_SOURCE_NOT_FOUND = "RUNTIME_SOURCE_NOT_FOUND";

    /** A source work item does not match the hash it declares. */
    public static final String RUNTIME_SOURCE_HASH_MISMATCH = "RUNTIME_SOURCE_HASH_MISMATCH";

    /** A source work item does not bind the frozen definition or Campaign it claims to. */
    public static final String RUNTIME_BINDING_MISMATCH = "RUNTIME_BINDING_MISMATCH";

    /** An Inbox source identity is already bound to different hash material. */
    public static final String RUNTIME_INBOX_CONFLICT = "RUNTIME_INBOX_CONFLICT";

    /** An execution key is already bound to different hash material. */
    public static final String RUNTIME_EXECUTION_CONFLICT = "RUNTIME_EXECUTION_CONFLICT";

    /** The frozen full definition a work item identifies is not held by the host source. */
    public static final String RUNTIME_DEFINITION_NOT_FOUND = "RUNTIME_DEFINITION_NOT_FOUND";

    /** The frozen full definition does not match the hash the work item pins. */
    public static final String RUNTIME_DEFINITION_HASH_MISMATCH = "RUNTIME_DEFINITION_HASH_MISMATCH";

    /** The frozen base definition a full definition binds is not held by the host source. */
    public static final String RUNTIME_BASE_DEFINITION_NOT_FOUND = "RUNTIME_BASE_DEFINITION_NOT_FOUND";

    /** The frozen base definition does not match the checksum the full definition pins. */
    public static final String RUNTIME_BASE_DEFINITION_HASH_MISMATCH =
            "RUNTIME_BASE_DEFINITION_HASH_MISMATCH";

    /** The frozen base Manifest declares no audience Segment component, so no audience can be materialized. */
    public static final String RUNTIME_SEGMENT_PIN_MISSING = "RUNTIME_SEGMENT_PIN_MISSING";

    /** The frozen base Manifest declares no exact {@code governance.limit/1.0} component. */
    public static final String RUNTIME_LIMIT_MISSING = "RUNTIME_LIMIT_MISSING";

    /** The frozen {@code governance.limit/1.0} declaration is not a bounded positive whole number. */
    public static final String RUNTIME_LIMIT_INVALID = "RUNTIME_LIMIT_INVALID";

    /** The host audience evidence is older than the frozen freshness policy allows. */
    public static final String RUNTIME_AUDIENCE_STALE = "RUNTIME_AUDIENCE_STALE";

    /** The host audience query did not answer within its bound. */
    public static final String RUNTIME_AUDIENCE_TIMEOUT = "RUNTIME_AUDIENCE_TIMEOUT";

    /** The host audience query failed. */
    public static final String RUNTIME_AUDIENCE_QUERY_FAILED = "RUNTIME_AUDIENCE_QUERY_FAILED";

    /** The host audience query was refused for the caller's DataScope. */
    public static final String RUNTIME_AUDIENCE_SCOPE_REJECTED = "RUNTIME_AUDIENCE_SCOPE_REJECTED";

    /** The exact candidate count exceeds the frozen recipient limit; the set is never truncated. */
    public static final String RUNTIME_AUDIENCE_OVER_LIMIT = "RUNTIME_AUDIENCE_OVER_LIMIT";

    /** The host audience result claims a completeness its own evidence contradicts. */
    public static final String RUNTIME_AUDIENCE_EVIDENCE_INVALID = "RUNTIME_AUDIENCE_EVIDENCE_INVALID";

    /** The Execution is not in a state this phase-A operation may materialize. */
    public static final String RUNTIME_EXECUTION_NOT_MATERIALIZABLE = "RUNTIME_EXECUTION_NOT_MATERIALIZABLE";

    /** A compare-and-set expectation no longer matches the stored record's revision. */
    public static final String RUNTIME_STORE_CONFLICT = "RUNTIME_STORE_CONFLICT";

    /** A record is already stored under one identity with different hash material. */
    public static final String RUNTIME_STORE_IDENTITY_CONFLICT = "RUNTIME_STORE_IDENTITY_CONFLICT";

    /** A local reference store or its audit collaborator was injected to fail. */
    public static final String RUNTIME_STORE_UNAVAILABLE = "RUNTIME_STORE_UNAVAILABLE";

    /** An audit sink cannot atomically record every record in one runtime transaction. */
    public static final String RUNTIME_AUDIT_BATCH_UNSUPPORTED = "RUNTIME_AUDIT_BATCH_UNSUPPORTED";

    /** A requested state transition is not one the frozen state machine allows. */
    public static final String RUNTIME_TRANSITION_INVALID = "RUNTIME_TRANSITION_INVALID";

    /** The host source could not answer at all. */
    public static final String RUNTIME_SOURCE_UNAVAILABLE = "RUNTIME_SOURCE_UNAVAILABLE";

    /** A RuntimeTask a command names is not stored under its own tenant. */
    public static final String RUNTIME_TASK_NOT_FOUND = "RUNTIME_TASK_NOT_FOUND";

    /** One task already holds an unexpired lease, so it cannot be stolen. */
    public static final String RUNTIME_LEASE_HELD = "RUNTIME_LEASE_HELD";

    /** A lease duration is outside the bounded interval a claim may take. */
    public static final String RUNTIME_LEASE_INVALID = "RUNTIME_LEASE_INVALID";

    /** A completion names a lease token that is not the task's current lease. */
    public static final String RUNTIME_LEASE_TOKEN_MISMATCH = "RUNTIME_LEASE_TOKEN_MISMATCH";

    /** A Participant revision or current-node expectation no longer matches the stored record. */
    public static final String RUNTIME_PARTICIPANT_CAS_CONFLICT = "RUNTIME_PARTICIPANT_CAS_CONFLICT";

    /** Duplicate work was replayed with different material than the committed original. */
    public static final String RUNTIME_COMPLETION_CONFLICT = "RUNTIME_COMPLETION_CONFLICT";

    /** A frozen Journey node or its exact frozen payload component could not be re-proved. */
    public static final String RUNTIME_NODE_BINDING_MISMATCH = "RUNTIME_NODE_BINDING_MISMATCH";

    /** A frozen Journey declares no exact successor for the typed branch a node selected. */
    public static final String RUNTIME_EDGE_MISSING = "RUNTIME_EDGE_MISSING";

    /** A frozen Journey declares more than one successor for the typed branch a node selected. */
    public static final String RUNTIME_EDGE_AMBIGUOUS = "RUNTIME_EDGE_AMBIGUOUS";

    /** The host fact that a Condition predicate tests is unknown, and is never treated as FALSE. */
    public static final String RUNTIME_CONDITION_UNKNOWN = "RUNTIME_CONDITION_UNKNOWN";

    /** A Condition predicate failed closed with the frozen {@code onUnknown=ERROR} policy. */
    public static final String RUNTIME_CONDITION_FAILED_CLOSED = "RUNTIME_CONDITION_FAILED_CLOSED";

    /** A host fact value kind does not match the Condition predicate's declared value kind. */
    public static final String RUNTIME_CONDITION_TYPE_MISMATCH = "RUNTIME_CONDITION_TYPE_MISMATCH";

    /** A host fact value is not the canonical text of its declared T19 value kind. */
    public static final String RUNTIME_CONDITION_VALUE_INVALID = "RUNTIME_CONDITION_VALUE_INVALID";

    /** A Condition node's frozen configuration is not the exact T19 predicate shape. */
    public static final String RUNTIME_CONDITION_NODE_CONFIG_INVALID = "RUNTIME_CONDITION_NODE_CONFIG_INVALID";

    /** The host fact port did not answer within its bound. */
    public static final String RUNTIME_FACT_TIMEOUT = "RUNTIME_FACT_TIMEOUT";

    /** The host fact port could not answer at all. */
    public static final String RUNTIME_FACT_UNAVAILABLE = "RUNTIME_FACT_UNAVAILABLE";

    /** A frozen Wait node payload is not the exact T19 Flow Wait duration and anchor shape. */
    public static final String RUNTIME_WAIT_CONFIGURATION_INVALID = "RUNTIME_WAIT_CONFIGURATION_INVALID";

    /** A Wait anchored at the previous accepted action has no persisted accepted-action instant. */
    public static final String RUNTIME_WAIT_ANCHOR_MISSING = "RUNTIME_WAIT_ANCHOR_MISSING";

    /** The host-owned Campaign runtime/schedule state could not be verified for this work. */
    public static final String RUNTIME_CAMPAIGN_STATE_UNVERIFIABLE = "RUNTIME_CAMPAIGN_STATE_UNVERIFIABLE";

    /** Every bounded attempt of a task was spent, so the work is dead-lettered. */
    public static final String RUNTIME_ATTEMPTS_EXHAUSTED = "RUNTIME_ATTEMPTS_EXHAUSTED";

    /** A retry policy names an unbounded attempt count, backoff or an inconsistent backoff sequence. */
    public static final String RUNTIME_RETRY_POLICY_INVALID = "RUNTIME_RETRY_POLICY_INVALID";

    /** An Execution must not complete while one of its Participants is runnable, waiting or unresolved. */
    public static final String RUNTIME_AGGREGATE_INVALID = "RUNTIME_AGGREGATE_INVALID";

    private RuntimeErrorCodes() {
    }
}
