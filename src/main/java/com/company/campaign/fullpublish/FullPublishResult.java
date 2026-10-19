package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The safe outward result of one accepted host full publication.
 *
 * <p>It carries exactly what a caller needs to address the version it just published and to reconcile its own
 * request — the full preparation reference, the immutable identities and content hashes of the full definition
 * and of the accepted T17 definition it contains, the exact Journey and Schedule hashes, the acknowledged risk
 * codes and the publication instant — and nothing else.
 *
 * <p>It carries no Manifest, Journey node, Schedule field, occurrence, retention hold, owner identifier or
 * trusted context: the published bytes and the server-side receipt stay server side and are reachable only by
 * identity and content hash.
 */
public record FullPublishResult(
        String preparationId,
        ContentHash preparationHash,
        String idempotencyKey,
        PublishedIdentity baseIdentity,
        PublishedIdentity fullIdentity,
        ContentHash journeyHash,
        ContentHash scheduleHash,
        List<String> confirmedRiskCodes,
        Instant publishedAt
) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public FullPublishResult {
        if (preparationId == null || !IDENTIFIER.matcher(preparationId).matches()) {
            throw new IllegalArgumentException("a full publish result requires a bounded preparationId");
        }
        Objects.requireNonNull(preparationHash, "preparationHash");
        if (idempotencyKey == null || !IDENTIFIER.matcher(idempotencyKey).matches()) {
            throw new IllegalArgumentException("a full publish result requires a bounded idempotencyKey");
        }
        Objects.requireNonNull(baseIdentity, "baseIdentity");
        Objects.requireNonNull(fullIdentity, "fullIdentity");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        confirmedRiskCodes = List.copyOf(Objects.requireNonNull(confirmedRiskCodes, "confirmedRiskCodes"));
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    /** Builds the safe outward result of one full publication receipt the boundary produced. */
    public static FullPublishResult of(FullPublishReceipt receipt, PublishedIdentity baseIdentity) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(baseIdentity, "baseIdentity");
        return new FullPublishResult(
                receipt.preparationId(), receipt.preparationHash(), receipt.idempotencyKey(), baseIdentity,
                receipt.fullIdentity(), receipt.journeyHash(), receipt.scheduleHash(),
                receipt.confirmedRiskCodes(), receipt.publishedAt());
    }

    /** The immutable identifier of the full definition this publication froze. */
    public String fullDefinitionId() {
        return fullIdentity.publishedId();
    }

    /** The publication revision this full publication produced for the Campaign. */
    public long publicationRevision() {
        return fullIdentity.revision();
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "FullPublishResult[preparationId=" + preparationId
                + ", fullDefinitionId=" + fullIdentity.publishedId()
                + ", publicationRevision=" + fullIdentity.revision()
                + ", journeyHash=" + journeyHash
                + ", scheduleHash=" + scheduleHash + ']';
    }
}