package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.contracts.Issue;
import com.company.campaign.contracts.PlanRef;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The immutable, non-forgeable, server-stored Campaign Plan.
 *
 * <p>It carries a deterministic full-hash-derived {@code planId} and {@code planHash}, the explicit compile
 * and expiry instants, the trusted tenant/program/user/campaign target identity, the exact base revisions
 * and fork facts, the proposal and Manifest hashes, every used contract version plus the catalog and policy
 * version/hash, the complete resolved Manifest, the ordered bounded validation issues, the semantic diff,
 * the internal ChangeSet and the {@code clientRef -> logicalId} mapping.
 *
 * <p>The class is final, the only constructor is package-private and there is no public factory, builder,
 * deserializer or subclass escape hatch, so only this compiler can produce a Plan. Construction re-proves
 * every internal invariant and recomputes the plan hash, so a Plan whose material does not match its own
 * identifier is not representable.
 *
 * <p>There is one canonical plan material hash: {@link #planHash()} covers the complete immutable semantic
 * material and every version discriminator, and {@link #planId()} is derived from all 64 digest hex
 * characters of that hash. The material never contains the identifier or the hash itself, so the derivation
 * is not circular. Identical explicit inputs therefore produce byte-identical material, hash and identifier.
 *
 * <p>The trusted identifiers are internal: {@link #toString()} deliberately omits them.
 */
public final class CampaignPlan {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String planFormatVersion;
    private final String compilerVersion;
    private final String planId;
    private final ContentHash planHash;
    private final Instant compiledAt;
    private final Instant expiresAt;
    private final String tenantId;
    private final String programId;
    private final String userId;
    private final String campaignId;
    private final Long baseCampaignRevision;
    private final String baseDraftId;
    private final Long baseDraftRevision;
    private final PublishedIdentity forkBase;
    private final ContentHash proposalHash;
    private final ContentHash manifestHash;
    private final List<CapabilityPin> capabilityPins;
    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final String policyVersion;
    private final ContentHash policyHash;
    private final ResolvedCampaignManifest manifest;
    private final List<Issue> validationIssues;
    private final SemanticDiff semanticDiff;
    private final ChangeSet changeSet;
    private final List<ClientRefBinding> clientRefBindings;
    private final List<PlanPreviewCard> previewCards;
    private final List<CapabilityPlanPreviewCard> capabilityPreviewCards;
    private final RiskSummary riskSummary;

    /**
     * The accepted T15/T16/T18 shape: a Plan that carries no generic T19 capability preview card.
     *
     * <p>It is kept so every accepted call site keeps compiling and keeps producing byte-identical output; a
     * Plan built through it carries the genuinely empty capability preview card list, which is omitted from
     * the canonical material entirely.
     */
    static CampaignPlan create(
            String planFormatVersion,
            String compilerVersion,
            Instant compiledAt,
            Instant expiresAt,
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            ContentHash proposalHash,
            ContentHash manifestHash,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            ResolvedCampaignManifest manifest,
            List<Issue> validationIssues,
            SemanticDiff semanticDiff,
            ChangeSet changeSet,
            List<ClientRefBinding> clientRefBindings,
            List<PlanPreviewCard> previewCards,
            RiskSummary riskSummary) {
        return create(planFormatVersion, compilerVersion, compiledAt, expiresAt, tenantId, programId, userId,
                campaignId, baseCampaignRevision, baseDraftId, baseDraftRevision, forkBase, proposalHash,
                manifestHash, capabilityPins, catalogVersion, catalogHash, policyVersion, policyHash, manifest,
                validationIssues, semanticDiff, changeSet, clientRefBindings, previewCards, List.of(), riskSummary);
    }

    /**
     * The one production path: canonicalize, compute the plan material hash, derive {@code planId} from all
     * 64 digest hex characters and then construct. Package-private so only this package — in practice only
     * the compiler — can build a Plan.
     */
    static CampaignPlan create(
            String planFormatVersion,
            String compilerVersion,
            Instant compiledAt,
            Instant expiresAt,
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            ContentHash proposalHash,
            ContentHash manifestHash,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            ResolvedCampaignManifest manifest,
            List<Issue> validationIssues,
            SemanticDiff semanticDiff,
            ChangeSet changeSet,
            List<ClientRefBinding> clientRefBindings,
            List<PlanPreviewCard> previewCards,
            List<CapabilityPlanPreviewCard> capabilityPreviewCards,
            RiskSummary riskSummary) {
        List<CapabilityPin> canonicalPins = canonicalPins(capabilityPins);
        List<ClientRefBinding> canonicalBindings = canonicalBindings(clientRefBindings);
        ContentHash hash = ContentHash.sha256(CANONICAL_JSON.write(new Material(
                planFormatVersion, compilerVersion, compiledAt, expiresAt, tenantId, programId, userId,
                campaignId, baseCampaignRevision, baseDraftId, baseDraftRevision, forkBase, proposalHash,
                manifestHash, canonicalPins, catalogVersion, catalogHash, policyVersion, policyHash,
                List.copyOf(validationIssues), semanticDiff.entries(), changeSet.formatVersion(),
                changeSet.nodes(), canonicalBindings, List.copyOf(previewCards),
                canonicalCapabilityPreviewCards(capabilityPreviewCards, manifest),
                journeyTopologyHash(manifest), riskSummary)));
        return new CampaignPlan(
                planFormatVersion, compilerVersion,
                hash.value().substring(CanonicalJson.HASH_PREFIX.length()), hash,
                compiledAt, expiresAt, tenantId, programId, userId, campaignId, baseCampaignRevision, baseDraftId,
                baseDraftRevision, forkBase, proposalHash, manifestHash, canonicalPins, catalogVersion,
                catalogHash, policyVersion, policyHash, manifest, validationIssues, semanticDiff, changeSet,
                canonicalBindings, previewCards, capabilityPreviewCards, riskSummary);
    }

    CampaignPlan(
            String planFormatVersion,
            String compilerVersion,
            String planId,
            ContentHash planHash,
            Instant compiledAt,
            Instant expiresAt,
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            ContentHash proposalHash,
            ContentHash manifestHash,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            ResolvedCampaignManifest manifest,
            List<Issue> validationIssues,
            SemanticDiff semanticDiff,
            ChangeSet changeSet,
            List<ClientRefBinding> clientRefBindings,
            List<PlanPreviewCard> previewCards,
            List<CapabilityPlanPreviewCard> capabilityPreviewCards,
            RiskSummary riskSummary) {
        this.planFormatVersion = requireVersion(
                planFormatVersion, CompilerVersions.PLAN_FORMAT_VERSION, "planFormatVersion");
        this.compilerVersion = requireVersion(compilerVersion, CompilerVersions.COMPILER_VERSION, "compilerVersion");
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(planHash, "planHash");
        Objects.requireNonNull(compiledAt, "compiledAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(proposalHash, "proposalHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(semanticDiff, "semanticDiff");
        Objects.requireNonNull(changeSet, "changeSet");
        Objects.requireNonNull(riskSummary, "riskSummary");
        this.compiledAt = compiledAt;
        this.expiresAt = expiresAt;
        this.tenantId = tenantId;
        this.programId = programId;
        this.userId = userId;
        this.campaignId = campaignId;
        this.baseCampaignRevision = baseCampaignRevision;
        this.baseDraftId = baseDraftId;
        this.baseDraftRevision = baseDraftRevision;
        this.forkBase = forkBase;
        this.proposalHash = proposalHash;
        this.manifestHash = manifestHash;
        this.catalogVersion = Objects.requireNonNull(catalogVersion, "catalogVersion");
        this.catalogHash = Objects.requireNonNull(catalogHash, "catalogHash");
        this.policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        this.policyHash = Objects.requireNonNull(policyHash, "policyHash");
        this.manifest = manifest;
        this.capabilityPins = canonicalPins(capabilityPins);
        this.validationIssues = List.copyOf(Objects.requireNonNull(validationIssues, "validationIssues"));
        this.semanticDiff = semanticDiff;
        this.changeSet = changeSet;
        this.clientRefBindings = canonicalBindings(clientRefBindings);
        this.previewCards = canonicalPreviewCards(previewCards);
        this.capabilityPreviewCards = canonicalCapabilityPreviewCards(capabilityPreviewCards, manifest);
        this.riskSummary = riskSummary;

        if (!manifest.manifestHash().equals(manifestHash)) {
            throw inconsistent("manifestHash", "A plan's manifest hash must be its manifest's hash.");
        }
        if (!manifest.compilerVersion().equals(compilerVersion)) {
            throw inconsistent("compilerVersion", "A plan and its manifest must share one compiler version.");
        }
        // Every fact a Plan and its Manifest both carry is re-proved exactly equal, nullity included; a
        // self-consistent plan hash alone must never make a cross-inconsistent pair representable.
        requireEqual(manifest.campaignId(), campaignId, "campaignId");
        requireEqual(manifest.baseCampaignRevision(), baseCampaignRevision, "baseCampaignRevision");
        requireEqual(manifest.baseDraftId(), baseDraftId, "baseDraftId");
        requireEqual(manifest.baseDraftRevision(), baseDraftRevision, "baseDraftRevision");
        requireEqual(manifest.forkBase(), forkBase, "forkBase");
        requireEqual(manifest.capabilityPins(), this.capabilityPins, "capabilityPins");
        requireEqual(manifest.catalogVersion(), catalogVersion, "catalogVersion");
        requireEqual(manifest.catalogHash(), catalogHash, "catalogHash");
        requireEqual(manifest.policyVersion(), policyVersion, "policyVersion");
        requireEqual(manifest.policyHash(), policyHash, "policyHash");

        // The instants are re-proved here as well as in CompileInstants: a Plan is constructible from raw
        // values, so ordering and the bounded lifetime must hold at the Plan boundary too.
        if (!expiresAt.isAfter(compiledAt)) {
            throw inconsistent("expiryNotAfterCompile",
                    "A plan expiry instant must be strictly after its compile instant.");
        }
        if (Duration.between(compiledAt, expiresAt).compareTo(CompileInstants.MAX_LIFETIME) > 0) {
            throw inconsistent("lifetimeBound", "A plan expiry instant must lie within the bounded lifetime.");
        }

        ContentHash expected = ContentHash.sha256(CANONICAL_JSON.write(material()));
        if (!planHash.equals(expected)) {
            throw inconsistent("planHash", "The declared plan hash is not the canonical hash of the plan.");
        }
        String derivedId = planHash.value().substring(CanonicalJson.HASH_PREFIX.length());
        if (!derivedId.equals(planId)) {
            throw inconsistent("planId", "A plan identifier must be derived from all 64 digest hex characters.");
        }
        this.planHash = planHash;
        this.planId = planId;
    }

    /** The Plan document format version. */
    public String planFormatVersion() {
        return planFormatVersion;
    }

    /** The compiler version that produced this Plan. */
    public String compilerVersion() {
        return compilerVersion;
    }

    /** The deterministic identifier: all 64 digest hex characters of {@link #planHash()}. */
    public String planId() {
        return planId;
    }

    /** The canonical plan material hash. */
    public ContentHash planHash() {
        return planHash;
    }

    public Instant compiledAt() {
        return compiledAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** The trusted tenant, internal only and never part of {@link #toString()}. */
    public String tenantId() {
        return tenantId;
    }

    /** The trusted program, internal only and never part of {@link #toString()}. */
    public String programId() {
        return programId;
    }

    /** The trusted user, internal only and never part of {@link #toString()}. */
    public String userId() {
        return userId;
    }

    /** The trusted campaign target, or null for a CREATE compile. Internal only. */
    public String campaignId() {
        return campaignId;
    }

    /** The exact Campaign revision this Plan was compiled against, or null for CREATE. Internal only. */
    public Long baseCampaignRevision() {
        return baseCampaignRevision;
    }

    public String baseDraftId() {
        return baseDraftId;
    }

    public Long baseDraftRevision() {
        return baseDraftRevision;
    }

    public PublishedIdentity forkBase() {
        return forkBase;
    }

    public ContentHash proposalHash() {
        return proposalHash;
    }

    public ContentHash manifestHash() {
        return manifestHash;
    }

    public List<CapabilityPin> capabilityPins() {
        return capabilityPins;
    }

    public String catalogVersion() {
        return catalogVersion;
    }

    public ContentHash catalogHash() {
        return catalogHash;
    }

    public String policyVersion() {
        return policyVersion;
    }

    public ContentHash policyHash() {
        return policyHash;
    }

    public ResolvedCampaignManifest manifest() {
        return manifest;
    }

    /**
     * The Journey topology frozen by this Plan's Manifest, or null when the Campaign carries none.
     *
     * <p>It is the exact graph a later Apply, fork or full publication must preserve: the topology is a fact of
     * the Manifest, so a different topology supplied at publication time is a mismatch rather than a
     * substitution.
     */
    public com.company.campaign.journey.graph.JourneyTopology journeyTopology() {
        return manifest.journeyTopology();
    }

    /** The ordered, bounded validation issues the frozen policy reported. */
    public List<Issue> validationIssues() {
        return validationIssues;
    }

    public SemanticDiff semanticDiff() {
        return semanticDiff;
    }

    /** The internal ChangeSet; host-only and never accepted from a client or model. */
    public ChangeSet changeSet() {
        return changeSet;
    }

    /** The {@code clientRef -> logicalId} mapping, in declaration order. */
    public List<ClientRefBinding> clientRefBindings() {
        return clientRefBindings;
    }

    /**
     * The immutable, bound plan preview cards contributed by resolved components.
     *
     * <p>They are carried in manifest order and every one is bound to a component the plan's manifest holds;
     * a non-Segment or unchanged component contributes none. The complete card material is covered by
     * {@link #planHash()}, so identical inputs always produce byte-identical cards.
     */
    public List<PlanPreviewCard> previewCards() {
        return previewCards;
    }

    /**
     * The immutable generic T19 capability preview cards contributed by resolved components.
     *
     * <p>They are carried in manifest order and every one is re-proved to bind exactly a component the plan's
     * manifest holds; a component that projects no such card contributes none, so the honest default is the
     * empty list. That empty list is omitted from the canonical material entirely, which is what keeps a Plan
     * with no T19 card byte-identical to the accepted T15/T16/T18 Plan; a non-empty list is covered by
     * {@link #planHash()}, so identical inputs always produce byte-identical cards.
     */
    public List<CapabilityPlanPreviewCard> capabilityPreviewCards() {
        return capabilityPreviewCards;
    }

    /** The bounded risk-summary extension slot; always empty for T15. */
    public RiskSummary riskSummary() {
        return riskSummary;
    }

    /** The host-only reference a caller may present for a later explicit Apply. */
    public PlanRef planRef() {
        return new PlanRef(planId, planHash, expiresAt);
    }

    /** The canonical UTF-8 bytes of the complete plan material. */
    public byte[] canonicalMaterialBytes() {
        return CANONICAL_JSON.write(material());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignPlan plan
                && Objects.equals(planId, plan.planId)
                && Objects.equals(planHash, plan.planHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(planId, planHash);
    }

    /**
     * Reconstructs the exact immutable CampaignPlan from stored canonical material and manifest.
     *
     * <p>This is the durable reconstruction path: it deserializes the canonical material and the
     * manifest from the stored bytes, re-creates the intermediate objects (SemanticDiff, ChangeSet)
     * and passes everything through the genuine {@link #create} factory, so every invariant,
     * hash verification and compatibility check runs exactly as it did at original construction
     * time. The reconstructed plan is byte-identical to the original.
     *
     * <p>Public so the durable persistence adapter (in {@code com.company.campaign.persistence})
     * can call it. No external caller can fabricate a Plan — every invariant and hash verification
     * runs through the genuine {@link #create} factory.
     */
    public static CampaignPlan reconstruct(byte[] materialBytes, String manifestJson) {
        if (materialBytes == null || materialBytes.length == 0 || manifestJson == null || manifestJson.isBlank()) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID, "reconstruct",
                    "Both canonical material and manifest are required to reconstruct a Campaign Plan.");
        }
        Material material = CANONICAL_JSON.read(new String(materialBytes, java.nio.charset.StandardCharsets.UTF_8),
                Material.class);
        ResolvedCampaignManifest manifest = ResolvedCampaignManifest.reconstruct(manifestJson);
        SemanticDiff semanticDiff = new SemanticDiff(material.diffEntries());
        ChangeSet changeSet = new ChangeSet(material.changeSetFormatVersion(), material.changeSetNodes());
        CampaignPlan plan = CampaignPlan.create(
                material.planFormatVersion(),
                material.compilerVersion(),
                material.compiledAt(),
                material.expiresAt(),
                material.tenantId(),
                material.programId(),
                material.userId(),
                material.campaignId(),
                material.baseCampaignRevision(),
                material.baseDraftId(),
                material.baseDraftRevision(),
                material.forkBase(),
                material.proposalHash(),
                material.manifestHash(),
                material.capabilityPins(),
                material.catalogVersion(),
                material.catalogHash(),
                material.policyVersion(),
                material.policyHash(),
                manifest,
                material.validationIssues(),
                semanticDiff,
                changeSet,
                material.clientRefBindings(),
                material.previewCards(),
                material.capabilityPreviewCards(),
                material.riskSummary());
        // Validates that the stored journeyTopologyHash equals the reconstructed manifest's
        // topology hash — including null symmetry — so a tampered plan material hash that
        // names a topology that differs from the manifest's is rejected.
        ContentHash storedTopologyHash = material.journeyTopologyHash();
        ContentHash manifestTopologyHash = manifest.journeyTopology() == null
                ? null : manifest.journeyTopology().topologyHash();
        if (!java.util.Objects.equals(storedTopologyHash, manifestTopologyHash)) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID, "journeyTopologyHash",
                    "The stored journey topology hash does not equal the reconstructed manifest"
                            + " topology hash.");
        }
        // Validates that the exact input canonical material bytes equal the reconstructed
        // plan's canonical material bytes, so noncanonical or tampered input is rejected.
        if (!java.util.Arrays.equals(materialBytes, plan.canonicalMaterialBytes())) {
            throw new CompilerException(
                    CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID, "canonicalMaterialBytes",
                    "The stored canonical material bytes do not equal the reconstructed plan's"
                            + " canonical material bytes.");
        }
        return plan;
    }

    /** Redacted diagnostics: no tenant, program, user or campaign value is ever printed. */
    @Override
    public String toString() {
        return "CampaignPlan[planId=" + planId
                + ", planHash=" + planHash
                + ", compiledAt=" + compiledAt
                + ", expiresAt=" + expiresAt
                + ", components=" + manifest.components().size()
                + ", changes=" + changeSet.size()
                + ", issues=" + validationIssues.size()
                + ", diffEntries=" + semanticDiff.entries().size() + ']';
    }

    private Material material() {
        // The manifest is covered through its hash; the diff and ChangeSet are covered through their closed
        // typed values, which serialize deterministically and carry no behavior.
        return new Material(
                planFormatVersion,
                compilerVersion,
                compiledAt,
                expiresAt,
                tenantId,
                programId,
                userId,
                campaignId,
                baseCampaignRevision,
                baseDraftId,
                baseDraftRevision,
                forkBase,
                proposalHash,
                manifestHash,
                capabilityPins,
                catalogVersion,
                catalogHash,
                policyVersion,
                policyHash,
                validationIssues,
                semanticDiff.entries(),
                changeSet.formatVersion(),
                changeSet.nodes(),
                clientRefBindings,
                previewCards,
                capabilityPreviewCards,
                journeyTopologyHash(manifest),
                riskSummary);
    }

    /**
     * The frozen Journey-topology hash of a Plan's Manifest, or null when the Campaign carries no topology.
     *
     * <p>The Manifest already covers the topology through its own hash; recording the topology hash here as
     * well makes the Plan material state the exact frozen graph explicitly, so a Plan can be compared and
     * audited on the topology alone without re-deriving it.
     */
    private static ContentHash journeyTopologyHash(ResolvedCampaignManifest manifest) {
        return manifest.journeyTopology() == null ? null : manifest.journeyTopology().topologyHash();
    }

    private static List<CapabilityPin> canonicalPins(List<CapabilityPin> pins) {
        if (pins == null) {
            throw inconsistent("capabilityPins", "A plan requires its capability pin set.");
        }
        List<CapabilityPin> ordered = new ArrayList<>(pins);
        ordered.sort(CapabilityPin::order);
        return List.copyOf(ordered);
    }

    private static List<ClientRefBinding> canonicalBindings(List<ClientRefBinding> bindings) {
        if (bindings == null) {
            throw inconsistent("clientRefBindings", "A plan requires its client reference mapping.");
        }
        List<ClientRefBinding> copy = new ArrayList<>(bindings.size());
        Set<String> seen = new HashSet<>();
        for (ClientRefBinding binding : bindings) {
            if (binding == null) {
                throw inconsistent("clientRefBindings", "A plan must not carry a null binding.");
            }
            if (!seen.add(binding.clientRef())) {
                throw inconsistent("clientRefBindings", "A plan must not repeat one client reference.");
            }
            copy.add(binding);
        }
        Set<String> logicalIds = new HashSet<>();
        for (ClientRefBinding binding : copy) {
            if (!logicalIds.add(binding.logicalId())) {
                throw inconsistent("clientRefBindings", "Two client references must not share one logicalId.");
            }
        }
        return List.copyOf(copy);
    }

    /**
     * Re-proves the preview-card projection: no null card, no repeated card identity, and every card bound to
     * a component the plan's manifest actually holds.
     *
     * <p>The second check is what makes a stale or mixed card fail closed: a card for a component that was
     * deleted, never merged or replaced by a different capability version cannot enter the plan material.
     */
    private List<PlanPreviewCard> canonicalPreviewCards(List<PlanPreviewCard> cards) {
        if (cards == null) {
            throw inconsistent("previewCards", "A plan requires its preview-card collection.");
        }
        List<PlanPreviewCard> copy = new ArrayList<>(cards.size());
        Set<String> identities = new HashSet<>();
        for (PlanPreviewCard card : cards) {
            if (card == null) {
                throw inconsistent("previewCards", "A plan must not carry a null preview card.");
            }
            if (!identities.add(card.cardIdentity())) {
                throw inconsistent("previewCards", "A plan must not repeat one preview-card identity.");
            }
            if (manifest.component(card.componentLogicalId()) == null) {
                throw inconsistent("previewCards",
                        "A preview card must bind to a component the plan's manifest holds.");
            }
            copy.add(card);
        }
        return List.copyOf(copy);
    }

    /**
     * Re-proves the generic T19 capability preview card projection: no null card, no repeated card identity,
     * and every card bound exactly to a component the plan's manifest actually holds.
     *
     * <p>The binding check is what makes a forged, stale, deleted or replaced card fail closed at the Plan
     * boundary as well as in the compiler: a card that names another component, declares a capability or
     * contract version the manifest component does not carry, pins a payload hash the manifest never
     * normalized or a reference hash the manifest's resolved references do not produce cannot enter the plan
     * material — even when a Plan is constructed directly from raw values.
     *
     * <p>The frozen runtime compatibility is a contract fact rather than a manifest fact, so it is re-proved
     * where the contract is still in hand, in the compiler, and not here.
     */
    private static List<CapabilityPlanPreviewCard> canonicalCapabilityPreviewCards(
            List<CapabilityPlanPreviewCard> cards,
            ResolvedCampaignManifest manifest) {
        if (cards == null) {
            throw inconsistent("capabilityPreviewCards",
                    "A plan requires its capability preview card collection.");
        }
        List<CapabilityPlanPreviewCard> copy = new ArrayList<>(cards.size());
        Set<String> identities = new HashSet<>();
        for (CapabilityPlanPreviewCard card : cards) {
            if (card == null) {
                throw inconsistent("capabilityPreviewCards", "A plan must not carry a null capability card.");
            }
            if (!identities.add(CapabilityPreviewCards.cardIdentity(card))) {
                throw inconsistent("capabilityPreviewCards",
                        "A plan must not repeat one capability preview card identity.");
            }
            ResolvedComponent component = manifest.component(card.componentLogicalId());
            if (component == null) {
                throw inconsistent("capabilityPreviewCards",
                        "A capability preview card must bind to a component the plan's manifest holds.");
            }
            if (CapabilityPreviewCards.bindingInvariant(card, component) != null) {
                throw inconsistent("capabilityPreviewCardBinding",
                        "A capability preview card must bind exactly the component it projects.");
            }
            copy.add(card);
        }
        return List.copyOf(copy);
    }

    private static void requireEqual(Object manifestFact, Object planFact, String invariant) {
        if (!Objects.equals(manifestFact, planFact)) {
            throw inconsistent(invariant, "A plan and its manifest must agree on every shared fact.");
        }
    }

    private static String requireVersion(String value, String expected, String invariant) {
        if (!expected.equals(value)) {
            throw inconsistent(invariant, "A plan must carry the one supported version discriminator.");
        }
        return value;
    }

    private static CompilerException inconsistent(String invariant, String message) {
        return new CompilerException(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT, invariant, message);
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(
            String planFormatVersion,
            String compilerVersion,
            Instant compiledAt,
            Instant expiresAt,
            String tenantId,
            String programId,
            String userId,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            ContentHash proposalHash,
            ContentHash manifestHash,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            List<Issue> validationIssues,
            List<DiffEntry> diffEntries,
            String changeSetFormatVersion,
            List<ChangeSetNode> changeSetNodes,
            List<ClientRefBinding> clientRefBindings,
            List<PlanPreviewCard> previewCards,
            // The one genuinely optional slot of the plan material: it is omitted from the canonical bytes
            // when it is empty, so a Plan that carries no T19 card keeps the accepted bytes, hash, equality
            // and JSON exactly rather than gaining an empty array.
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<CapabilityPlanPreviewCard> capabilityPreviewCards,
            // The one genuinely optional topology slot of the plan material: it is omitted from the canonical
            // bytes when the Campaign carries none, so a topology-free Plan keeps the accepted T15 bytes,
            // hash, equality and JSON exactly rather than gaining a null.
            @JsonInclude(JsonInclude.Include.NON_NULL) ContentHash journeyTopologyHash,
            RiskSummary riskSummary) {

        /** Supplies the default empty value for the optional slots the canonical form omits. */
        Material {
            if (capabilityPreviewCards == null) {
                capabilityPreviewCards = List.of();
            }
            // journeyTopologyHash is genuinely nullable — it stays null when omitted.
        }
    }
}