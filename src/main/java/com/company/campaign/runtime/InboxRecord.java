package com.company.campaign.runtime;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable Inbox receipt: the durable proof that one exact T21 work item was accepted, and what the
 * consumer decided about it.
 *
 * <p>Its identity is {@code (sourceKind, sourceId)}, and it additionally pins the exact source hash it was
 * accepted under. That is what makes redelivery decidable without a queue: an exact duplicate carries the same
 * hash and returns the original receipt unchanged, while the same identity carrying different hash material is
 * a bounded conflict rather than a second Execution.
 *
 * <p>It persists tenant identity, so a background worker can restore and verify tenant scope from durable state
 * instead of an HTTP security context. It carries no member value and {@link #toString()} prints neither the
 * tenant nor the Campaign identifier.
 */
public record InboxRecord(
        String formatVersion,
        InboxSourceKind sourceKind,
        String sourceId,
        ContentHash sourceHash,
        String tenantId,
        String campaignId,
        String fullDefinitionId,
        long publicationRevision,
        String executionKey,
        InboxOutcome outcome,
        Instant receivedAt,
        ContentHash recordHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public InboxRecord {
        if (!RuntimeVersions.INBOX_FORMAT_VERSION.equals(formatVersion)) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED, "formatVersion");
        }
        Objects.requireNonNull(sourceKind, "sourceKind");
        RuntimeSupport.requireIdentifier(sourceId, "sourceId");
        Objects.requireNonNull(sourceHash, "sourceHash");
        RuntimeSupport.requireIdentifier(tenantId, "tenantId");
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        RuntimeSupport.requirePositive(publicationRevision, "publicationRevision");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.hasExecution()) {
            RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        } else if (executionKey != null) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "executionKey");
        }
        Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(recordHash, "recordHash");
        if (!recordHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, sourceKind, sourceId, sourceHash, tenantId, campaignId, fullDefinitionId,
                publicationRevision, executionKey, outcome, receivedAt)))) {
            throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "recordHash");
        }
    }

    /** Builds one Inbox receipt and stamps it with its own canonical hash. */
    public static InboxRecord of(
            InboxSourceKind sourceKind,
            String sourceId,
            ContentHash sourceHash,
            String tenantId,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            String executionKey,
            InboxOutcome outcome,
            Instant receivedAt) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(outcome, "outcome");
        return new InboxRecord(
                RuntimeVersions.INBOX_FORMAT_VERSION,
                sourceKind, sourceId, sourceHash, tenantId, campaignId, fullDefinitionId, publicationRevision,
                outcome.hasExecution() ? executionKey : null, outcome, receivedAt,
                CANONICAL_JSON.hash(material(
                        RuntimeVersions.INBOX_FORMAT_VERSION, sourceKind, sourceId, sourceHash, tenantId,
                        campaignId, fullDefinitionId, publicationRevision,
                        outcome.hasExecution() ? executionKey : null, outcome, receivedAt)));
    }

    /** True when this receipt already describes exactly the given delivery, so a redelivery is a no-op. */
    public boolean describes(InboxSourceKind kind, String id, ContentHash hash) {
        return sourceKind == kind && sourceId.equals(id) && sourceHash.equals(hash);
    }

    private static Material material(
            String formatVersion,
            InboxSourceKind sourceKind,
            String sourceId,
            ContentHash sourceHash,
            String tenantId,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            String executionKey,
            InboxOutcome outcome,
            Instant receivedAt) {
        return new Material(formatVersion, sourceKind, sourceId, sourceHash, tenantId, campaignId,
                fullDefinitionId, publicationRevision, executionKey, outcome, receivedAt);
    }

    /** Redacted diagnostics: no tenant, Campaign or source identifier is printed. */
    @Override
    public String toString() {
        return "InboxRecord[sourceKind=" + sourceKind
                + ", publicationRevision=" + publicationRevision
                + ", outcome=" + outcome
                + ", receivedAt=" + receivedAt
                + ", recordHash=" + recordHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            InboxSourceKind sourceKind,
            String sourceId,
            ContentHash sourceHash,
            String tenantId,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            String executionKey,
            InboxOutcome outcome,
            Instant receivedAt) {
    }
}