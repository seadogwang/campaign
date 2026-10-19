package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CapabilityPin;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
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
 * The immutable Published Campaign Definition one explicit host Publish freezes.
 *
 * <p>It is the frozen execution material of a Campaign, and it is frozen in full: the exact resolved
 * Manifest the Draft held and its hash, the Draft identity and revision it was frozen from, the immutable
 * fork base, the source Plan and its hash, every capability/contract pin, the catalog and policy pins, the
 * safe Preview projection, the acknowledged risk codes, the production readiness pins the activation rested
 * on, the published revision and the published instant — all covered by {@link #contentChecksum()}.
 *
 * <p>Published bytes never change. There is no setter, no builder, no merge and no in-place edit: an EDIT of
 * a published Campaign is an explicit host-only fork that copies this definition into a new editable Draft
 * with a new Draft identity and the {@link #identity() PublishedIdentity} as its fork base, leaving this
 * record byte-identical.
 *
 * <p>{@link #toString()} prints no tenant, program, user or DataScope value and no Manifest content.
 */
public record PublishedCampaignDefinition(
        String formatVersion,
        String publishedId,
        long publishedRevision,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        String campaignId,
        long campaignRevision,
        String sourceDraftId,
        long sourceDraftRevision,
        PublishedIdentity forkBase,
        String planId,
        ContentHash planHash,
        ContentHash manifestHash,
        ContentHash draftHash,
        String catalogVersion,
        ContentHash catalogHash,
        String policyVersion,
        ContentHash policyHash,
        List<CapabilityPin> capabilityPins,
        List<PlanPreviewCard> previewCards,
        List<String> riskCodes,
        String readinessCapability,
        String readinessState,
        ContentHash gateInputsHash,
        ContentHash approvedDecisionHash,
        ResolvedCampaignManifest manifest,
        Instant publishedAt,
        ContentHash contentChecksum
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public PublishedCampaignDefinition {
        if (!LifecycleVersions.PUBLISHED_DEFINITION_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("formatVersion");
        }
        requireIdentifier(publishedId, "publishedId");
        if (publishedRevision < 1 || campaignRevision < 1 || sourceDraftRevision < 1) {
            throw invalid("revisions");
        }
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(dataScopeId, "dataScopeId");
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(sourceDraftId, "sourceDraftId");
        requireIdentifier(planId, "planId");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(draftHash, "draftHash");
        requireIdentifier(catalogVersion, "catalogVersion");
        Objects.requireNonNull(catalogHash, "catalogHash");
        requireIdentifier(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        capabilityPins = canonicalPins(capabilityPins);
        previewCards = canonicalCards(previewCards);
        riskCodes = canonicalRiskCodes(riskCodes);
        if (readinessCapability == null || readinessCapability.isBlank()) {
            throw invalid("readinessCapability");
        }
        if (readinessState == null || readinessState.isBlank()) {
            throw invalid("readinessState");
        }
        Objects.requireNonNull(gateInputsHash, "gateInputsHash");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(contentChecksum, "contentChecksum");
        if (!manifestHash.equals(manifest.manifestHash())) {
            throw invalid("manifestHash");
        }
        if (!contentChecksum.equals(CANONICAL_JSON.hash(materialFacts(
                formatVersion, publishedId, publishedRevision, tenantId, programId, userId, dataScopeId,
                campaignId, campaignRevision, sourceDraftId, sourceDraftRevision, forkBase, planId, planHash,
                manifestHash, draftHash, catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins,
                previewCards, riskCodes, readinessCapability, readinessState, gateInputsHash,
                approvedDecisionHash, publishedAt)))) {
            throw invalid("contentChecksum");
        }
    }

    /** Builds a Published Definition and stamps it with its own canonical content checksum. */
    public static PublishedCampaignDefinition of(
            String publishedId,
            long publishedRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String sourceDraftId,
            long sourceDraftRevision,
            PublishedIdentity forkBase,
            String planId,
            ContentHash planHash,
            ContentHash draftHash,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            List<CapabilityPin> capabilityPins,
            List<PlanPreviewCard> previewCards,
            List<String> riskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            ResolvedCampaignManifest manifest,
            Instant publishedAt) {
        Objects.requireNonNull(manifest, "manifest");
        List<CapabilityPin> pins = canonicalPins(capabilityPins);
        List<PlanPreviewCard> cards = canonicalCards(previewCards);
        List<String> risks = canonicalRiskCodes(riskCodes);
        ContentHash manifestHash = manifest.manifestHash();
        return new PublishedCampaignDefinition(
                LifecycleVersions.PUBLISHED_DEFINITION_FORMAT_VERSION,
                publishedId, publishedRevision, tenantId, programId, userId, dataScopeId, campaignId,
                campaignRevision, sourceDraftId, sourceDraftRevision, forkBase, planId, planHash, manifestHash,
                draftHash, catalogVersion, catalogHash, policyVersion, policyHash, pins, cards, risks,
                readinessCapability, readinessState, gateInputsHash, approvedDecisionHash, manifest,
                publishedAt,
                CANONICAL_JSON.hash(materialFacts(
                        LifecycleVersions.PUBLISHED_DEFINITION_FORMAT_VERSION,
                        publishedId, publishedRevision, tenantId, programId, userId, dataScopeId, campaignId,
                        campaignRevision, sourceDraftId, sourceDraftRevision, forkBase, planId, planHash,
                        manifestHash, draftHash, catalogVersion, catalogHash, policyVersion, policyHash, pins,
                        cards, risks, readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                        publishedAt)));
    }

    /**
     * The immutable identity and content checksum of this published version.
     *
     * <p>It is exactly the value a forked Draft records as its fork base, so a fork can never name a
     * version whose bytes differ from the ones it copied.
     */
    public PublishedIdentity identity() {
        return new PublishedIdentity(publishedId, publishedRevision, contentChecksum);
    }

    private static List<CapabilityPin> canonicalPins(List<CapabilityPin> pins) {
        if (pins == null) {
            throw invalid("capabilityPins");
        }
        List<CapabilityPin> ordered = new ArrayList<>(pins.size());
        Set<String> distinct = new LinkedHashSet<>();
        for (CapabilityPin pin : pins) {
            if (pin == null || !distinct.add(pin.capabilityCode() + '|' + pin.contractVersion())) {
                throw invalid("capabilityPins");
            }
            ordered.add(pin);
        }
        ordered.sort(CapabilityPin::order);
        return List.copyOf(ordered);
    }

    private static List<PlanPreviewCard> canonicalCards(List<PlanPreviewCard> cards) {
        if (cards == null || cards.size() > LifecycleSupport.MAX_PREVIEW_CARDS) {
            throw invalid("previewCards");
        }
        List<PlanPreviewCard> copy = new ArrayList<>(cards.size());
        Set<String> identities = new LinkedHashSet<>();
        for (PlanPreviewCard card : cards) {
            if (card == null || !identities.add(card.cardIdentity())) {
                throw invalid("previewCards");
            }
            copy.add(card);
        }
        return List.copyOf(copy);
    }

    private static List<String> canonicalRiskCodes(List<String> riskCodes) {
        if (riskCodes == null) {
            throw invalid("riskCodes");
        }
        List<String> ordered = new ArrayList<>(riskCodes.size());
        for (String code : riskCodes) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw invalid("riskCodes");
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
                "A Published Campaign Definition must be a bounded, self-binding immutable record.");
    }

    private static Material materialFacts(
            String formatVersion,
            String publishedId,
            long publishedRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String sourceDraftId,
            long sourceDraftRevision,
            PublishedIdentity forkBase,
            String planId,
            ContentHash planHash,
            ContentHash manifestHash,
            ContentHash draftHash,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            List<CapabilityPin> capabilityPins,
            List<PlanPreviewCard> previewCards,
            List<String> riskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant publishedAt) {
        return new Material(
                formatVersion, publishedId, publishedRevision, tenantId, programId, userId, dataScopeId,
                campaignId, campaignRevision, sourceDraftId, sourceDraftRevision, forkBase, planId, planHash,
                manifestHash, draftHash, catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins,
                previewCards, riskCodes, readinessCapability, readinessState, gateInputsHash,
                approvedDecisionHash, publishedAt);
    }

    /** Redacted diagnostics: no tenant, program, user, DataScope, Campaign or Draft value is ever printed. */
    @Override
    public String toString() {
        return "PublishedCampaignDefinition[publishedId=" + publishedId
                + ", publishedRevision=" + publishedRevision
                + ", campaignRevision=" + campaignRevision
                + ", components=" + manifest.components().size()
                + ", cards=" + previewCards.size()
                + ", risks=" + riskCodes.size()
                + ", contentChecksum=" + contentChecksum + ']';
    }

    /** Package-private hash material; the owner identifiers are covered but never separately exposed. */
    record Material(
            String formatVersion,
            String publishedId,
            long publishedRevision,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String sourceDraftId,
            long sourceDraftRevision,
            PublishedIdentity forkBase,
            String planId,
            ContentHash planHash,
            ContentHash manifestHash,
            ContentHash draftHash,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            List<CapabilityPin> capabilityPins,
            List<PlanPreviewCard> previewCards,
            List<String> riskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant publishedAt) {
    }
}