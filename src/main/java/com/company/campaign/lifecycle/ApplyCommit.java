package com.company.campaign.lifecycle;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * One atomic Apply commit: the resulting Draft, its receipt and the audit event that must be written with
 * them.
 *
 * <p>The commit carries the expected base state as an explicit compare-and-set token, so a concurrent Apply
 * that lost the race is refused as stale rather than overwriting a revision it never read. A CREATE commit
 * carries no expected identity — its Campaign and Draft identities were server-allocated for this commit —
 * while an EDIT commit names the exact Campaign and Draft identity and revision the Plan was compiled
 * against.
 *
 * <p>The three writes are the unit of atomicity: an adapter records the audit event and publishes the
 * Draft and receipt together, or publishes none of them. A failing audit must leave no partial state.
 *
 * <p>Construction binds the three artifacts to each other in full. The receipt must witness exactly the
 * Draft this commit saves and the Plan it descends from — the same tenant, program and user, the same
 * Campaign and Draft identities at the same revisions, the same Manifest hash and the same Draft content
 * hash — the Draft must descend from the Plan the commit names, and the audit event must record the Apply
 * for that same owner, so a host-side caller cannot pair individually self-valid artifacts that contradict
 * one another.
 */
public record ApplyCommit(
        String planId,
        ContentHash planHash,
        String expectedCampaignId,
        Long expectedCampaignRevision,
        String expectedDraftId,
        Long expectedDraftRevision,
        SavedCampaignDraft draft,
        ApplyReceipt receipt,
        LifecycleAuditEvent auditEvent
) {

    public ApplyCommit {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(auditEvent, "auditEvent");
        boolean creates = expectedCampaignId == null;
        if (creates) {
            if (expectedCampaignRevision != null || expectedDraftId != null || expectedDraftRevision != null) {
                throw new IllegalArgumentException("a CREATE commit must carry no expected base identity");
            }
        } else {
            if (expectedCampaignRevision == null || expectedDraftId == null
                    || expectedDraftRevision == null) {
                throw new IllegalArgumentException("an EDIT commit requires its complete expected base state");
            }
            if (!expectedCampaignId.equals(draft.campaignId()) || !expectedDraftId.equals(draft.draftId())) {
                throw new IllegalArgumentException(
                        "an EDIT commit must edit the Campaign and Draft its base state names");
            }
        }
        if (!receipt.planId().equals(planId) || !receipt.planHash().equals(planHash)) {
            throw new IllegalArgumentException("a commit's receipt must bind the plan it applies");
        }
        if (!draft.sourcePlanId().equals(planId)) {
            throw new IllegalArgumentException("a commit's draft must descend from the plan it applies");
        }
        if (!receipt.tenantId().equals(draft.tenantId())
                || !receipt.programId().equals(draft.programId())
                || !receipt.userId().equals(draft.userId())) {
            throw new IllegalArgumentException("a commit's receipt must carry the draft's exact ownership");
        }
        if (!receipt.draftId().equals(draft.draftId())
                || receipt.draftRevision() != draft.draftRevision()
                || !receipt.campaignId().equals(draft.campaignId())
                || receipt.campaignRevision() != draft.campaignRevision()) {
            throw new IllegalArgumentException("a commit's receipt must witness the draft it commits");
        }
        if (!receipt.manifestHash().equals(draft.manifestHash())) {
            throw new IllegalArgumentException("a commit's receipt must witness the draft's own manifest");
        }
        if (!receipt.contentHash().equals(draft.draftHash())) {
            throw new IllegalArgumentException("a commit's receipt must witness the draft's own content hash");
        }
        if (auditEvent.action() != LifecycleAction.APPLY) {
            throw new IllegalArgumentException("an Apply commit's audit event must record the Apply");
        }
        if (!draft.tenantId().equals(auditEvent.tenantId())
                || !draft.programId().equals(auditEvent.programId())
                || !draft.userId().equals(auditEvent.userId())) {
            throw new IllegalArgumentException(
                    "an Apply commit's audit event must carry the committed Draft's exact ownership");
        }
    }

    /** True when this commit allocates a new Campaign and Draft identity. */
    public boolean creates() {
        return expectedCampaignId == null;
    }
}