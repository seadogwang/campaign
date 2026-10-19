package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one editable, server-owned Campaign Draft a Campaign owns, saved at one exact revision.
 *
 * <p>Its semantic content is <em>exactly</em> the resolved Manifest of the Plan an explicit host Apply
 * accepted: this contract introduces no second editable truth, no parallel Segment draft and no
 * client-authored mutation document. Validation and Preview stay immutable derived evidence referenced by
 * hash from that Manifest and its bound preview cards.
 *
 * <p>The snapshot is immutable and hash-bound: construction re-proves that {@link #manifestHash()} is the
 * Manifest's own hash and that {@link #draftHash()} is the canonical hash of every material field, so a
 * Draft whose bytes do not match its own hash is not representable. {@link #draftRevision()} is the
 * concurrency token an EDIT is written against; applying a Plan whose base revision is not the current one
 * is refused rather than merged.
 *
 * <p>{@link #forkBase()} is recorded fact, never an editing target: a Draft forked from an immutable
 * Published version names that version here, and the published bytes themselves are never addressed or
 * changed by this contract.
 */
public record SavedCampaignDraft(
        String formatVersion,
        String tenantId,
        String programId,
        String userId,
        String campaignId,
        long campaignRevision,
        String draftId,
        long draftRevision,
        PublishedIdentity forkBase,
        ResolvedCampaignManifest manifest,
        ContentHash manifestHash,
        String sourcePlanId,
        Instant savedAt,
        ContentHash draftHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public SavedCampaignDraft {
        if (!LifecycleVersions.DRAFT_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("formatVersion");
        }
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(draftId, "draftId");
        requireIdentifier(sourcePlanId, "sourcePlanId");
        if (campaignRevision < 1 || draftRevision < 1) {
            throw invalid("revisions");
        }
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(savedAt, "savedAt");
        Objects.requireNonNull(draftHash, "draftHash");
        if (!manifestHash.equals(manifest.manifestHash())) {
            throw invalid("manifestHash");
        }
        // The compact canonical constructor runs before the record fields are assigned, so the material is
        // built from the constructor parameters (which shadow the fields) rather than the fields themselves.
        if (!draftHash.equals(CANONICAL_JSON.hash(new Material(
                formatVersion, tenantId, programId, userId, campaignId, campaignRevision, draftId,
                draftRevision, forkBase, manifestHash, sourcePlanId, savedAt)))) {
            throw invalid("draftHash");
        }
    }

    /** Builds a Draft snapshot and stamps it with its own canonical hash. */
    public static SavedCampaignDraft of(
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            PublishedIdentity forkBase,
            ResolvedCampaignManifest manifest,
            String sourcePlanId,
            Instant savedAt) {
        Objects.requireNonNull(manifest, "manifest");
        return new SavedCampaignDraft(
                LifecycleVersions.DRAFT_FORMAT_VERSION,
                tenantId,
                programId,
                userId,
                campaignId,
                campaignRevision,
                draftId,
                draftRevision,
                forkBase,
                manifest,
                manifest.manifestHash(),
                sourcePlanId,
                savedAt,
                CANONICAL_JSON.hash(new Material(
                        LifecycleVersions.DRAFT_FORMAT_VERSION, tenantId, programId, userId, campaignId,
                        campaignRevision, draftId, draftRevision, forkBase, manifest.manifestHash(),
                        sourcePlanId, savedAt)));
    }

    /** The exact editable Draft identity and revision an EDIT compile binds to. */
    public DraftIdentity draftIdentity() {
        return new DraftIdentity(draftId, draftRevision);
    }

    /** True when this Draft descends from an immutable Published version. */
    public boolean isFork() {
        return forkBase != null;
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
                "A saved Campaign Draft must be a bounded, self-binding snapshot.");
    }

    /** Redacted diagnostics: no tenant, program, user, campaign or Draft value is ever printed. */
    @Override
    public String toString() {
        return "SavedCampaignDraft[revision=" + draftRevision
                + ", campaignRevision=" + campaignRevision
                + ", forked=" + isFork()
                + ", components=" + manifest.components().size()
                + ", draftHash=" + draftHash + ']';
    }

    /** Package-private hash material; the owner identifiers are covered but never separately exposed. */
    record Material(
            String formatVersion,
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
            PublishedIdentity forkBase,
            ContentHash manifestHash,
            String sourcePlanId,
            Instant savedAt) {
    }
}