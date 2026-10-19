package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.CapabilityPin;
import com.company.campaign.builder.compiler.DraftIdentity;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.builder.compiler.RiskSummary;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The expiring, server-stored authority one explicit host Prepare Publish produces.
 *
 * <p>It freezes everything a later confirmation must re-prove rather than re-derive: the caller's trusted
 * owner and DataScope, the Campaign and Draft identity with their exact revisions, the immutable fork base,
 * the source Plan, Manifest and Draft hashes, the frozen catalog/policy and capability/contract pins, the
 * safe Preview projection and the exact risk codes the confirmation must acknowledge, the production
 * readiness pins, the creation instant and the expiry instant.
 *
 * <p>It is not a second editable truth and not a cache of the Draft: it carries no ChangeSet, no raw
 * Manifest bytes beyond the hash that identifies them, no caller-supplied payload and no readiness fact a
 * caller could declare. {@link #previewCards()} holds only the already-safe plan projection, and
 * {@link #requiredRiskCodes()} holds only bounded machine codes.
 *
 * <p>The record is immutable and hash-bound: construction re-proves that {@link #preparationHash()} is the
 * canonical hash of every material field, so a preparation whose bytes do not match its own hash is not
 * representable. All four lifecycle states are decidable from its own frozen facts plus the durable
 * {@code consumed} flag the store owns.
 */
public record PublishPreparation(
        String formatVersion,
        String preparationId,
        String tenantId,
        String programId,
        String userId,
        String dataScopeId,
        String campaignId,
        long campaignRevision,
        String draftId,
        long draftRevision,
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
        List<String> requiredRiskCodes,
        String readinessCapability,
        String readinessState,
        ContentHash gateInputsHash,
        ContentHash approvedDecisionHash,
        Instant createdAt,
        Instant expiresAt,
        ContentHash preparationHash,
        // The one optional slot on the preparation, and it exists only for byte compatibility: an absent
        // optional slot must be omitted from the canonical material rather than serialized as a null, so every
        // accepted reference-free preparation hashes to exactly the bytes it always did.
        @JsonInclude(JsonInclude.Include.NON_NULL) ReferencePreparationEvidence referenceEvidence
) {

    /** The default preparation lifetime; a host may inject a shorter or longer one within the bound. */
    public static final Duration DEFAULT_LIFETIME = Duration.ofMinutes(15);

    /** The longest lifetime any preparation may declare, so a frozen preparation cannot outlive its inputs. */
    public static final Duration MAX_LIFETIME = Duration.ofMinutes(60);

    /** The shortest lifetime any preparation may declare, so a preparation is never born already spent. */
    public static final Duration MIN_LIFETIME = Duration.ofSeconds(30);

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    /**
     * The accepted reference-free preparation constructor, kept as a backward-compatible overload.
     *
     * <p>It supplies no reference evidence, so every preparation a reference-free plan produced constructs
     * exactly the accepted record it always did — and the compact constructor it delegates to then re-proves
     * that the frozen Preview projection carries no reference evidence either.
     */
    public PublishPreparation(
            String formatVersion,
            String preparationId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
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
            List<String> requiredRiskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant createdAt,
            Instant expiresAt,
            ContentHash preparationHash) {
        this(formatVersion, preparationId, tenantId, programId, userId, dataScopeId, campaignId,
                campaignRevision, draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins, previewCards,
                requiredRiskCodes, readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                createdAt, expiresAt, preparationHash, null);
    }

    public PublishPreparation {
        if (!LifecycleVersions.PUBLISH_PREPARATION_FORMAT_VERSION.equals(formatVersion)) {
            throw invalid("formatVersion");
        }
        requireIdentifier(preparationId, "preparationId");
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(programId, "programId");
        requireIdentifier(userId, "userId");
        requireIdentifier(dataScopeId, "dataScopeId");
        requireIdentifier(campaignId, "campaignId");
        requireIdentifier(draftId, "draftId");
        requireIdentifier(planId, "planId");
        if (campaignRevision < 1 || draftRevision < 1) {
            throw invalid("revisions");
        }
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(draftHash, "draftHash");
        requireIdentifier(catalogVersion, "catalogVersion");
        Objects.requireNonNull(catalogHash, "catalogHash");
        requireIdentifier(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        capabilityPins = canonicalPins(capabilityPins);
        previewCards = canonicalCards(previewCards);
        requiredRiskCodes = canonicalRiskCodes(requiredRiskCodes);
        if (readinessCapability == null || readinessCapability.isBlank()) {
            throw invalid("readinessCapability");
        }
        if (readinessState == null || readinessState.isBlank()) {
            throw invalid("readinessState");
        }
        Objects.requireNonNull(gateInputsHash, "gateInputsHash");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(preparationHash, "preparationHash");
        requireReferenceCoherence(previewCards, referenceEvidence);
        requireBoundedLifetime(createdAt, expiresAt);
        if (!preparationHash.equals(CANONICAL_JSON.hash(materialFacts(
                formatVersion, preparationId, tenantId, programId, userId, dataScopeId, campaignId,
                campaignRevision, draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins, previewCards,
                requiredRiskCodes, readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                createdAt, expiresAt, referenceEvidence)))) {
            throw invalid("preparationHash");
        }
    }

    /**
     * Requires the preparation's reference evidence and its frozen Preview projection to agree exactly.
     *
     * <p>A reference-bearing plan is detected from the trusted plan evidence its cards carry, never from a
     * caller flag, so this is where a stored preparation is re-proved to still say what its own frozen material
     * says. A preparation that carries a reference-bearing card and no reference evidence would let a
     * reference-bearing plan be prepared as if it were reference-free; one that carries reference evidence and
     * no reference-bearing card would claim a dependency it does not have. Either way the record would
     * misstate what it authorizes, so both are refused rather than stored.
     */
    private static void requireReferenceCoherence(
            List<PlanPreviewCard> cards, ReferencePreparationEvidence referenceEvidence) {
        ReferencePlanEvidence cardEvidence = null;
        int bearing = 0;
        for (PlanPreviewCard card : cards) {
            ReferencePlanEvidence evidence = card.referenceEvidence();
            if (evidence != null) {
                bearing++;
                cardEvidence = evidence;
            }
        }
        if (referenceEvidence == null) {
            if (bearing != 0) {
                throw invalid("referenceEvidenceMissing");
            }
            return;
        }
        if (bearing != 1 || cardEvidence == null
                || !cardEvidence.capabilityId().equals(referenceEvidence.capabilityId())
                || !cardEvidence.referenceDependenciesHash()
                        .equals(referenceEvidence.referenceDependenciesHash())
                || !cardEvidence.referenceInputsHash().equals(referenceEvidence.referenceInputsHash())) {
            throw invalid("referenceEvidenceMismatch");
        }
    }

    /** Builds a preparation and stamps it with its own canonical hash. */
    public static PublishPreparation of(
            String preparationId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
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
            List<String> requiredRiskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant createdAt,
            Instant expiresAt) {
        return of(preparationId, tenantId, programId, userId, dataScopeId, campaignId, campaignRevision,
                draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins, previewCards,
                requiredRiskCodes, readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                createdAt, expiresAt, null);
    }

    /**
     * Builds a preparation carrying the exact reference activation evidence of a reference-bearing plan.
     *
     * <p>The reference-free {@code of(...)} above is the accepted one and stays byte-identical: it supplies no
     * reference evidence, so a plan with no reference dependencies freezes exactly the material it always did.
     */
    public static PublishPreparation of(
            String preparationId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
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
            List<String> requiredRiskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant createdAt,
            Instant expiresAt,
            ReferencePreparationEvidence referenceEvidence) {
        List<CapabilityPin> pins = canonicalPins(capabilityPins);
        List<PlanPreviewCard> cards = canonicalCards(previewCards);
        List<String> risks = canonicalRiskCodes(requiredRiskCodes);
        return new PublishPreparation(
                LifecycleVersions.PUBLISH_PREPARATION_FORMAT_VERSION,
                preparationId, tenantId, programId, userId, dataScopeId, campaignId, campaignRevision,
                draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                catalogVersion, catalogHash, policyVersion, policyHash, pins, cards, risks,
                readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                createdAt, expiresAt,
                CANONICAL_JSON.hash(materialFacts(
                        LifecycleVersions.PUBLISH_PREPARATION_FORMAT_VERSION,
                        preparationId, tenantId, programId, userId, dataScopeId, campaignId, campaignRevision,
                        draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                        catalogVersion, catalogHash, policyVersion, policyHash, pins, cards, risks,
                        readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                        createdAt, expiresAt, referenceEvidence)),
                referenceEvidence);
    }

    /** The host-only reference a Publish confirmation presents for this preparation. */
    public PublishPreparationRef ref() {
        return new PublishPreparationRef(preparationId, preparationHash);
    }

    /** The exact editable Draft identity and revision this preparation froze. */
    public DraftIdentity draftIdentity() {
        return new DraftIdentity(draftId, draftRevision);
    }

    /** The declared lifetime of this preparation. */
    public Duration lifetime() {
        return Duration.between(createdAt, expiresAt);
    }

    /**
     * The state decidable from the preparation's own durable facts and expiry alone.
     *
     * <p>A consumed preparation is consumed forever, so consumption outranks expiry; a preparation at or
     * past its expiry instant is expired. Anything else is {@link PublishPreparationState#PREPARED}, and a
     * confirmation may still downgrade it to {@link PublishPreparationState#STALE} once it has re-read the
     * host state this preparation froze.
     */
    public PublishPreparationState durableState(Instant now, boolean consumed) {
        Objects.requireNonNull(now, "now");
        if (consumed) {
            return PublishPreparationState.CONSUMED;
        }
        return expiresAt.isAfter(now) ? PublishPreparationState.PREPARED : PublishPreparationState.EXPIRED;
    }

    /** True when the two risk-code collections are exactly equal as sets, order and repetition aside. */
    public boolean requiresExactly(Set<String> confirmations) {
        Objects.requireNonNull(confirmations, "confirmations");
        return confirmations.size() == requiredRiskCodes.size()
                && new LinkedHashSet<>(requiredRiskCodes).equals(new LinkedHashSet<>(confirmations));
    }

    private static void requireBoundedLifetime(Instant createdAt, Instant expiresAt) {
        Duration lifetime = Duration.between(createdAt, expiresAt);
        if (lifetime.compareTo(MIN_LIFETIME) < 0 || lifetime.compareTo(MAX_LIFETIME) > 0) {
            throw invalid("preparationLifetime");
        }
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
            throw invalid("requiredRiskCodes");
        }
        if (riskCodes.size() > RiskSummary.MAX_RISK_CODES) {
            throw invalid("requiredRiskCodes");
        }
        List<String> ordered = new ArrayList<>(riskCodes.size());
        for (String code : riskCodes) {
            if (code == null || !MACHINE_CODE.matcher(code).matches()) {
                throw invalid("requiredRiskCodes");
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
                "A publish preparation must be a bounded, self-binding server record.");
    }

    /**
     * The hash material without the hash itself.
     *
     * <p>It is a static form because a record's compact constructor runs before its fields are assigned:
     * the constructor must hash the canonicalized parameters it was given, not the fields.
     */
    private static Material materialFacts(
            String formatVersion,
            String preparationId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
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
            List<String> requiredRiskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant createdAt,
            Instant expiresAt,
            ReferencePreparationEvidence referenceEvidence) {
        return new Material(
                formatVersion, preparationId, tenantId, programId, userId, dataScopeId, campaignId,
                campaignRevision, draftId, draftRevision, forkBase, planId, planHash, manifestHash, draftHash,
                catalogVersion, catalogHash, policyVersion, policyHash, capabilityPins, previewCards,
                requiredRiskCodes, readinessCapability, readinessState, gateInputsHash, approvedDecisionHash,
                createdAt, expiresAt, referenceEvidence);
    }

    /** Redacted diagnostics: no tenant, program, user, DataScope, Campaign or Draft value is ever printed. */
    @Override
    public String toString() {
        return "PublishPreparation[preparationId=" + preparationId
                + ", draftRevision=" + draftRevision
                + ", cards=" + previewCards.size()
                + ", risks=" + requiredRiskCodes.size()
                + ", readiness=" + readinessState
                + ", reference=" + (referenceEvidence == null ? "none" : "activation")
                + ", expiresAt=" + expiresAt
                + ", preparationHash=" + preparationHash + ']';
    }

    /** Package-private hash material; the owner identifiers are covered but never separately exposed. */
    record Material(
            String formatVersion,
            String preparationId,
            String tenantId,
            String programId,
            String userId,
            String dataScopeId,
            String campaignId,
            long campaignRevision,
            String draftId,
            long draftRevision,
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
            List<String> requiredRiskCodes,
            String readinessCapability,
            String readinessState,
            ContentHash gateInputsHash,
            ContentHash approvedDecisionHash,
            Instant createdAt,
            Instant expiresAt,
            // Omitted from the canonical material entirely when absent, so a reference-free preparation hashes
            // to exactly the accepted bytes rather than to material carrying an explicit null.
            @JsonInclude(JsonInclude.Include.NON_NULL) ReferencePreparationEvidence referenceEvidence) {
    }
}