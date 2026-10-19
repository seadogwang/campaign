package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;

import java.util.Objects;

/**
 * One atomic Prepare Full Publish commit: the full preparation and the audit event that must be written with it.
 *
 * <p>The commit carries the expected saved Draft identity and revision as an explicit compare-and-set token, so
 * a full preparation generated from a Draft that moved under it — a concurrent Apply, a fork or another
 * publication — is refused as stale rather than freezing evidence for a revision nobody will publish.
 *
 * <p>The two writes are the unit of atomicity: an adapter records the audit event and stores the preparation
 * together, or stores neither. A failing audit must leave no partial state.
 *
 * <p>Construction binds the audit event to the preparation it witnesses: the event must carry exactly the
 * preparation's server-resolved owner and the one action a full preparation is ever stored under.
 */
public record FullPreparationCommit(
        String expectedCampaignId,
        String expectedDraftId,
        long expectedDraftRevision,
        FullPublishPreparation preparation,
        LifecycleAuditEvent auditEvent
) {

    public FullPreparationCommit {
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
            throw new IllegalArgumentException("a full preparation must freeze the Draft the commit expects");
        }
        if (auditEvent.action() != LifecycleAction.FULL_PUBLISH) {
            throw new IllegalArgumentException(
                    "a full preparation commit's audit event must record the full preparation");
        }
        if (!preparation.tenantId().equals(auditEvent.tenantId())
                || !preparation.programId().equals(auditEvent.programId())
                || !preparation.userId().equals(auditEvent.userId())) {
            throw new IllegalArgumentException(
                    "a full preparation commit's audit event must carry the preparation's exact ownership");
        }
    }
}