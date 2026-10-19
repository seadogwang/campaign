package com.company.campaign.lifecycle;

import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.ReferenceLimits;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One atomic Publish commit: the immutable Published Definition, its receipt, the audit event and — for a
 * reference-bearing publication — every retention hold the publication depends on.
 *
 * <p>The commit carries the preparation it consumes, the canonical request hash its idempotency key is
 * bound to, and the published revision it expects to be next. An adapter commits the definition, publishes
 * the receipt, marks the preparation consumed, acquires the retention holds and records the audit event as
 * one unit — or does none of it.
 *
 * <p>An exact repeat of the same {@code (owner, preparation, idempotencyKey, requestHash)} returns the
 * original receipt and writes nothing; the same key with a different request hash is a conflict; a
 * preparation already consumed by a different key is refused.
 *
 * <p>Construction binds the receipt to the definition it publishes in full — identity, revision, owner,
 * DataScope, Campaign and Draft identity and revisions, source Plan, Manifest hash and content checksum —
 * and pins the expected published revision to the definition's own revision, so a host-side caller cannot
 * pair individually self-valid artifacts that describe different publications.
 *
 * <p>{@link #retentionHolds()} is the one optional slot. It is empty for every reference-free publication,
 * so the accepted T17 commit material is unchanged, and a reference-bearing publication names exactly one
 * deterministic hold per exact re-resolved referenced version. Each hold must witness this commit's own
 * publication, so a commit can never acquire a hold attributed to a different one.
 *
 * <p>{@link #additionalAuditEvents()} is the one additive slot. It is empty for every accepted T17 caller, so
 * their definition, receipt and hash bytes are identical to what they always froze, and a caller whose
 * publication freezes a strictly larger artifact set can name the extra audit events that must be recorded by
 * this same transaction. The adapter submits the base and additional events as one atomic audit batch before
 * any state is published: either the complete audit evidence is recorded or none of it is.
 */
public record PublishCommit(
        String expectedPreparationId,
        ContentHash expectedPreparationHash,
        String idempotencyKey,
        ContentHash requestHash,
        long expectedPublishedRevision,
        PublishedCampaignDefinition definition,
        PublishReceipt receipt,
        LifecycleAuditEvent auditEvent,
        List<AudienceRetentionHold> retentionHolds,
        List<LifecycleAuditEvent> additionalAuditEvents
) {

    /** The largest number of additional audit events one commit may carry, so the slot stays bounded. */
    public static final int MAX_ADDITIONAL_AUDIT_EVENTS = 8;

    /**
     * The accepted reference-free commit: a publication with no referenced version acquires no hold.
     *
     * <p>It is the constructor every existing caller keeps using, so leaving the retention slot empty is
     * exactly the material a reference-free Publish always froze.
     */
    public PublishCommit(
            String expectedPreparationId,
            ContentHash expectedPreparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            long expectedPublishedRevision,
            PublishedCampaignDefinition definition,
            PublishReceipt receipt,
            LifecycleAuditEvent auditEvent) {
        this(expectedPreparationId, expectedPreparationHash, idempotencyKey, requestHash,
                expectedPublishedRevision, definition, receipt, auditEvent, List.of(), List.of());
    }

    /**
     * The accepted retained commit: it names its retention holds and carries no additional audit event.
     *
     * <p>It keeps the accepted nine-argument constructor intact, so a reference-bearing publication freezes
     * exactly the material it always froze.
     */
    public PublishCommit(
            String expectedPreparationId,
            ContentHash expectedPreparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            long expectedPublishedRevision,
            PublishedCampaignDefinition definition,
            PublishReceipt receipt,
            LifecycleAuditEvent auditEvent,
            List<AudienceRetentionHold> retentionHolds) {
        this(expectedPreparationId, expectedPreparationHash, idempotencyKey, requestHash,
                expectedPublishedRevision, definition, receipt, auditEvent, retentionHolds, List.of());
    }

    public PublishCommit {
        Objects.requireNonNull(expectedPreparationId, "expectedPreparationId");
        Objects.requireNonNull(expectedPreparationHash, "expectedPreparationHash");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(auditEvent, "auditEvent");
        if (expectedPublishedRevision < 1) {
            throw new IllegalArgumentException("an expected published revision must be positive");
        }
        if (expectedPublishedRevision != definition.publishedRevision()) {
            throw new IllegalArgumentException(
                    "an expected published revision must be the definition's own revision");
        }
        // The receipt must witness exactly the definition this commit publishes: a commit that publishes one
        // version and records a receipt for another would be an unverifiable pair.
        if (!receipt.publishedId().equals(definition.publishedId())
                || receipt.publishedRevision() != definition.publishedRevision()
                || !receipt.contentChecksum().equals(definition.contentChecksum())
                || !receipt.preparationId().equals(expectedPreparationId)
                || !receipt.preparationHash().equals(expectedPreparationHash)
                || !receipt.requestHash().equals(requestHash)
                || !receipt.idempotencyKey().equals(idempotencyKey)) {
            throw new IllegalArgumentException("a publish commit's receipt must witness its own publication");
        }
        if (!receipt.tenantId().equals(definition.tenantId())
                || !receipt.programId().equals(definition.programId())
                || !receipt.userId().equals(definition.userId())
                || !receipt.dataScopeId().equals(definition.dataScopeId())) {
            throw new IllegalArgumentException("a publish commit's receipt must carry the definition's owner");
        }
        if (!receipt.campaignId().equals(definition.campaignId())
                || receipt.campaignRevision() != definition.campaignRevision()
                || !receipt.draftId().equals(definition.sourceDraftId())
                || receipt.draftRevision() != definition.sourceDraftRevision()) {
            throw new IllegalArgumentException("a publish commit's receipt must witness the frozen draft");
        }
        if (!receipt.planId().equals(definition.planId())
                || !receipt.planHash().equals(definition.planHash())
                || !receipt.manifestHash().equals(definition.manifestHash())) {
            throw new IllegalArgumentException("a publish commit's receipt must witness the source plan");
        }
        if (!receipt.confirmedRiskCodes().equals(definition.riskCodes())) {
            throw new IllegalArgumentException("a publish commit's receipt must witness the confirmed risks");
        }
        retentionHolds = canonicalHolds(retentionHolds, definition);
        additionalAuditEvents = canonicalAdditionalAuditEvents(additionalAuditEvents);
    }

    /**
     * The commit's additional audit events, canonical: bounded and free of absent entries.
     *
     * <p>Order is deliberately preserved rather than sorted: the caller that freezes the larger artifact set
     * chooses the order in which its extra audit events are recorded, and this slot never carries a null event
     * a sink would have to interpret.
     */
    private static List<LifecycleAuditEvent> canonicalAdditionalAuditEvents(
            List<LifecycleAuditEvent> events) {
        if (events == null || events.size() > MAX_ADDITIONAL_AUDIT_EVENTS) {
            throw new IllegalArgumentException("a publish commit's additional audit events must be bounded");
        }
        for (LifecycleAuditEvent event : events) {
            Objects.requireNonNull(event, "additionalAuditEvent");
        }
        return List.copyOf(events);
    }

    /**
     * The commit's retention holds, canonical: bounded, duplicate-free, hold-ID ordered and each witnessing
     * this commit's own publication.
     *
     * <p>A hold whose holder is not the definition this commit freezes would keep a referenced version alive
     * on behalf of a publication that never happened, so it is not representable rather than merely rejected
     * later.
     */
    private static List<AudienceRetentionHold> canonicalHolds(
            List<AudienceRetentionHold> holds, PublishedCampaignDefinition definition) {
        if (holds == null || holds.size() > ReferenceLimits.MAX_DISTINCT_VERSIONS) {
            throw new IllegalArgumentException("a publish commit's retention holds must be bounded");
        }
        List<AudienceRetentionHold> ordered = new ArrayList<>(holds.size());
        Set<String> holdIds = new LinkedHashSet<>();
        for (AudienceRetentionHold hold : holds) {
            if (hold == null || !holdIds.add(hold.holdId())
                    || !hold.holderAudienceId().equals(definition.campaignId())
                    || !hold.holderVersionId().equals(definition.publishedId())) {
                throw new IllegalArgumentException(
                        "a publish commit's retention holds must witness its own publication");
            }
            ordered.add(hold);
        }
        ordered.sort(Comparator.comparing(AudienceRetentionHold::holdId));
        return List.copyOf(ordered);
    }
}
