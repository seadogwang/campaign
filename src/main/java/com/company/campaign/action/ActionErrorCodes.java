package com.company.campaign.action;

/**
 * Every bounded machine failure code the T23 external-action layer can produce.
 *
 * <p>Each is a stable uppercase code in the base contract's machine-code form, so a caller, an audit record or
 * a persisted Action reason can be compared and asserted without parsing a message. None of them ever carries a
 * member value, a tenant value, a recipient address, a template body, a reward secret, a Provider token, SQL or
 * an unrestricted exception message.
 */
public final class ActionErrorCodes {

    /** An immutable action record carries a version discriminator this build does not support. */
    public static final String ACTION_VERSION_UNSUPPORTED = "ACTION_VERSION_UNSUPPORTED";

    /** A bounded action input is absent, of the wrong type or of the wrong shape. */
    public static final String ACTION_INPUT_INVALID = "ACTION_INPUT_INVALID";

    /** A runtime collection, count, amount or duration exceeds its frozen bound. */
    public static final String ACTION_BOUND_EXCEEDED = "ACTION_BOUND_EXCEEDED";

    /** The exact persisted handoff a command names is not held by the host. */
    public static final String ACTION_HANDOFF_NOT_FOUND = "ACTION_HANDOFF_NOT_FOUND";

    /** A handoff does not bind the Execution, Participant, node or frozen payload it claims to. */
    public static final String ACTION_HANDOFF_BINDING_MISMATCH = "ACTION_HANDOFF_BINDING_MISMATCH";

    /** Two related immutable records do not witness exactly the same owner, pins and hashes. */
    public static final String ACTION_BINDING_MISMATCH = "ACTION_BINDING_MISMATCH";

    /** The frozen full or base definition a handoff rests on is not held by the host. */
    public static final String ACTION_DEFINITION_NOT_FOUND = "ACTION_DEFINITION_NOT_FOUND";

    /** A frozen definition does not witness exactly the pins the Execution recorded. */
    public static final String ACTION_DEFINITION_HASH_MISMATCH = "ACTION_DEFINITION_HASH_MISMATCH";

    /** The frozen base Manifest declares no exact component for a node's stable logical identity. */
    public static final String ACTION_COMPONENT_MISSING = "ACTION_COMPONENT_MISSING";

    /** A frozen MESSAGE or REWARD component is not the exact payload, capability or version the node names. */
    public static final String ACTION_COMPONENT_BINDING_MISMATCH = "ACTION_COMPONENT_BINDING_MISMATCH";

    /** A frozen reward or message component carries no resolved catalog reference at all. */
    public static final String ACTION_CATALOG_REFERENCE_MISSING = "ACTION_CATALOG_REFERENCE_MISSING";

    /** A frozen catalog reference does not witness the exact entity kind, identity or version it claims. */
    public static final String ACTION_CATALOG_REFERENCE_MISMATCH = "ACTION_CATALOG_REFERENCE_MISMATCH";

    /** The frozen base Manifest declares no exact {@code governance.limit/1.0} component. */
    public static final String ACTION_GOVERNANCE_LIMIT_MISSING = "ACTION_GOVERNANCE_LIMIT_MISSING";

    /** A frozen {@code governance.limit/1.0} declaration is not the exact bounded shape this contract needs. */
    public static final String ACTION_GOVERNANCE_LIMIT_INVALID = "ACTION_GOVERNANCE_LIMIT_INVALID";

    /** The host recipient resolver could not name a bounded recipient target for this action. */
    public static final String ACTION_RECIPIENT_UNRESOLVED = "ACTION_RECIPIENT_UNRESOLVED";

    /** A business idempotency key is already bound to material that is not this action's. */
    public static final String ACTION_IDEMPOTENCY_CONFLICT = "ACTION_IDEMPOTENCY_CONFLICT";

    /** No action is recorded under the named business idempotency key. */
    public static final String ACTION_IDEMPOTENCY_NOT_FOUND = "ACTION_IDEMPOTENCY_NOT_FOUND";

    /** A request hash is not the canonical hash of the exact frozen material it claims to cover. */
    public static final String ACTION_REQUEST_HASH_MISMATCH = "ACTION_REQUEST_HASH_MISMATCH";

    /** The provider returned no capability evidence at all. */
    public static final String ACTION_PROVIDER_CAPABILITY_MISSING = "ACTION_PROVIDER_CAPABILITY_MISSING";

    /** The provider capability evidence declares no business-key idempotency or no reliable reconciliation. */
    public static final String ACTION_PROVIDER_CAPABILITY_UNSUPPORTED = "ACTION_PROVIDER_CAPABILITY_UNSUPPORTED";

    /** The provider capability evidence names another owner, kind or contract than this action's. */
    public static final String ACTION_PROVIDER_CAPABILITY_MISMATCH = "ACTION_PROVIDER_CAPABILITY_MISMATCH";

    /** A provider call was refused, threw or could not be reached, so its remote outcome is unknown. */
    public static final String ACTION_PROVIDER_UNAVAILABLE = "ACTION_PROVIDER_UNAVAILABLE";

    /** A provider effect may have been applied remotely and this build cannot confirm it by key. */
    public static final String ACTION_PROVIDER_OUTCOME_UNKNOWN = "ACTION_PROVIDER_OUTCOME_UNKNOWN";

    /** A requested action transition is not one the frozen state machine allows. */
    public static final String ACTION_TRANSITION_INVALID = "ACTION_TRANSITION_INVALID";

    /** A terminal action is immutable and cannot be moved again. */
    public static final String ACTION_TERMINAL_IMMUTABLE = "ACTION_TERMINAL_IMMUTABLE";

    /** The frozen reward budget would be exceeded, so the action is refused rather than truncated. */
    public static final String ACTION_BUDGET_LIMITED = "ACTION_BUDGET_LIMITED";

    /** A budget declaration or reservation is not the exact bounded shape this contract needs. */
    public static final String ACTION_BUDGET_INVALID = "ACTION_BUDGET_INVALID";

    /** A budget ledger or reservation is already bound to different material. */
    public static final String ACTION_BUDGET_CONFLICT = "ACTION_BUDGET_CONFLICT";

    /** No durable ActionTask is stored under the named tenant and task identity. */
    public static final String ACTION_TASK_NOT_FOUND = "ACTION_TASK_NOT_FOUND";

    /** One action task already holds an unexpired lease, so it cannot be stolen. */
    public static final String ACTION_LEASE_HELD = "ACTION_LEASE_HELD";

    /** An action lease duration is outside the bounded interval a claim may take. */
    public static final String ACTION_LEASE_INVALID = "ACTION_LEASE_INVALID";

    /** A completion names a lease token that is not the task's current lease. */
    public static final String ACTION_LEASE_TOKEN_MISMATCH = "ACTION_LEASE_TOKEN_MISMATCH";

    /** A compare-and-set expectation no longer matches the stored record's revision. */
    public static final String ACTION_STORE_CONFLICT = "ACTION_STORE_CONFLICT";

    /** A record is already stored under one identity with different material. */
    public static final String ACTION_STORE_IDENTITY_CONFLICT = "ACTION_STORE_IDENTITY_CONFLICT";

    /** An exact duplicate completion was replayed with different material than the committed original. */
    public static final String ACTION_COMPLETION_CONFLICT = "ACTION_COMPLETION_CONFLICT";

    /** Every bounded submission attempt was spent. */
    public static final String ACTION_SUBMISSION_ATTEMPTS_EXHAUSTED = "ACTION_SUBMISSION_ATTEMPTS_EXHAUSTED";

    /** Every bounded verification attempt was spent without settling the remote outcome. */
    public static final String ACTION_VERIFICATION_EXHAUSTED = "ACTION_VERIFICATION_EXHAUSTED";

    /** An action is unresolved and requires an explicit reconciliation before anything else may move it. */
    public static final String ACTION_RECONCILIATION_REQUIRED = "ACTION_RECONCILIATION_REQUIRED";

    /** A manual retry is not permitted by the persisted evidence, the state or the attempt limits. */
    public static final String ACTION_RETRY_NOT_PERMITTED = "ACTION_RETRY_NOT_PERMITTED";

    /** The caller holds no {@code campaign.operate} authorization for a host-only operation. */
    public static final String ACTION_OPERATION_FORBIDDEN = "ACTION_OPERATION_FORBIDDEN";

    /** No actionable resource is visible to this caller; absent and cross-owner resources answer alike. */
    public static final String ACTION_NOT_FOUND = "ACTION_NOT_FOUND";

    /** The host-owned Campaign activation projection could not be verified. */
    public static final String ACTION_CAMPAIGN_STATE_UNVERIFIABLE = "ACTION_CAMPAIGN_STATE_UNVERIFIABLE";

    /** A bounded action retry policy is unbounded or inconsistent. */
    public static final String ACTION_POLICY_INVALID = "ACTION_POLICY_INVALID";

    /** An audit sink cannot atomically record every record in one action transaction. */
    public static final String ACTION_AUDIT_BATCH_UNSUPPORTED = "ACTION_AUDIT_BATCH_UNSUPPORTED";

    /** A local reference store or its audit collaborator was injected to fail. */
    public static final String ACTION_STORE_UNAVAILABLE = "ACTION_STORE_UNAVAILABLE";

    private ActionErrorCodes() {
    }
}