package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * One atomic fork commit: the new editable Draft and the audit event that must be written with it.
 *
 * <p>The commit carries the Campaign, the exact Campaign revision and the exact pre-fork Draft identity and
 * content hash the fork was computed from, so a fork that lost a race with a concurrent Apply or a second
 * fork is refused as stale rather than silently replacing a Draft nobody read. The Campaign revision is the
 * token rather than the Draft revision because a fork allocates a brand-new Draft identity, whose revision
 * legitimately starts again; the pre-fork Draft identity and hash are carried alongside it so the adapter
 * can re-prove inside the transaction that the Draft it replaces is byte-for-byte the one the service read.
 *
 * <p>It writes no Published Definition: a fork only ever reads one, so the published bytes of the version it
 * descends from cannot change here. The two writes are the unit of atomicity, and a failing audit must leave
 * no partial state.
 *
 * <p>Construction binds the new Draft to the audit event that witnesses it — the event must record the one
 * action a fork is stored under, carry the new Draft's exact owner and its content hash — so the committed
 * Draft and the record of its creation can never describe different operations.
 */
public record ForkCommit(
        String expectedCampaignId,
        long expectedCampaignRevision,
        DraftIdentity expectedDraft,
        ContentHash expectedDraftHash,
        SavedCampaignDraft draft,
        LifecycleAuditEvent auditEvent
) {

    public ForkCommit {
        Objects.requireNonNull(expectedCampaignId, "expectedCampaignId");
        Objects.requireNonNull(expectedDraft, "expectedDraft");
        Objects.requireNonNull(expectedDraftHash, "expectedDraftHash");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(auditEvent, "auditEvent");
        if (expectedCampaignRevision < 1) {
            throw new IllegalArgumentException("an expected Campaign revision must be positive");
        }
        if (!draft.campaignId().equals(expectedCampaignId)) {
            throw new IllegalArgumentException("a fork commit's Draft must belong to the expected Campaign");
        }
        if (draft.campaignRevision() != expectedCampaignRevision + 1L) {
            throw new IllegalArgumentException("a fork must advance the Campaign revision exactly once");
        }
        if (!draft.isFork()) {
            throw new IllegalArgumentException("a fork commit must carry a Draft with an immutable fork base");
        }
        if (draft.draftId().equals(expectedDraft.draftId())) {
            // A fork that reuses the identity it forks from would mutate the Draft it was told to copy.
            throw new IllegalArgumentException("a fork must allocate a new editable Draft identity");
        }
        if (auditEvent.action() != LifecycleAction.FORK_PUBLISHED) {
            throw new IllegalArgumentException("a fork commit's audit event must record the fork");
        }
        if (!draft.tenantId().equals(auditEvent.tenantId())
                || !draft.programId().equals(auditEvent.programId())
                || !draft.userId().equals(auditEvent.userId())) {
            throw new IllegalArgumentException(
                    "a fork commit's audit event must carry the forked Draft's exact ownership");
        }
        if (!draft.draftHash().equals(auditEvent.contentHash())) {
            throw new IllegalArgumentException(
                    "a fork commit's audit event must witness the forked Draft's content hash");
        }
    }
}