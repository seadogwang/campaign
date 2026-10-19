package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The safe outward result of one accepted host Publish.
 *
 * <p>It carries exactly what a caller needs to address the version it just published and to reconcile its
 * own request — the immutable {@link PublishedIdentity} of the frozen definition, the exact Draft revision
 * it was frozen from, the Manifest hash, the acknowledged risk codes, the idempotency key it presented and
 * the published instant — and nothing else. It carries no Manifest, ChangeSet, component payload or Preview
 * member: the published bytes stay server side and are reachable only by identity and content checksum.
 *
 * <p>The immutable {@link PublishReceipt} stays server side: it exists so an exact repeat of the same
 * idempotency key and request can return its original idempotency witness, and it covers the caller's
 * trusted ownership and DataScope in its own checksum, but nothing here re-exposes them. This projection is
 * built only from a receipt the boundary itself produced.
 */
public record PublishResult(
        String preparationId,
        ContentHash preparationHash,
        String idempotencyKey,
        PublishedIdentity publishedIdentity,
        DraftIdentity draftIdentity,
        ContentHash manifestHash,
        List<String> confirmedRiskCodes,
        Instant publishedAt
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public PublishResult {
        if (preparationId == null || !IDENTIFIER.matcher(preparationId).matches()) {
            throw new IllegalArgumentException("a publish result requires a bounded preparationId");
        }
        Objects.requireNonNull(preparationHash, "preparationHash");
        if (idempotencyKey == null || !IDENTIFIER.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("a publish result requires a bounded idempotencyKey");
        }
        Objects.requireNonNull(publishedIdentity, "publishedIdentity");
        Objects.requireNonNull(draftIdentity, "draftIdentity");
        Objects.requireNonNull(manifestHash, "manifestHash");
        confirmedRiskCodes = List.copyOf(Objects.requireNonNull(confirmedRiskCodes, "confirmedRiskCodes"));
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /** Builds the safe outward result of one receipt the lifecycle boundary produced. */
    public static PublishResult of(PublishReceipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        return new PublishResult(
                receipt.preparationId(),
                receipt.preparationHash(),
                receipt.idempotencyKey(),
                receipt.publishedIdentity(),
                new DraftIdentity(receipt.draftId(), receipt.draftRevision()),
                receipt.manifestHash(),
                receipt.confirmedRiskCodes(),
                receipt.publishedAt());
    }

    /** The immutable identifier of the Published Definition this publication froze. */
    public String publishedId() {
        return publishedIdentity.publishedId();
    }

    /** The published revision this publication produced for the Campaign. */
    public long publishedRevision() {
        return publishedIdentity.revision();
    }

    @Override
    public String toString() {
        return "PublishResult[preparationId=" + preparationId
                + ", publishedId=" + publishedIdentity.publishedId()
                + ", publishedRevision=" + publishedIdentity.revision()
                + ", draftRevision=" + draftIdentity.revision() + ']';
    }
}