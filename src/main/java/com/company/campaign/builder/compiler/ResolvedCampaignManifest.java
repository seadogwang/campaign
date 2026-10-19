package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The complete, immutable, non-forgeable target state of one Campaign.
 *
 * <p>It carries the target campaign identity and mode, the exact base Draft/fork facts, the complete final
 * ordered component collection — inherited unchanged components included — every component's stable
 * {@code logicalId}, exact capability and contract version, composition slot and normalized canonical value,
 * every catalog reference resolved to a stable {@code logicalId}/{@code versionId} pair, and the pinned
 * capability, catalog and policy versions and hashes.
 *
 * <p>It contains no {@code DELETE} or {@code MOVE}, no client-only operation or {@code clientRef}, no
 * unresolved code, no label synonym, no authorization fact and no trusted user or tenant value inside a
 * capability payload.
 *
 * <p>The Manifest is compiler-produced and externally non-forgeable: the class is final, the only
 * constructor is package-private, and there is no public factory, builder, deserializer or subclass escape
 * hatch. Construction re-proves every internal invariant and re-computes {@link #manifestHash()} from the
 * canonical bytes, so a Manifest that does not match its own hash is not representable. Collections are
 * defensively copied and ordered.
 */
public final class ResolvedCampaignManifest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    private final String manifestFormatVersion;
    private final String compilerVersion;
    private final ProposalMode mode;
    private final String campaignId;
    private final Long baseCampaignRevision;
    private final String baseDraftId;
    private final Long baseDraftRevision;
    private final PublishedIdentity forkBase;
    private final List<ResolvedComponent> components;
    private final List<CapabilityPin> capabilityPins;
    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final String policyVersion;
    private final ContentHash policyHash;
    private final JourneyTopology journeyTopology;
    private final ContentHash manifestHash;

    /**
     * The accepted T15/T20 shape: a Manifest that honestly carries no Journey topology.
     *
     * <p>It is kept so every accepted call site keeps compiling and keeps producing byte-identical output; the
     * topology is omitted from the canonical material entirely rather than written as a null or an empty
     * object. It never <em>defaults</em> a Campaign with Flow nodes to an empty topology: a Manifest built
     * through it simply states that no topology was frozen.
     */
    static ResolvedCampaignManifest create(
            String manifestFormatVersion,
            String compilerVersion,
            ProposalMode mode,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            List<ResolvedComponent> components,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash) {
        return create(manifestFormatVersion, compilerVersion, mode, campaignId, baseCampaignRevision,
                baseDraftId, baseDraftRevision, forkBase, components, capabilityPins, catalogVersion,
                catalogHash, policyVersion, policyHash, null);
    }

    /**
     * The one production path: canonicalize the inputs, compute the hash from the canonical bytes and then
     * construct. Package-private so only this package — in practice only the compiler — can build a Manifest.
     */
    static ResolvedCampaignManifest create(
            String manifestFormatVersion,
            String compilerVersion,
            ProposalMode mode,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            List<ResolvedComponent> components,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            JourneyTopology journeyTopology) {
        List<ResolvedComponent> canonicalComponents = canonicalComponents(components);
        List<CapabilityPin> canonicalPins = canonicalPins(capabilityPins, canonicalComponents);
        ContentHash hash = ContentHash.sha256(CANONICAL_JSON.write(new Material(
                manifestFormatVersion, compilerVersion, mode, campaignId, baseCampaignRevision, baseDraftId,
                baseDraftRevision, forkBase, canonicalComponents, canonicalPins, catalogVersion, catalogHash,
                policyVersion, policyHash, CanonicalJourneyTopology.of(journeyTopology))));
        return new ResolvedCampaignManifest(
                manifestFormatVersion, compilerVersion, mode, campaignId, baseCampaignRevision, baseDraftId,
                baseDraftRevision, forkBase, canonicalComponents, canonicalPins, catalogVersion, catalogHash,
                policyVersion, policyHash, journeyTopology, hash);
    }

    ResolvedCampaignManifest(
            String manifestFormatVersion,
            String compilerVersion,
            ProposalMode mode,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            List<ResolvedComponent> components,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            JourneyTopology journeyTopology,
            ContentHash manifestHash) {
        this.manifestFormatVersion = requireVersion(
                manifestFormatVersion, CompilerVersions.MANIFEST_FORMAT_VERSION, "manifestFormatVersion");
        this.compilerVersion = requireVersion(
                compilerVersion, CompilerVersions.COMPILER_VERSION, "compilerVersion");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.campaignId = campaignId;
        this.baseCampaignRevision = baseCampaignRevision;
        this.baseDraftId = baseDraftId;
        this.baseDraftRevision = baseDraftRevision;
        this.forkBase = forkBase;
        Objects.requireNonNull(catalogVersion, "catalogVersion");
        Objects.requireNonNull(catalogHash, "catalogHash");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(policyHash, "policyHash");
        Objects.requireNonNull(manifestHash, "manifestHash");
        this.catalogVersion = catalogVersion;
        this.catalogHash = catalogHash;
        this.policyVersion = policyVersion;
        this.policyHash = policyHash;
        this.journeyTopology = journeyTopology;
        this.components = canonicalComponents(components);
        this.capabilityPins = canonicalPins(capabilityPins, this.components);

        requireModeConsistency();

        ContentHash expected = ContentHash.sha256(CANONICAL_JSON.write(material()));
        if (!manifestHash.equals(expected)) {
            throw inconsistent("manifestHash", "The declared manifest hash is not the hash of the manifest.");
        }
        this.manifestHash = manifestHash;
    }

    /** The Manifest document format version. */
    public String manifestFormatVersion() {
        return manifestFormatVersion;
    }

    /** The compiler version that produced this Manifest. */
    public String compilerVersion() {
        return compilerVersion;
    }

    public ProposalMode mode() {
        return mode;
    }

    /** The target campaign identifier, or null for a CREATE compile where no Campaign exists yet. */
    public String campaignId() {
        return campaignId;
    }

    /** The exact Campaign revision this Manifest was compiled against, or null for CREATE. */
    public Long baseCampaignRevision() {
        return baseCampaignRevision;
    }

    /** The editable Draft this Manifest was compiled against, or null for CREATE. */
    public String baseDraftId() {
        return baseDraftId;
    }

    /** The exact Draft revision this Manifest was compiled against, or null for CREATE. */
    public Long baseDraftRevision() {
        return baseDraftRevision;
    }

    /** The immutable Published fork base, or null when the Draft is not a fork. */
    public PublishedIdentity forkBase() {
        return forkBase;
    }

    /** The complete final ordered component collection, inherited unchanged components included. */
    public List<ResolvedComponent> components() {
        return components;
    }

    /** Every capability/contract-version pin this Manifest uses, in deterministic order. */
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

    /**
     * The Journey topology frozen with this Manifest, or null when the Campaign carries none.
     *
     * <p>When present it is a genuine T20 {@link JourneyTopology} whose edges name final component
     * {@code logicalId}s, so the graph survives Save, Apply, fork and publication as a fact of the Manifest
     * rather than as a host-resolved input a later turn could substitute.
     */
    public JourneyTopology journeyTopology() {
        return journeyTopology;
    }

    /** True when this Manifest froze a Journey topology. */
    public boolean hasJourneyTopology() {
        return journeyTopology != null;
    }

    /** The canonical Manifest hash, computed from strict canonical bytes. */
    public ContentHash manifestHash() {
        return manifestHash;
    }

    /** The canonical UTF-8 bytes every semantic fact and version discriminator is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /**
     * Reconstructs the exact immutable ResolvedCampaignManifest from its stored canonical JSON bytes.
     *
     * <p>Package-private so only this compiler package — in practice, a durable store — can call it.
     * The reconstruction re-proves every invariant through the genuine {@link #create} factory, so
     * the reconstructed manifest is byte-identical to the original.
     */
    static ResolvedCampaignManifest reconstruct(String manifestJson) {
        Material material = CANONICAL_JSON.read(manifestJson, Material.class);
        JourneyTopology topology = null;
        if (material.journeyTopology() != null) {
            CanonicalJourneyTopology canonical = material.journeyTopology();
            topology = JourneyTopology.of(canonical.topologyFormatVersion(), canonical.edges());
            if (!canonical.topologyHash().equals(topology.topologyHash())) {
                throw inconsistent("topologyHash",
                        "The stored topology hash does not equal the recomputed topology hash.");
            }
        }
        return create(
                material.manifestFormatVersion(),
                material.compilerVersion(),
                material.mode(),
                material.campaignId(),
                material.baseCampaignRevision(),
                material.baseDraftId(),
                material.baseDraftRevision(),
                material.forkBase(),
                material.components(),
                material.capabilityPins(),
                material.catalogVersion(),
                material.catalogHash(),
                material.policyVersion(),
                material.policyHash(),
                topology);
    }

    /** The component with the given stable identity, or null when the Manifest does not own it. */
    public ResolvedComponent component(String logicalId) {
        for (ResolvedComponent component : components) {
            if (component.logicalId().equals(logicalId)) {
                return component;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ResolvedCampaignManifest manifest
                && Objects.equals(manifestHash, manifest.manifestHash);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(manifestHash);
    }

    @Override
    public String toString() {
        return "ResolvedCampaignManifest[formatVersion=" + manifestFormatVersion
                + ", mode=" + mode
                + ", components=" + components.size()
                + ", capabilityPins=" + capabilityPins.size()
                + ", manifestHash=" + manifestHash + ']';
    }

    private Material material() {
        return new Material(
                manifestFormatVersion,
                compilerVersion,
                mode,
                campaignId,
                baseCampaignRevision,
                baseDraftId,
                baseDraftRevision,
                forkBase,
                components,
                capabilityPins,
                catalogVersion,
                catalogHash,
                policyVersion,
                policyHash,
                CanonicalJourneyTopology.of(journeyTopology));
    }

    private static List<ResolvedComponent> canonicalComponents(List<ResolvedComponent> components) {
        if (components == null) {
            throw inconsistent("components", "A manifest requires its complete component collection.");
        }
        List<ResolvedComponent> copy = new ArrayList<>(components.size());
        Set<String> ids = new HashSet<>();
        for (ResolvedComponent component : components) {
            if (component == null) {
                throw inconsistent("components", "A manifest must not carry a null component.");
            }
            if (!ids.add(component.logicalId())) {
                throw inconsistent("duplicateLogicalId", "A manifest must not repeat one logical identity.");
            }
            copy.add(component);
        }
        return List.copyOf(copy);
    }

    private static List<CapabilityPin> canonicalPins(
            List<CapabilityPin> pins,
            List<ResolvedComponent> components) {
        if (pins == null) {
            throw inconsistent("capabilityPins", "A manifest requires its capability pin set.");
        }
        Map<String, String> byCode = new HashMap<>();
        for (ResolvedComponent component : components) {
            String existing = byCode.putIfAbsent(component.capabilityCode(), component.contractVersion());
            if (existing != null && !existing.equals(component.contractVersion())) {
                throw inconsistent("capabilityPins",
                        "One capability must not appear under two contract versions in one manifest.");
            }
        }
        Set<String> declared = new LinkedHashSet<>();
        for (CapabilityPin pin : pins) {
            if (pin == null) {
                throw inconsistent("capabilityPins", "A manifest must not carry a null capability pin.");
            }
            if (!declared.add(pin.capabilityCode() + '|' + pin.contractVersion())) {
                throw inconsistent("capabilityPins", "A manifest must not repeat one capability pin.");
            }
        }
        for (Map.Entry<String, String> entry : byCode.entrySet()) {
            if (!declared.contains(entry.getKey() + '|' + entry.getValue())) {
                throw inconsistent("capabilityPins", "Every used capability requires its exact pin.");
            }
        }
        if (declared.size() != byCode.size()) {
            throw inconsistent("capabilityPins", "A manifest must not pin an unused capability.");
        }
        List<CapabilityPin> ordered = new ArrayList<>(pins);
        ordered.sort(CapabilityPin::order);
        return List.copyOf(ordered);
    }

    /**
     * Re-proves that the mode and the base Campaign/Draft/fork facts cannot contradict one another.
     *
     * <p>Construction is reachable with any field combination, so a self-consistent hash must never by itself
     * make a CREATE that carries base facts, or an EDIT that omits them, representable. Equal revision numbers
     * are not identity, so the only fork contradiction is a fork base that names the base Draft itself.
     */
    private void requireModeConsistency() {
        if (mode == ProposalMode.CREATE) {
            if (campaignId != null || baseCampaignRevision != null || baseDraftId != null
                    || baseDraftRevision != null || forkBase != null) {
                throw inconsistent("createBaseFacts",
                        "A CREATE manifest must carry no campaign, base revision or fork fact.");
            }
            return;
        }
        if (campaignId == null || !IDENTIFIER.matcher(campaignId).matches()) {
            throw inconsistent("campaignId", "An EDIT manifest requires a canonical campaign identifier.");
        }
        if (baseCampaignRevision == null || baseCampaignRevision < 1) {
            throw inconsistent("baseCampaignRevision",
                    "An EDIT manifest requires a positive base campaign revision.");
        }
        if (baseDraftId == null || !IDENTIFIER.matcher(baseDraftId).matches()) {
            throw inconsistent("baseDraftId", "An EDIT manifest requires a canonical base draft identifier.");
        }
        if (baseDraftRevision == null || baseDraftRevision < 1) {
            throw inconsistent("baseDraftRevision", "An EDIT manifest requires a positive base draft revision.");
        }
        if (forkBase != null && forkBase.publishedId().equals(baseDraftId)) {
            throw inconsistent("forkBase", "A fork base must not identify the draft itself.");
        }
    }

    private static String requireVersion(String value, String expected, String invariant) {
        if (!expected.equals(value)) {
            throw inconsistent(invariant, "A manifest must carry the one supported version discriminator.");
        }
        return value;
    }

    private static CompilerException inconsistent(String invariant, String message) {
        return new CompilerException(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT, invariant, message);
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(
            String manifestFormatVersion,
            String compilerVersion,
            ProposalMode mode,
            String campaignId,
            Long baseCampaignRevision,
            String baseDraftId,
            Long baseDraftRevision,
            PublishedIdentity forkBase,
            List<ResolvedComponent> components,
            List<CapabilityPin> capabilityPins,
            String catalogVersion,
            ContentHash catalogHash,
            String policyVersion,
            ContentHash policyHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) CanonicalJourneyTopology journeyTopology) {
    }

    /**
     * The canonical, hash-covering projection of one optional Journey topology.
     *
     * <p>The T20 {@link JourneyTopology} is a behavior-carrying value rather than a wire record, so the
     * Manifest projects exactly the facts it freezes: the format version, the ordered typed edges and the
     * topology hash. A Manifest that carries no topology contributes nothing at all, which is what keeps an
     * accepted topology-free Manifest byte-identical to the one the T15/T20 boundaries already produced.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CanonicalJourneyTopology(
            String topologyFormatVersion,
            List<JourneyTopologyEdge> edges,
            ContentHash topologyHash) {

        static CanonicalJourneyTopology of(JourneyTopology topology) {
            return topology == null
                    ? null
                    : new CanonicalJourneyTopology(
                            topology.topologyFormatVersion(), topology.edges(), topology.topologyHash());
        }
    }
}