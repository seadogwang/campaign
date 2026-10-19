package com.company.campaign.action;

import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.runtime.RuntimeVersions;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Every version discriminator, closed code and frozen bound owned by the T23 external-action layer.
 *
 * <p>Each value here is part of an immutable action record's canonical hash — an {@code ActionExecution}, an
 * {@code ActionAttempt}, an {@code ActionTask}, an {@code ActionIdempotencyRecord}, a {@code BudgetReservation},
 * a {@code BudgetLedger}, a Provider capability/request/result record or an action Outbox/Audit record — so
 * changing any of them is a new version rather than a silent reinterpretation of already-frozen bytes.
 *
 * <p>Nothing here restates an accepted T19–T22 discriminator as a private copy: the frozen governance-limit
 * capability it resolves reward budget from, the runtime schema version its handoffs carry and the frozen node
 * capability codes it binds to are read from the records that own them.
 */
public final class ActionVersions {

    /** The immutable ActionExecution document format version. */
    public static final String ACTION_EXECUTION_FORMAT_VERSION = "campaign-action-execution/1.0";

    /** The immutable ActionAttempt document format version. */
    public static final String ACTION_ATTEMPT_FORMAT_VERSION = "campaign-action-attempt/1.0";

    /** The immutable durable ActionTask document format version. */
    public static final String ACTION_TASK_FORMAT_VERSION = "campaign-action-task/1.0";

    /** The immutable ActionIdempotencyRecord document format version. */
    public static final String ACTION_IDEMPOTENCY_FORMAT_VERSION = "campaign-action-idempotency/1.0";

    /** The immutable immutable-request document format version. */
    public static final String ACTION_REQUEST_FORMAT_VERSION = "campaign-action-request/1.0";

    /** The immutable BudgetReservation document format version. */
    public static final String BUDGET_RESERVATION_FORMAT_VERSION = "campaign-budget-reservation/1.0";

    /** The immutable aggregate BudgetLedger projection document format version. */
    public static final String BUDGET_LEDGER_FORMAT_VERSION = "campaign-budget-ledger/1.0";

    /** The immutable Provider capability-evidence document format version. */
    public static final String PROVIDER_CAPABILITY_FORMAT_VERSION = "campaign-provider-capability/1.0";

    /** The immutable bounded Provider request document format version. */
    public static final String PROVIDER_REQUEST_FORMAT_VERSION = "campaign-provider-request/1.0";

    /** The immutable Provider result/evidence document format version. */
    public static final String PROVIDER_RESULT_FORMAT_VERSION = "campaign-provider-result/1.0";

    /** The immutable action Outbox/Audit record document format version. */
    public static final String ACTION_AUDIT_FORMAT_VERSION = "campaign-action-outbox/1.0";

    /** The immutable aggregate action-result counters projection document format version. */
    public static final String RESULT_COUNTERS_FORMAT_VERSION = "campaign-action-result-counters/1.0";

    /** The deterministic business-idempotency-key derivation version. */
    public static final String IDEMPOTENCY_KEY_VERSION = "campaign-action-idempotency-key/1.0";

    /** The deterministic ActionExecution identity derivation version. */
    public static final String ACTION_ID_VERSION = "campaign-action-id/1.0";

    /** The deterministic durable ActionTask identity derivation version. */
    public static final String ACTION_TASK_ID_VERSION = "campaign-action-task-id/1.0";

    /** The deterministic ActionAttempt identity derivation version. */
    public static final String ACTION_ATTEMPT_ID_VERSION = "campaign-action-attempt-id/1.0";

    /** The deterministic ActionIdempotencyRecord identity derivation version. */
    public static final String IDEMPOTENCY_RECORD_ID_VERSION = "campaign-action-idempotency-record-id/1.0";

    /** The deterministic BudgetReservation identity derivation version. */
    public static final String BUDGET_RESERVATION_ID_VERSION = "campaign-budget-reservation-id/1.0";

    /** The deterministic action lease-token derivation version. */
    public static final String ACTION_LEASE_TOKEN_VERSION = "campaign-action-lease-token/1.0";

    /** The bounded machine topic an action Outbox/Audit record is addressed to. */
    public static final String ACTION_AUDIT_TOPIC = "campaign.action.audit";

    /**
     * The bounded Participant waiting reason an Action in {@code UNKNOWN} leaves behind.
     *
     * <p>It is the accepted v1.3 waiting-reason vocabulary value for an unresolved external action, so the
     * Runtime side never has to learn a second word for the same fact.
     */
    public static final String WAITING_REASON_ACTION_VERIFY = "ACTION_VERIFY";

    /** The exact frozen governance-limit capability code reward budget is resolved from. */
    public static final String GOVERNANCE_LIMIT_CAPABILITY = GovernanceLimitVersions.CAPABILITY_CODE;

    /** The exact frozen governance-limit contract version reward budget is resolved from. */
    public static final String GOVERNANCE_LIMIT_CONTRACT_VERSION = GovernanceLimitVersions.CONTRACT_VERSION;

    /** The exact frozen {@code maxRewardGrants} field name. */
    public static final String LIMIT_MAX_REWARD_GRANTS = GovernanceLimitVersions.FIELD_MAX_REWARD_GRANTS;

    /** The exact frozen {@code maxRewardFaceValue} field name. */
    public static final String LIMIT_MAX_REWARD_FACE_VALUE = GovernanceLimitVersions.FIELD_MAX_REWARD_FACE_VALUE;

    /** The exact frozen {@code currency} field name. */
    public static final String LIMIT_CURRENCY = GovernanceLimitVersions.FIELD_CURRENCY;

    /** The accepted runtime schema version every handoff this layer consumes was produced under. */
    public static final String RUNTIME_SCHEMA_VERSION = RuntimeVersions.RUNTIME_SCHEMA_VERSION;

    /** True when the record kind is frozen development evidence rather than a production claim. */
    public static final boolean PRODUCTION_GATE_EVIDENCE = false;

    /** The exact scale every budget amount is normalized to; more precision than this is refused. */
    public static final int AMOUNT_SCALE = 4;

    /** The largest number of significant digits a budget amount may carry. */
    public static final int MAX_AMOUNT_PRECISION = 18;

    /** The largest number of actual attempts any bounded action retry policy may declare. */
    public static final int MAX_POLICY_ATTEMPTS = 8;

    /** The smallest lease a claim may take, so a lease is always a bounded real interval. */
    public static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(1);

    /** The largest lease a claim may take. */
    public static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(5);

    /** The past-most retry instant a bounded backoff may name. */
    public static final Duration MAX_RETRY_BACKOFF = Duration.ofHours(1);

    /** The largest number of claim candidates one scan considers, so a claim is never unbounded. */
    public static final int MAX_CLAIM_CANDIDATES = 4_096;

    /** The largest number of immutable action records one in-memory reference store retains. */
    public static final int MAX_ACTION_RECORDS = 4_194_304;

    /** The largest number of bounded allowlisted Provider request fields one request may carry. */
    public static final int MAX_REQUEST_FIELDS = 16;

    /** The largest length of one bounded Provider request text field. */
    public static final int MAX_REQUEST_TEXT = 256;

    /** The inclusive upper bound of one declared reward quantity. */
    public static final long MAX_REWARD_QUANTITY = 1_000_000L;

    /** The inclusive policy upper bound of a declared reward face value. */
    public static final BigDecimal MAX_REWARD_FACE_VALUE_AMOUNT = GovernanceLimitVersions.MAX_REWARD_FACE_VALUE;

    private ActionVersions() {
    }
}