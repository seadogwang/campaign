package com.company.campaign.runtime;

import com.company.campaign.builder.capability.governance.GovernanceLimitVersions;
import com.company.campaign.fullpublish.FullPublishVersions;

import java.time.Duration;

/**
 * Every version discriminator, closed code and frozen bound owned by the T22 Runtime layer.
 *
 * <p>Each value here is part of an immutable runtime record's canonical hash — an Execution, an audience
 * snapshot member, a Campaign enrollment, a Journey Participant, a NodeExecution, a NodeAttempt, a RuntimeTask,
 * an Inbox receipt, a runtime Outbox/Audit record or an aggregate counters projection — so changing any of them
 * is a new version rather than a silent reinterpretation of already-frozen bytes.
 *
 * <p>Phase A published only the discriminators it actually used. Phase B adds exactly the ones its worker
 * lease, bounded retry/backoff, Journey progression, {@code ACTION_READY} handoff, host Campaign-state and
 * Runtime fact contracts actually use, so this class never claims a behavior the implementation lacks.
 *
 * <p>The runtime schema version is not restated as a private constant: it is the accepted T20/T21
 * discriminator itself, so the runtime layer can never claim a schema version the compiler it consumes does
 * not produce.
 */
public final class RuntimeVersions {

    /** The accepted runtime schema version this layer executes under. */
    public static final String RUNTIME_SCHEMA_VERSION = FullPublishVersions.RUNTIME_SCHEMA_VERSION;

    /** The immutable Campaign Execution document format version. */
    public static final String EXECUTION_FORMAT_VERSION = "campaign-execution/1.0";

    /** The immutable audience snapshot member document format version. */
    public static final String AUDIENCE_MEMBER_FORMAT_VERSION = "campaign-execution-audience-member/1.0";

    /** The immutable Campaign member enrollment document format version. */
    public static final String ENROLLMENT_FORMAT_VERSION = "campaign-member-enrollment/1.0";

    /** The immutable Journey Participant document format version. */
    public static final String PARTICIPANT_FORMAT_VERSION = "campaign-journey-participant/1.0";

    /** The immutable NodeExecution document format version. */
    public static final String NODE_EXECUTION_FORMAT_VERSION = "campaign-node-execution/1.0";

    /** The immutable NodeAttempt document format version. */
    public static final String NODE_ATTEMPT_FORMAT_VERSION = "campaign-node-attempt/1.0";

    /** The immutable RuntimeTask document format version. */
    public static final String TASK_FORMAT_VERSION = "campaign-runtime-task/1.0";

    /** The immutable runtime Inbox receipt document format version. */
    public static final String INBOX_FORMAT_VERSION = "campaign-runtime-inbox/1.0";

    /** The immutable runtime Outbox/Audit record document format version. */
    public static final String AUDIT_FORMAT_VERSION = "campaign-runtime-outbox/1.0";

    /** The immutable aggregate counters projection document format version. */
    public static final String COUNTERS_FORMAT_VERSION = "campaign-execution-counters/1.0";

    /** The immutable {@code ACTION_READY} handoff record document format version T23 consumes. */
    public static final String HANDOFF_FORMAT_VERSION = "campaign-action-handoff/1.0";

    /** The immutable host Campaign runtime/schedule state projection document format version. */
    public static final String CAMPAIGN_STATE_FORMAT_VERSION = "campaign-runtime-campaign-state/1.0";

    /** The immutable Runtime fact request document format version. */
    public static final String FACT_REQUEST_FORMAT_VERSION = "campaign-runtime-fact-request/1.0";

    /** The immutable Runtime fact result document format version. */
    public static final String FACT_RESULT_FORMAT_VERSION = "campaign-runtime-fact-result/1.0";

    /** The deterministic lease-token derivation version. */
    public static final String LEASE_TOKEN_VERSION = "campaign-runtime-lease-token/1.0";

    /** The deterministic {@code ACTION_READY} handoff-identity derivation version. */
    public static final String HANDOFF_ID_VERSION = "campaign-runtime-action-handoff-id/1.0";

    /** The bounded reason a Participant is waiting for a frozen node duration. */
    public static final String WAIT_REASON_DURATION = "DURATION";

    /** The smallest lease a claim may take, so a lease is always a bounded real interval. */
    public static final Duration MIN_LEASE_DURATION = Duration.ofSeconds(1);

    /** The largest lease a claim may take. */
    public static final Duration MAX_LEASE_DURATION = Duration.ofMinutes(5);

    /** The past-most retry instant a bounded technical backoff may name. */
    public static final Duration MAX_RETRY_BACKOFF = Duration.ofHours(1);

    /** The largest number of claim candidates one scan considers, so a claim is never unbounded. */
    public static final int MAX_CLAIM_CANDIDATES = 4_096;

    /** The largest number of successor tasks one node completion may enqueue. */
    public static final int MAX_NEXT_TASKS_PER_COMMIT = 8;

    /** The largest number of {@code ACTION_READY} handoffs one node completion may create. */
    public static final int MAX_HANDOFFS_PER_COMMIT = 8;

    /** The immutable audience materialization request document format version. */
    public static final String MATERIALIZATION_REQUEST_FORMAT_VERSION =
            "campaign-audience-materialization-request/1.0";

    /** The immutable audience materialization result document format version. */
    public static final String MATERIALIZATION_RESULT_FORMAT_VERSION =
            "campaign-audience-materialization-result/1.0";

    /** The deterministic participant-identity derivation version. */
    public static final String PARTICIPANT_ID_VERSION = "campaign-participant-id/1.0";

    /** The deterministic task-identity derivation version. */
    public static final String TASK_ID_VERSION = "campaign-runtime-task-id/1.0";

    /** The bounded machine topic a runtime Outbox/Audit record is addressed to. */
    public static final String AUDIT_TOPIC = "campaign.runtime.audit";

    /** True when the record kind is frozen development evidence rather than a production claim. */
    public static final boolean PRODUCTION_GATE_EVIDENCE = false;

    /** The largest number of distinct member identities one materialization may accept. */
    public static final int MAX_AUDIENCE_MEMBERS = 100_000;

    /** The largest number of staged Participants one occurrence materialization may commit at once. */
    public static final int MAX_MATERIALIZED_PARTICIPANTS = 100_000;

    /** The largest number of runtime Inbox receipts one in-memory store retains. */
    public static final int MAX_INBOX_RECORDS = 1_048_576;

    /** The largest number of runtime audit records one in-memory store retains. */
    public static final int MAX_AUDIT_RECORDS = 1_048_576;

    /** The largest number of frozen runtime records one in-memory store retains. */
    public static final int MAX_RUNTIME_RECORDS = 4_194_304;

    /** The largest age any audience freshness policy may declare, so an unbounded staleness is unrepresentable. */
    public static final Duration MAX_FRESHNESS_AGE = Duration.ofHours(24);

    /** The smallest age any audience freshness policy may declare; zero means the evidence must be live. */
    public static final Duration MIN_FRESHNESS_AGE = Duration.ZERO;

    /** The exact segment composition slot whose frozen component is the Campaign audience pin. */
    public static final String AUDIENCE_SLOT_CODE = "campaign.audience.segment";

    /** The exact frozen governance-limit capability code phase A resolves {@code maxRecipients} from. */
    public static final String GOVERNANCE_LIMIT_CAPABILITY = GovernanceLimitVersions.CAPABILITY_CODE;

    /** The exact frozen governance-limit contract version phase A resolves {@code maxRecipients} from. */
    public static final String GOVERNANCE_LIMIT_CONTRACT_VERSION = GovernanceLimitVersions.CONTRACT_VERSION;

    /** The exact frozen {@code maxRecipients} field name inside that declaration. */
    public static final String GOVERNANCE_LIMIT_MAX_RECIPIENTS = GovernanceLimitVersions.FIELD_MAX_RECIPIENTS;

    private RuntimeVersions() {
    }
}