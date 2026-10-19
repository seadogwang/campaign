package com.company.campaign.lifecycle;

import java.util.Objects;

/**
 * One atomic Prepare Publish commit: the preparation and the audit event that must be written with it.
 *
 * <p>The commit carries the expected Draft identity and revision as an explicit compare-and-set token, so a
 * preparation generated from a Draft that moved under it — a concurrent Apply, or a fork — is refused as
 * stale rather than freezing evidence for a revision nobody will publish.
 *
 * <p>The two writes are the unit of atomicity: an adapter records the audit event and stores the
 * preparation together, or stores neither. A failing audit must leave no partial state.
 *
 * <p>Construction binds the audit event to the preparation it witnesses: the event must carry exactly the
 * preparation's server-resolved owner and the one action a preparation is ever stored under, so the stored
 * record and the record of who stored it can never describe different operations.
 */
public record PublishPreparationCommit(
        String expectedCampaignId,
        String expectedDraftId,
        long expectedDraftRevision,
        PublishPreparation preparation,
        LifecycleAuditEvent auditEvent
) {

    public PublishPreparationCommit {
        Objects.requireNonNull(expectedCampaignId, "expectedCampaignId");
        Objects.requireNonNull(expectedDraftId, "expectedDraftId");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(auditEvent, "auditEvent");
        if (expectedDraftRevision < 1) {
            throw new IllegalArgumentException("an expected Draft revision must be positive");
        }
        if (!preparation.campaignId().equals(expectedCampaignId)
                || !preparation.draftId().equals(expectedDraftId)
                || preparation.draftRevision() != expectedDraftRevision) {
            throw new IllegalArgumentException("a preparation must freeze the Draft the commit expects");
        }
        if (auditEvent.action() != LifecycleAction.PREPARE_PUBLISH) {
            throw new IllegalArgumentException("a preparation commit's audit event must record the preparation");
        }
        if (!preparation.tenantId().equals(auditEvent.tenantId())
                || !preparation.programId().equals(auditEvent.programId())
                || !preparation.userId().equals(auditEvent.userId())) {
            throw new IllegalArgumentException(
                    "a preparation commit's audit event must carry the preparation's exact ownership");
        }
    }
}