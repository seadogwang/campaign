package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable runtime Outbox/Audit record: the durable, hash-bound statement of what a runtime command did.
 *
 * <p>It carries identities, hashes, bounded machine codes and before/after revisions only. There is no member
 * value, no template body, no reward description or secret, no raw fact value, no SQL, no credential, no
 * provider data and no unrestricted exception message anywhere in it, and {@link #toString()} prints neither
 * the tenant nor the subject identifier.
 *
 * <p>It is deliberately distinct from business Execution state: an audit record witnesses a transition, while
 * the Execution states what the Campaign is doing. A failed audit write rolls the transition back with it,
 * because both are written inside one store transaction.
 */
public record RuntimeAuditRecord(
        String formatVersion,
        String recordId,
        String tenantId,
        String topic,
        String reasonCode,
        String subjectKind,
        String subjectId,
        String executionKey,
        Long beforeRevision,
        Long afterRevision,
        Instant occurredAt,
        ContentHash recordHash
) {

    /** One schedule-registration delivery was accepted into the Inbox. */
    public static final String REASON_REGISTRATION_RECEIVED = "REGISTRATION_RECEIVED";

    /** One {@code SKIPPED} occurrence was accepted without creating an Execution. */
    public static final String REASON_OCCURRENCE_SKIPPED = "OCCURRENCE_SKIPPED";

    /** Exactly one Execution was created for a stable execution key. */
    public static final String REASON_EXECUTION_CREATED = "EXECUTION_CREATED";

    /** A materialization completed with the given number of newly enrolled Participants. */
    public static final String REASON_MATERIALIZATION_COMPLETED = "MATERIALIZATION_COMPLETED";

    /** A materialization completed successfully over a zero-candidate audience. */
    public static final String REASON_MATERIALIZATION_EMPTY = "MATERIALIZATION_EMPTY";

    /** A materialization completed successfully with every candidate already enrolled. */
    public static final String REASON_MATERIALIZATION_ENROLLED = "MATERIALIZATION_ENROLLED";

    /** A materialization failed closed and wrote no snapshot, enrollment, Participant or task. */
    public static final String REASON_MATERIALIZATION_FAILED = "MATERIALIZATION_FAILED";

    /** One candidate was deterministically excluded because the Campaign already enrolled it. */
    public static final String REASON_ENROLLMENT_EXCLUDED = "ENROLLMENT_EXCLUDED";

    /** One worker took the lease of one due task. */
    public static final String REASON_LEASE_CLAIMED = "LEASE_CLAIMED";

    /** One worker reclaimed the lease of one task whose previous lease had expired. */
    public static final String REASON_LEASE_RECLAIMED = "LEASE_RECLAIMED";

    /** One frozen Journey node completed locally and the Participant advanced. */
    public static final String REASON_NODE_SUCCEEDED = "NODE_SUCCEEDED";

    /** One Wait node stored its frozen deadline and one wake task. */
    public static final String REASON_NODE_WAITING = "NODE_WAITING";

    /** One Wait node was resumed at its original deadline exactly once. */
    public static final String REASON_WAIT_WOKEN = "WAIT_WOKEN";

    /** One MESSAGE or REWARD node created only bounded {@code ACTION_READY} handoff work for T23. */
    public static final String REASON_ACTION_READY = "ACTION_READY";

    /** One bounded technical failure scheduled its next attempt. */
    public static final String REASON_RETRY_SCHEDULED = "RETRY_SCHEDULED";

    /** One task was dead-lettered after its bounded attempts were exhausted. */
    public static final String REASON_DEAD_LETTERED = "DEAD_LETTERED";

    /** One node failed closed with a non-retryable business outcome, leaving the work needing a decision. */
    public static final String REASON_BUSINESS_FAILURE = "BUSINESS_FAILURE";

    /** One node failed closed because its frozen configuration can never be satisfied. */
    public static final String REASON_CONFIGURATION_FAILURE = "CONFIGURATION_FAILURE";

    /** One next task was guarded as {@code DEFERRED} because the Campaign was paused. */
    public static final String REASON_PAUSE_GUARDED = "PAUSE_GUARDED";

    /** Guarded work was released by a resume without resending successful work. */
    public static final String REASON_RESUME_RELEASED = "RESUME_RELEASED";

    /** One external-action node was finished by the external-action layer's additive atomic completion. */
    public static final String REASON_ACTION_NODE_COMPLETED = "ACTION_NODE_COMPLETED";

    /** One external-action node was left verifying while its remote result remains unresolved. */
    public static final String REASON_ACTION_NODE_VERIFYING = "ACTION_NODE_VERIFYING";

    /** Duplicate work was replayed hash-equal and reused the committed result. */
    public static final String REASON_COMPLETION_DUPLICATE = "COMPLETION_DUPLICATE";

    /** The Execution's deterministic terminal aggregate advanced. */
    public static final String REASON_EXECUTION_AGGREGATED = "EXECUTION_AGGREGATED";

    /** The host-owned Campaign runtime/schedule state could not be verified, so work failed closed. */
    public static final String REASON_CAMPAIGN_STATE_UNVERIFIED = "CAMPAIGN_STATE_UNVERIFIED";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public RuntimeAuditRecord {
        if (!RuntimeVersions.AUDIT_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        RuntimeSupport.requireIdentifier(recordId, "recordId");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        if (!RuntimeVersions.AUDIT_TOPIC.equals(topic)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "topic");
        }
        RuntimeSupport.requireMachineCode(reasonCode, "reasonCode");
        RuntimeSupport.requireMachineCode(subjectKind, "subjectKind");
        RuntimeSupport.requireIdentifier(subjectId, "subjectId");
        if (executionKey != null) {
            RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        }
        if (beforeRevision != null && beforeRevision < 0 || afterRevision != null && afterRevision < 0) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "revision");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordHash, "recordHash");
        if (!recordHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, recordId, tenantId, topic, reasonCode, subjectKind, subjectId, executionKey,
                beforeRevision, afterRevision, occurredAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "recordHash");
        }
    }

    /** Builds one runtime Outbox/Audit record and stamps it with its own canonical hash. */
    public static RuntimeAuditRecord of(
            String recordId,
            String tenantId,
            String reasonCode,
            String subjectKind,
            String subjectId,
            String executionKey,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
        return new RuntimeAuditRecord(
                RuntimeVersions.AUDIT_FORMAT_VERSION,
                recordId, tenantId, RuntimeVersions.AUDIT_TOPIC, reasonCode, subjectKind, subjectId,
                executionKey, beforeRevision, afterRevision, occurredAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.AUDIT_FORMAT_VERSION, recordId, tenantId, RuntimeVersions.AUDIT_TOPIC,
                        reasonCode, subjectKind, subjectId, executionKey, beforeRevision, afterRevision,
                        occurredAt)));
    }

    private static Material material(
            String formatVersion,
            String recordId,
            String tenantId,
            String topic,
            String reasonCode,
            String subjectKind,
            String subjectId,
            String executionKey,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
        return new Material(formatVersion, recordId, tenantId, topic, reasonCode, subjectKind, subjectId,
                executionKey, beforeRevision, afterRevision, occurredAt);
    }

    /** Redacted diagnostics: no tenant and no subject identifier is printed. */
    @Override
    public String toString() {
        return "RuntimeAuditRecord[recordId=" + recordId
                + ", reasonCode=" + reasonCode
                + ", subjectKind=" + subjectKind
                + ", beforeRevision=" + beforeRevision
                + ", afterRevision=" + afterRevision
                + ", occurredAt=" + occurredAt
                + ", recordHash=" + recordHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String recordId,
            String tenantId,
            String topic,
            String reasonCode,
            String subjectKind,
            String subjectId,
            String executionKey,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
    }
}
