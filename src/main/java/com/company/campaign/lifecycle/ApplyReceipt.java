package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The immutable receipt of one accepted host Apply.
 *
 * <p>It binds the exact Plan that was applied, the caller's trusted ownership, the Campaign and Draft
 * identities that were allocated or advanced, the exact resulting revisions and the content hash of the
 * saved Draft. Construction re-proves that {@link #receiptHash()} is the canonical hash of the receipt
 * material, so a receipt whose bytes do not match its own hash is not representable and a repeated Apply
 * can return the original receipt as its idempotency witness.
 *
 * <p>The receipt is not a second editable truth: it records that one Draft revision was produced, and it
 * carries no Manifest, ChangeSet, Preview member or payload value.
 */
public record ApplyReceipt(
        String formatVersion,
        String receiptId,
        String tenantId,
        String programId,
        String userId,
        String planId,
        ContentHash planHash,
        String campaignId,
        long campaignRevision,
        String draftId,
        long draftRevision,
        ContentHash manifestHash,
        ContentHash contentHash,
        Instant appliedAt,
        ContentHash receiptHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ApplyReceipt {
        if (!LifecycleVersions.APPLY_RECEIPT_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("formatVersion");
        }
        requireIdentifier(receiptId, "receiptId");
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(planId, "planId");
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(draftId, "draftId");
        if (campaignRevision < 1 || draftRevision < 1) {
            throw invalid("revisions");
        }
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(contentHash, "contentHash");
        Objects.requireNonNull(appliedAt, "appliedAt");
        Objects.requireNonNull(receiptHash, "receiptHash");
        if (!receiptHash.equals(CANONICAL_JSON.hash(new Material(
                formatVersion, receiptId, tenantId, programId, userId, planId, planHash, campaignId,
                campaignRevision, draftId, draftRevision, manifestHash, contentHash, appliedAt)))) {
            throw invalid("receiptHash");
        }
    }

    /** Builds an Apply receipt and stamps it with its own canonical hash. */
    public static ApplyReceipt of(
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String planId,
            ContentHash planHash,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            ContentHash manifestHash,
            ContentHash contentHash,
            Instant appliedAt) {
        return new ApplyReceipt(
                LifecycleVersions.APPLY_RECEIPT_FORMAT_VERSION,
                receiptId,
                tenantId,
                programId,
                userId,
                planId,
                planHash,
                campaignId,
                campaignRevision,
                draftId,
                draftRevision,
                manifestHash,
                contentHash,
                appliedAt,
                CANONICAL_JSON.hash(new Material(
                        LifecycleVersions.APPLY_RECEIPT_FORMAT_VERSION, receiptId, tenantId, programId, userId,
                        planId, planHash, campaignId, campaignRevision, draftId, draftRevision, manifestHash,
                        contentHash, appliedAt)));
    }

    /** The exact editable Draft identity and revision this receipt witnesses. */
    public DraftIdentity draftIdentity() {
        return new DraftIdentity(draftId, draftRevision);
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
                "An Apply receipt must be a bounded, self-binding record.");
    }

    /** Redacted diagnostics: no tenant, program, user, campaign or Draft value is ever printed. */
    @Override
    public String toString() {
        return "ApplyReceipt[receiptId=" + receiptId
                + ", campaignRevision=" + campaignRevision
                + ", draftRevision=" + draftRevision
                + ", receiptHash=" + receiptHash + ']';
    }

    /** Package-private hash material; the owner identifiers are covered but never separately exposed. */
    record Material(
            String formatVersion,
            String receiptId,
            String tenantId,
            String programId,
            String userId,
            String planId,
            ContentHash planHash,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            ContentHash manifestHash,
            ContentHash contentHash,
            Instant appliedAt) {
    }
}