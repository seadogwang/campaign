package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The safe outward result of one accepted host Prepare Publish.
 *
 * <p>It carries exactly what a later confirmation must present and acknowledge — the preparation reference,
 * its expiry, the exact Draft revision it froze, the acknowledged risk codes and the already-safe Preview
 * projection — and nothing else. It carries no Manifest, ChangeSet, component payload, source Plan content
 * or trusted identifier: the preparation itself stays server side and is reachable only by hash.
 */
public record PreparePublishResult(
        PublishPreparationRef preparationRef,
        DraftIdentity draftIdentity,
        String readinessState,
        List<String> requiredRiskCodes,
        List<PlanPreviewCard> previewCards,
        Instant createdAt,
        Instant expiresAt
) {

    public PreparePublishResult {
        Objects.requireNonNull(preparationRef, "preparationRef");
        Objects.requireNonNull(draftIdentity, "draftIdentity");
        Objects.requireNonNull(readinessState, "readinessState");
        requiredRiskCodes = List.copyOf(Objects.requireNonNull(requiredRiskCodes, "requiredRiskCodes"));
        previewCards = List.copyOf(Objects.requireNonNull(previewCards, "previewCards"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    /** The server-stored preparation identifier a confirmation presents. */
    public String preparationId() {
        return preparationRef.preparationId();
    }

    /** The exact preparation hash a confirmation must re-present. */
    public ContentHash preparationHash() {
        return preparationRef.preparationHash();
    }

    @Override
    public String toString() {
        return "PreparePublishResult[preparationId=" + preparationId()
                + ", draftRevision=" + draftIdentity.revision()
                + ", risks=" + requiredRiskCodes.size()
                + ", cards=" + previewCards.size()
                + ", expiresAt=" + expiresAt + ']';
    }
}