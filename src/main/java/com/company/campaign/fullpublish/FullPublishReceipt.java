package com.company.campaign.fullpublish;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The immutable receipt of one accepted host full publication.
 *
 * <p>It witnesses exactly one frozen full definition and the accepted T17 definition it contains: which full
 * preparation authorized it and at which hash, which bounded idempotency key and canonical request hash it was
 * bound to, the acknowledged risk codes, the full definition identifier, its complete publication revision and
 * content hash, the base published identity and checksum, the exact Journey and Schedule hashes, and the
 * publication instant.
 *
 * <p>Construction re-proves {@link #receiptHash()} against its own canonical material, so a receipt whose bytes
 * do not match its own hash is not representable, and an exact retry can return the original receipt as its
 * idempotency witness. The receipt carries no Manifest, Journey node, Schedule field, occurrence, Execution,
 * member value or payload.
 */
public record FullPublishReceipt(
        String formatVersion,
        String receiptId,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        String preparationId,
        ContentHash preparationHash,
        String idempotencyKey,
        ContentHash requestHash,
        String campaignId,
        String fullDefinitionId,
        long publicationRevision,
        ContentHash fullDefinitionHash,
        String basePublishedId,
        ContentHash baseDefinitionChecksum,
        ContentHash journeyHash,
        ContentHash scheduleHash,
        List<String> confirmedRiskCodes,
        Instant publishedAt,
        ContentHash receiptHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public FullPublishReceipt {
        if (!FullPublishVersions.FULL_RECEIPT_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_RECEIPT_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(receiptId, "receiptId");
        FullPublishSupport.requireIdentifier(tenantId, "tenantId");
        FullPublishSupport.requireIdentifier(programId, "programId");
        FullPublishSupport.requireIdentifier(userId, "userId");
        FullPublishSupport.requireIdentifier(dataScopeId, "dataScopeId");
        FullPublishSupport.requireIdentifier(preparationId, "preparationId");
        FullPublishSupport.requireIdentifier(idempotencyKey, "idempotencyKey");
        FullPublishSupport.requireIdentifier(campaignId, "campaignId");
        FullPublishSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        FullPublishSupport.requireIdentifier(basePublishedId, "basePublishedId");
        if (publicationRevision < 1) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_RECEIPT_INVALID, "publicationRevision");
        }
        Objects.requireNonNull(preparationHash, "preparationHash");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(baseDefinitionChecksum, "baseDefinitionChecksum");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        confirmedRiskCodes = FullPublishSupport.canonicalRiskCodes(confirmedRiskCodes, "confirmedRiskCodes");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(receiptHash, "receiptHash");
        if (!receiptHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, receiptId, tenantId, programId, userId, dataScopeId, preparationId,
                preparationHash, idempotencyKey, requestHash, campaignId, fullDefinitionId, publicationRevision,
                fullDefinitionHash, basePublishedId, baseDefinitionChecksum, journeyHash, scheduleHash,
                confirmedRiskCodes, publishedAt)))) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_RECEIPT_INVALID, "receiptHash");
        }
    }

    /** Builds a full publication receipt and stamps it with its own canonical hash. */
    public static FullPublishReceipt of(
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            FullPublishedDefinition definition,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
        Objects.requireNonNull(definition, "definition");
        List<String> risks = FullPublishSupport.canonicalRiskCodes(confirmedRiskCodes, "confirmedRiskCodes");
        return new FullPublishReceipt(
                FullPublishVersions.FULL_RECEIPT_FORMAT_VERSION,
                receiptId, tenantId, programId, userId, dataScopeId, preparationId, preparationHash,
                idempotencyKey, requestHash, definition.campaignId(), definition.fullDefinitionId(),
                definition.publicationRevision(), definition.contentHash(), definition.basePublishedId(),
                definition.baseDefinitionChecksum(), definition.journeyHash(), definition.scheduleHash(),
                risks, publishedAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.FULL_RECEIPT_FORMAT_VERSION,
                        receiptId, tenantId, programId, userId, dataScopeId, preparationId, preparationHash,
                        idempotencyKey, requestHash, definition.campaignId(), definition.fullDefinitionId(),
                        definition.publicationRevision(), definition.contentHash(), definition.basePublishedId(),
                        definition.baseDefinitionChecksum(), definition.journeyHash(), definition.scheduleHash(),
                        risks, publishedAt)));
    }

    /** The immutable identity and content hash of the full definition this receipt witnesses. */
    public PublishedIdentity fullIdentity() {
        return new PublishedIdentity(fullDefinitionId, publicationRevision, fullDefinitionHash);
    }

    private static Material material(
            String formatVersion,
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            ContentHash fullDefinitionHash,
            String basePublishedId,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
        return new Material(formatVersion, receiptId, tenantId, programId, userId, dataScopeId, preparationId,
                preparationHash, idempotencyKey, requestHash, campaignId, fullDefinitionId, publicationRevision,
                fullDefinitionHash, basePublishedId, baseDefinitionChecksum, journeyHash, scheduleHash,
                confirmedRiskCodes, publishedAt);
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "FullPublishReceipt[receiptId=" + receiptId
                + ", publicationRevision=" + publicationRevision
                + ", risks=" + confirmedRiskCodes.size()
                + ", receiptHash=" + receiptHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            ContentHash fullDefinitionHash,
            String basePublishedId,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
    }
}