package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
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
 * One atomic full publication commit: the accepted T17 definition and receipt, the full definition and receipt,
 * the active schedule projection, the durable scheduling-registration outbox event, both bounded audit events
 * and every retention hold the publication depends on.
 *
 * <p>An adapter commits all of it as one unit or commits none of it. The accepted T17 artifacts are the ones
 * the transaction itself writes into the accepted lifecycle store, so the published pointer the T17 boundary
 * exposes moves with the full definition that supersedes it rather than in a second, separate write.
 *
 * <p>Construction binds every artifact to every other one: the full receipt must witness the full definition,
 * the full definition must bind the base definition's own identity and checksum, the schedule projection and
 * the outbox event must describe the same full definition, and each retention hold must witness this commit's
 * own base publication. A commit that paired individually self-valid artifacts describing different
 * publications is therefore not representable.
 */
public record FullPublishCommit(
        String expectedPreparationId,
        ContentHash expectedPreparationHash,
        String idempotencyKey,
        ContentHash requestHash,
        long expectedPublishedRevision,
        PublishedCampaignDefinition baseDefinition,
        PublishReceipt baseReceipt,
        LifecycleAuditEvent baseAuditEvent,
        FullPublishedDefinition fullDefinition,
        FullPublishReceipt fullReceipt,
        CampaignScheduleState scheduleState,
        ScheduleRegistrationOutboxEvent outboxEvent,
        LifecycleAuditEvent fullAuditEvent,
        List<AudienceRetentionHold> retentionHolds
) {

    public FullPublishCommit {
        Objects.requireNonNull(expectedPreparationId, "expectedPreparationId");
        Objects.requireNonNull(expectedPreparationHash, "expectedPreparationHash");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(baseDefinition, "baseDefinition");
        Objects.requireNonNull(baseReceipt, "baseReceipt");
        Objects.requireNonNull(baseAuditEvent, "baseAuditEvent");
        Objects.requireNonNull(fullDefinition, "fullDefinition");
        Objects.requireNonNull(fullReceipt, "fullReceipt");
        Objects.requireNonNull(scheduleState, "scheduleState");
        Objects.requireNonNull(outboxEvent, "outboxEvent");
        Objects.requireNonNull(fullAuditEvent, "fullAuditEvent");
        if (expectedPublishedRevision < 1
                || expectedPublishedRevision != baseDefinition.publishedRevision()
                || expectedPublishedRevision != fullDefinition.publicationRevision()) {
            throw new IllegalArgumentException(
                    "a full publication commit's expected revision must be the revision it freezes");
        }
        if (baseAuditEvent.action() != LifecycleAction.PUBLISH
                || fullAuditEvent.action() != LifecycleAction.FULL_PUBLISH) {
            throw new IllegalArgumentException(
                    "a full publication commit must record its base and full audit actions");
        }
        // The full definition must bind exactly the base definition this commit writes.
        if (!fullDefinition.baseIdentity().equals(baseDefinition.identity())
                || !fullDefinition.campaignId().equals(baseDefinition.campaignId())
                || fullDefinition.campaignRevision() != baseDefinition.campaignRevision()) {
            throw new IllegalArgumentException(
                    "a full publication commit's full definition must bind its own base publication");
        }
        if (!fullReceipt.fullDefinitionId().equals(fullDefinition.fullDefinitionId())
                || fullReceipt.publicationRevision() != fullDefinition.publicationRevision()
                || !fullReceipt.fullDefinitionHash().equals(fullDefinition.contentHash())
                || !fullReceipt.preparationId().equals(expectedPreparationId)
                || !fullReceipt.preparationHash().equals(expectedPreparationHash)
                || !fullReceipt.idempotencyKey().equals(idempotencyKey)
                || !fullReceipt.requestHash().equals(requestHash)
                || !fullReceipt.campaignId().equals(fullDefinition.campaignId())
                || !fullReceipt.basePublishedId().equals(fullDefinition.basePublishedId())
                || !fullReceipt.baseDefinitionChecksum().equals(fullDefinition.baseDefinitionChecksum())
                || !fullReceipt.journeyHash().equals(fullDefinition.journeyHash())
                || !fullReceipt.scheduleHash().equals(fullDefinition.scheduleHash())) {
            throw new IllegalArgumentException(
                    "a full publication commit's receipt must witness its own full publication");
        }
        if (!fullReceipt.tenantId().equals(fullDefinition.tenantId())
                || !fullReceipt.programId().equals(fullDefinition.programId())
                || !fullReceipt.userId().equals(fullDefinition.userId())
                || !fullReceipt.dataScopeId().equals(fullDefinition.dataScopeId())) {
            throw new IllegalArgumentException(
                    "a full publication commit's receipt must carry the full definition's owner");
        }
        // The active projection must describe exactly the full definition this commit freezes.
        if (!scheduleState.campaignId().equals(fullDefinition.campaignId())
                || !scheduleState.tenantId().equals(fullDefinition.tenantId())
                || !scheduleState.programId().equals(fullDefinition.programId())
                || !scheduleState.userId().equals(fullDefinition.userId())
                || !scheduleState.dataScopeId().equals(fullDefinition.dataScopeId())
                || !scheduleState.fullDefinitionId().equals(fullDefinition.fullDefinitionId())
                || !scheduleState.fullDefinitionHash().equals(fullDefinition.contentHash())
                || !scheduleState.baseDefinitionChecksum().equals(fullDefinition.baseDefinitionChecksum())
                || !scheduleState.journeyHash().equals(fullDefinition.journeyHash())
                || !scheduleState.scheduleHash().equals(fullDefinition.scheduleHash())) {
            throw new IllegalArgumentException(
                    "a full publication commit's schedule projection must describe its own publication");
        }
        // The outbox event must describe exactly the same publication, and nothing else.
        if (!outboxEvent.campaignId().equals(fullDefinition.campaignId())
                || !outboxEvent.fullDefinitionId().equals(fullDefinition.fullDefinitionId())
                || outboxEvent.publicationRevision() != fullDefinition.publicationRevision()
                || !outboxEvent.fullDefinitionHash().equals(fullDefinition.contentHash())
                || !outboxEvent.baseDefinitionChecksum().equals(fullDefinition.baseDefinitionChecksum())
                || !outboxEvent.journeyHash().equals(fullDefinition.journeyHash())
                || !outboxEvent.scheduleHash().equals(fullDefinition.scheduleHash())
                || !outboxEvent.runtimeAvailabilityPinHash().equals(fullDefinition.runtimeAvailabilityHash())) {
            throw new IllegalArgumentException(
                    "a full publication commit's outbox event must describe its own publication");
        }
        retentionHolds = canonicalHolds(retentionHolds, baseDefinition);
    }

    /**
     * The commit's retention holds, canonical: bounded, duplicate-free, hold-ID ordered and each witnessing this
     * commit's own base publication.
     *
     * <p>A hold whose holder is not the base definition this commit freezes would keep a referenced version
     * alive on behalf of a publication that never happened, so it is not representable rather than merely
     * rejected later.
     */
    private static List<AudienceRetentionHold> canonicalHolds(
            List<AudienceRetentionHold> holds, PublishedCampaignDefinition definition) {
        if (holds == null || holds.size() > ReferenceLimits.MAX_DISTINCT_VERSIONS) {
            throw new IllegalArgumentException("a full publication commit's retention holds must be bounded");
        }
        List<AudienceRetentionHold> ordered = new ArrayList<>(holds.size());
        Set<String> holdIds = new LinkedHashSet<>();
        for (AudienceRetentionHold hold : holds) {
            if (hold == null || !holdIds.add(hold.holdId())
                    || !hold.holderAudienceId().equals(definition.campaignId())
                    || !hold.holderVersionId().equals(definition.publishedId())) {
                throw new IllegalArgumentException(
                        "a full publication commit's retention holds must witness its own publication");
            }
            ordered.add(hold);
        }
        ordered.sort(Comparator.comparing(AudienceRetentionHold::holdId));
        return List.copyOf(ordered);
    }
}