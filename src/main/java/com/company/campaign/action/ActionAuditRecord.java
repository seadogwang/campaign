package com.company.campaign.action;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable action Outbox/Audit record: the durable, hash-bound statement of what one external-action
 * command did.
 *
 * <p>It carries identities, hashes, bounded machine codes and before/after revisions only. There is no member
 * value, no recipient address, no template body, no variable value, no reward description or secret, no
 * Provider token or reference, no credential, no SQL and no unrestricted exception message anywhere in it, and
 * {@link #toString()} prints neither the tenant nor the subject identifier.
 *
 * <p>An audit record witnesses a transition while {@link ActionExecution} states what the action is doing, so a
 * failed audit write rolls the transition back with it: both are written inside one store transaction.
 */
public record ActionAuditRecord(
        String formatVersion,
        String recordId,
        String tenantId,
        String topic,
        String reasonCode,
        String subjectKind,
        String subjectId,
        Long beforeRevision,
        Long afterRevision,
        Instant occurredAt,
        ContentHash recordHash
) {

    /** One REWARD action reserved its exact conservative budget exposure before any Provider call. */
    public static final String REASON_BUDGET_RESERVED = "BUDGET_RESERVED";

    /** One REWARD action finalized its reservation because the remote effect is confirmed applied. */
    public static final String REASON_BUDGET_FINALIZED = "BUDGET_FINALIZED";

    /** One REWARD action released its reservation because no remote effect exists. */
    public static final String REASON_BUDGET_RELEASED = "BUDGET_RELEASED";

    /** One REWARD action was refused because the frozen budget limit would be exceeded. */
    public static final String REASON_BUDGET_REFUSED = "BUDGET_REFUSED";

    /** One action was reserved from one exact immutable handoff. */
    public static final String REASON_ACTION_RESERVED = "ACTION_RESERVED";

    /** One already-reserved action was presented again under the same key and frozen material. */
    public static final String REASON_ACTION_DUPLICATE = "ACTION_DUPLICATE";

    /** A business key was presented again under material that is not the reserved action's. */
    public static final String REASON_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    /** One submission claim leased the durable submission task and persisted IN_FLIGHT before the call. */
    public static final String REASON_SUBMISSION_CLAIMED = "SUBMISSION_CLAIMED";

    /** No submission started because the host-owned Campaign projection is paused. */
    public static final String REASON_PAUSE_GUARDED = "PAUSE_GUARDED";

    /** One Provider submission applied the effect. */
    public static final String REASON_PROVIDER_SUCCEEDED = "PROVIDER_SUCCEEDED";

    /** One Provider submission established that the effect is definitively not applied. */
    public static final String REASON_PROVIDER_NOT_APPLIED = "PROVIDER_NOT_APPLIED";

    /** One Provider submission left the effect pending remotely. */
    public static final String REASON_PROVIDER_PENDING = "PROVIDER_PENDING";

    /** One Provider submission, timeout or crash left the remote outcome unknown. */
    public static final String REASON_PROVIDER_UNKNOWN = "PROVIDER_UNKNOWN";

    /** One reconciliation query was scheduled or claimed. */
    public static final String REASON_VERIFICATION_SCHEDULED = "VERIFICATION_SCHEDULED";

    /** One reconciliation query confirmed the remote effect exists. */
    public static final String REASON_VERIFIED_SUCCEEDED = "VERIFIED_SUCCEEDED";

    /** One reconciliation query proved the effect is definitively not applied. */
    public static final String REASON_VERIFIED_NOT_APPLIED = "VERIFIED_NOT_APPLIED";

    /** One reconciliation query left the effect unresolved, so another bounded verification is scheduled. */
    public static final String REASON_VERIFIED_UNRESOLVED = "VERIFIED_UNRESOLVED";

    /** One bounded safe retry reused the original action, key, request hash and reservation. */
    public static final String REASON_SAFE_RETRY = "SAFE_RETRY";

    /** One action exhausted its bounded verification policy and needs a human decision. */
    public static final String REASON_NEEDS_REVIEW = "NEEDS_REVIEW";

    /** One action completed and the exact frozen Participant was advanced once. */
    public static final String REASON_ACTION_COMPLETED = "ACTION_COMPLETED";

    /** One action failed definitively and the frozen failure policy was applied. */
    public static final String REASON_ACTION_FAILED = "ACTION_FAILED";

    /** One action's Runtime node was left verifying and the Participant was left waiting for reconciliation. */
    public static final String REASON_ACTION_VERIFYING = "ACTION_VERIFYING";

    /** One exact duplicate completion reused the committed logical result and wrote nothing. */
    public static final String REASON_COMPLETION_DUPLICATE = "COMPLETION_DUPLICATE";

    /** One host-only reconciliation operation was performed under explicit authorization evidence. */
    public static final String REASON_OPERATOR_RECONCILE = "OPERATOR_RECONCILE";

    /** One host-only safe-retry operation was performed under explicit authorization evidence. */
    public static final String REASON_OPERATOR_RETRY = "OPERATOR_RETRY";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ActionAuditRecord {
        if (!ActionVersions.ACTION_AUDIT_FORMAT_VERSION.equals(formatVersion)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_VERSION_UNSUPPORTED, "formatVersion");
        }
        ActionSupport.requireIdentifier(recordId, "recordId");
        ActionSupport.requireIdentifier(tenantId, "tenantId");
        if (!ActionVersions.ACTION_AUDIT_TOPIC.equals(topic)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "topic");
        }
        ActionSupport.requireMachineCode(reasonCode, "reasonCode");
        ActionSupport.requireMachineCode(subjectKind, "subjectKind");
        ActionSupport.requireIdentifier(subjectId, "subjectId");
        if (beforeRevision != null && beforeRevision < 0 || afterRevision != null && afterRevision < 0) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "revision");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(recordHash, "recordHash");
        if (!recordHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, recordId, tenantId, topic, reasonCode, subjectKind, subjectId, beforeRevision,
                afterRevision, occurredAt)))) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "recordHash");
        }
    }

    /** Builds one action Outbox/Audit record and stamps it with its own canonical hash. */
    public static ActionAuditRecord of(
            String recordId,
            String tenantId,
            String reasonCode,
            String subjectKind,
            String subjectId,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
        return new ActionAuditRecord(
                ActionVersions.ACTION_AUDIT_FORMAT_VERSION, recordId, tenantId, ActionVersions.ACTION_AUDIT_TOPIC,
                reasonCode, subjectKind, subjectId, beforeRevision, afterRevision, occurredAt,
                CANONICAL_JSON.hash(material(
                        ActionVersions.ACTION_AUDIT_FORMAT_VERSION, recordId, tenantId,
                        ActionVersions.ACTION_AUDIT_TOPIC, reasonCode, subjectKind, subjectId, beforeRevision,
                        afterRevision, occurredAt)));
    }

    private static Material material(
            String formatVersion,
            String recordId,
            String tenantId,
            String topic,
            String reasonCode,
            String subjectKind,
            String subjectId,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
        return new Material(formatVersion, recordId, tenantId, topic, reasonCode, subjectKind, subjectId,
                beforeRevision, afterRevision, occurredAt);
    }

    /** Redacted diagnostics: no tenant and no subject identifier is printed. */
    @Override
    public String toString() {
        return "ActionAuditRecord[recordId=" + recordId
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
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
    }
}