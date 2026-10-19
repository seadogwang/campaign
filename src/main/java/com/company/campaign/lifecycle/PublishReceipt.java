package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The immutable receipt of one accepted host Publish.
 *
 * <p>It witnesses exactly one frozen Published Definition: which preparation authorized it and at which
 * hash, which bounded idempotency key and canonical request hash it was bound to, the acknowledged risk
 * codes, the Draft and root revisions it was frozen from, and the identity and content checksum of the
 * immutable definition it produced.
 *
 * <p>Construction re-proves that {@link #receiptHash()} is the canonical hash of the receipt material, so a
 * receipt whose bytes do not match its own hash is not representable and a repeated confirmation can return
 * the original receipt as its idempotency witness. The receipt carries no Manifest, ChangeSet, Preview
 * member or payload value.
 */
public record PublishReceipt(
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
        String publishedId,
        long publishedRevision,
        String campaignId,
        long campaignRevision,
        String draftId,
        long draftRevision,
        String planId,
        ContentHash planHash,
        ContentHash manifestHash,
        ContentHash contentChecksum,
        List<String> confirmedRiskCodes,
        Instant publishedAt,
        ContentHash receiptHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public PublishReceipt {
        if (!LifecycleVersions.PUBLISH_RECEIPT_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("formatVersion");
        }
        requireIdentifier(receiptId, "receiptId");
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(dataScopeId, "dataScopeId");
        requireIdentifier(preparationId, "preparationId");
        requireIdentifier(idempotencyKey, "idempotencyKey");
        requireIdentifier(publishedId, "publishedId");
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(draftId, "draftId");
        requireIdentifier(planId, "planId");
        if (publishedRevision < 1 || campaignRevision < 1 || draftRevision < 1) {
            throw invalid("revisions");
        }
        Objects.requireNonNull(preparationHash, "preparationHash");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(contentChecksum, "contentChecksum");
        confirmedRiskCodes = canonicalRiskCodes(confirmedRiskCodes);
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(receiptHash, "receiptHash");
        if (!receiptHash.equals(CANONICAL_JSON.hash(materialFacts(
                formatVersion, receiptId, tenantId, programId, userId, dataScopeId, preparationId,
                preparationHash, idempotencyKey, requestHash, publishedId, publishedRevision, campaignId,
                campaignRevision, draftId, draftRevision, planId, planHash, manifestHash, contentChecksum,
                confirmedRiskCodes, publishedAt)))) {
            throw invalid("receiptHash");
        }
    }

    /** Builds a publish receipt and stamps it with its own canonical hash. */
    public static PublishReceipt of(
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String preparationId,
            ContentHash preparationHash,
            String idempotencyKey,
            ContentHash requestHash,
            String publishedId,
            long publishedRevision,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            String planId,
            ContentHash planHash,
            ContentHash manifestHash,
            ContentHash contentChecksum,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
        List<String> risks = canonicalRiskCodes(confirmedRiskCodes);
        return new PublishReceipt(
                LifecycleVersions.PUBLISH_RECEIPT_FORMAT_VERSION,
                receiptId, tenantId, programId, userId, dataScopeId, preparationId, preparationHash,
                idempotencyKey, requestHash, publishedId, publishedRevision, campaignId, campaignRevision,
                draftId, draftRevision, planId, planHash, manifestHash, contentChecksum, risks, publishedAt,
                CANONICAL_JSON.hash(materialFacts(
                        LifecycleVersions.PUBLISH_RECEIPT_FORMAT_VERSION,
                        receiptId, tenantId, programId, userId, dataScopeId, preparationId, preparationHash,
                        idempotencyKey, requestHash, publishedId, publishedRevision, campaignId,
                        campaignRevision, draftId, draftRevision, planId, planHash, manifestHash,
                        contentChecksum, risks, publishedAt)));
    }

    /** The immutable identity and content checksum of the Published Definition this receipt witnesses. */
    public PublishedIdentity publishedIdentity() {
        return new PublishedIdentity(publishedId, publishedRevision, contentChecksum);
    }

    private static List<String> canonicalRiskCodes(List<String> riskCodes) {
        if (riskCodes == null) {
            throw invalid("confirmedRiskCodes");
        }
        List<String> ordered = new ArrayList<>(riskCodes.size());
        for (String code : riskCodes) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw invalid("confirmedRiskCodes");
            }
            ordered.add(code);
        }
        ordered.sort(String::compareTo);
        return List.copyOf(new LinkedHashSet<>(ordered));
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw invalid(field);
        }
    }

    private static LifecycleException invalid(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                invariant,
                "A publish receipt must be a bounded, self-binding immutable record.");
    }

    private static Material materialFacts(
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
            String publishedId,
            long publishedRevision,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            String planId,
            ContentHash planHash,
            ContentHash manifestHash,
            ContentHash contentChecksum,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
        return new Material(
                formatVersion, receiptId, tenantId, programId, userId, dataScopeId, preparationId,
                preparationHash, idempotencyKey, requestHash, publishedId, publishedRevision, campaignId,
                campaignRevision, draftId, draftRevision, planId, planHash, manifestHash, contentChecksum,
                confirmedRiskCodes, publishedAt);
    }

    /** Redacted diagnostics: no tenant, program, user, DataScope, Campaign or Draft value is ever printed. */
    @Override
    public String toString() {
        return "PublishReceipt[receiptId=" + receiptId
                + ", publishedRevision=" + publishedRevision
                + ", draftRevision=" + draftRevision
                + ", risks=" + confirmedRiskCodes.size()
                + ", receiptHash=" + receiptHash + ']';
    }

    /** Package-private hash material; the owner identifiers are covered but never separately exposed. */
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
            String publishedId,
            long publishedRevision,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            String planId,
            ContentHash planHash,
            ContentHash manifestHash,
            ContentHash contentChecksum,
            List<String> confirmedRiskCodes,
            Instant publishedAt) {
    }
}