package com.company.campaign.builder.assembly;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The closed, host-owned Campaign Tool catalog.
 *
 * <p>It is derived exclusively from the server-owned identifier vocabulary and the fixed descriptor facts of
 * this build: there is no method to register a Tool, choose an implementation, widen the allowlist or mark a
 * planned Tool available. The catalog is immutable and {@link #standard()} always returns the same descriptor
 * set and the same {@link #catalogHash()}, so a run cannot depend on a mutable "latest" registry.
 *
 * <p>Every descriptor declares its owning Skill and its rank within that Skill's own order, so the invoker can
 * admit only a Tool owned by the selected Skill and can require the declared phase order.
 */
public final class CampaignToolCatalog {

    /**
     * The one catalog version this build ships.
     *
     * <p>It moved from {@code campaign-tool-catalog-1.0} to {@code 2.0} when {@code schedule.normalize},
     * {@code schedule.validate}, {@code governance.exposure.calculate} and {@code governance.validate} were
     * promoted from planned to implemented, model-callable Tools, from {@code 2.0} to {@code 3.0} when the
     * four {@code journey.*} Tools were promoted the same way, and from {@code 3.0} to {@code 4.0} when the
     * inline-draft Message preview Tool was added: each is a semantic change to the descriptor set, so the
     * version and the canonical hash change together.
     */
    public static final String CATALOG_VERSION = "campaign-tool-catalog-4.0";

    /** The absolute bound on Tool calls in one run. */
    public static final int MAX_TOOL_CALLS = 16;

    /** The absolute bound on the canonical bytes of one Tool input. */
    public static final int MAX_INPUT_BYTES = CampaignToolDescriptor.MAX_INPUT_BYTES;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final CampaignToolCatalog STANDARD = build();

    private final String catalogVersion;
    private final ContentHash catalogHash;
    private final List<CampaignToolDescriptor> descriptors;
    private final Map<String, CampaignToolDescriptor> byId;
    private final int maxToolCalls;

    private CampaignToolCatalog(
            String catalogVersion,
            ContentHash catalogHash,
            List<CampaignToolDescriptor> descriptors,
            int maxToolCalls) {
        this.catalogVersion = catalogVersion;
        this.catalogHash = catalogHash;
        this.descriptors = List.copyOf(descriptors);
        Map<String, CampaignToolDescriptor> index = new LinkedHashMap<>();
        for (CampaignToolDescriptor descriptor : this.descriptors) {
            if (index.put(descriptor.toolId(), descriptor) != null) {
                throw CampaignAssemblyException.invalid(
                        CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool catalog must not repeat a Tool identifier.");
            }
        }
        this.byId = Map.copyOf(index);
        this.maxToolCalls = maxToolCalls;
        if (catalogHash == null || !catalogHash.equals(recomputeCatalogHash())) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.TOOL_UNKNOWN, "A Tool catalog hash must cover its own descriptors.");
        }
    }

    /** The one closed catalog of this build. */
    public static CampaignToolCatalog standard() {
        return STANDARD;
    }

    /** The exact catalog version. */
    public String catalogVersion() {
        return catalogVersion;
    }

    /** The canonical hash covering the catalog version and every descriptor. */
    public ContentHash catalogHash() {
        return catalogHash;
    }

    /** Every descriptor, in the server-owned canonical order. */
    public List<CampaignToolDescriptor> descriptors() {
        return descriptors;
    }

    /** The descriptor for one Tool identifier, or null when the catalog does not declare it. */
    public CampaignToolDescriptor descriptor(String toolId) {
        return toolId == null ? null : byId.get(toolId);
    }

    /** True when the catalog declares the Tool identifier. */
    public boolean declares(String toolId) {
        return toolId != null && byId.containsKey(toolId);
    }

    /** Every descriptor the given Skill owns, in descriptor order. */
    public List<CampaignToolDescriptor> ownedBy(String skillId) {
        List<CampaignToolDescriptor> owned = new ArrayList<>();
        for (CampaignToolDescriptor descriptor : descriptors) {
            if (descriptor.owningSkillId().equals(skillId)) {
                owned.add(descriptor);
            }
        }
        return List.copyOf(owned);
    }

    /** True when the exact Skill owns the Tool identifier. */
    public boolean owns(String skillId, String toolId) {
        CampaignToolDescriptor descriptor = descriptor(toolId);
        return descriptor != null && descriptor.owningSkillId().equals(skillId);
    }

    /** The catalog-bounded maximum number of Tool calls in one run. */
    public int maxToolCalls() {
        return maxToolCalls;
    }

    /** The canonical UTF-8 bytes covering the catalog version and every descriptor. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(descriptor());
    }

    /** The canonical hash covering the catalog version and every descriptor. */
    public ContentHash recomputeCatalogHash() {
        return CANONICAL_JSON.hash(descriptor());
    }

    private Map<String, Object> descriptor() {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("catalogVersion", catalogVersion);
        descriptor.put("descriptors", descriptors);
        return descriptor;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CampaignToolCatalog catalog
                && Objects.equals(catalogVersion, catalog.catalogVersion)
                && Objects.equals(catalogHash, catalog.catalogHash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogVersion, catalogHash);
    }

    @Override
    public String toString() {
        return "CampaignToolCatalog[version=" + catalogVersion
                + ", tools=" + descriptors.size()
                + ", hash=" + catalogHash + ']';
    }

    private static CampaignToolCatalog build() {
        List<CampaignToolDescriptor> descriptors = new ArrayList<>();
        descriptors.addAll(messageDescriptors());
        descriptors.addAll(promotionDescriptors());
        descriptors.add(segmentBridgeDescriptor());
        descriptors.addAll(journeyDescriptors());
        descriptors.addAll(scheduleDescriptors());
        descriptors.addAll(governanceDescriptors());
        ContentHash hash = CANONICAL_JSON.hash(Map.of(
                "catalogVersion", CATALOG_VERSION,
                "descriptors", descriptors));
        return new CampaignToolCatalog(CATALOG_VERSION, hash, descriptors, MAX_TOOL_CALLS);
    }

    private static List<CampaignToolDescriptor> messageDescriptors() {
        return List.of(
                new CampaignToolDescriptor(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, "1.0",
                        CampaignSkillIds.MESSAGE_TEMPLATE, 0, CampaignToolKind.DISCOVERY,
                        "MESSAGE_TEMPLATE_DISCOVERY", 4_096, 65_536, true, true),
                new CampaignToolDescriptor(CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE, "1.0",
                        CampaignSkillIds.MESSAGE_TEMPLATE, 1, CampaignToolKind.RESOLUTION,
                        "MESSAGE_TEMPLATE_RESOLUTION", 4_096, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.MESSAGE_VARIABLES_VALIDATE, "1.0",
                        CampaignSkillIds.MESSAGE_TEMPLATE, 2, CampaignToolKind.VALIDATION,
                        "MESSAGE_VARIABLE_VALIDATION", 16_384, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.MESSAGE_PREVIEW_SAFE, "1.0",
                        CampaignSkillIds.MESSAGE_TEMPLATE, 3, CampaignToolKind.PREVIEW,
                        "MESSAGE_SAFE_PREVIEW", 16_384, 16_384, true, true),
                // The one Message Tool that needs no catalog reference: its typed request already carries the
                // canonical channel and the exact bounded body, so a full-length body may legally reach the
                // descriptor's whole input budget while the safe result stays tiny.
                new CampaignToolDescriptor(CampaignToolIds.MESSAGE_INLINE_PREVIEW_SAFE, "1.0",
                        CampaignSkillIds.MESSAGE_TEMPLATE, 4, CampaignToolKind.PREVIEW,
                        "MESSAGE_INLINE_SAFE_PREVIEW", 65_536, 16_384, true, true));
    }

    private static List<CampaignToolDescriptor> promotionDescriptors() {
        return List.of(
                new CampaignToolDescriptor(CampaignToolIds.REWARD_DISCOVER, "1.0",
                        CampaignSkillIds.PROMOTION_REFERENCE, 0, CampaignToolKind.DISCOVERY,
                        "REWARD_DISCOVERY", 4_096, 65_536, true, true),
                new CampaignToolDescriptor(CampaignToolIds.REWARD_RESOLVE, "1.0",
                        CampaignSkillIds.PROMOTION_REFERENCE, 1, CampaignToolKind.RESOLUTION,
                        "REWARD_RESOLUTION", 4_096, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.REWARD_EXPOSURE_CALCULATE, "1.0",
                        CampaignSkillIds.PROMOTION_REFERENCE, 2, CampaignToolKind.CALCULATION,
                        "REWARD_EXPOSURE_CALCULATION", 8_192, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.REWARD_PREVIEW_SAFE, "1.0",
                        CampaignSkillIds.PROMOTION_REFERENCE, 3, CampaignToolKind.PREVIEW,
                        "REWARD_SAFE_PREVIEW", 8_192, 16_384, true, true));
    }

    private static CampaignToolDescriptor segmentBridgeDescriptor() {
        return new CampaignToolDescriptor(CampaignToolIds.SEGMENT_ARTIFACT_REFERENCE, "1.0",
                CampaignSkillIds.SEGMENT_RULE_BASED, 0, CampaignToolKind.PREVIEW,
                "SEGMENT_ARTIFACT_BRIDGE", 4_096, 65_536, false, true);
    }

    private static List<CampaignToolDescriptor> journeyDescriptors() {
        // The Journey Tools are the one place the declared ranks carry a real phase order: catalogue the closed
        // node capabilities, normalize every declared node, validate and compile the complete graph, and only
        // then project a bounded preview. Only the validation Tool is stageable.
        return List.of(
                new CampaignToolDescriptor(CampaignToolIds.JOURNEY_NODE_CATALOG, "1.0",
                        CampaignSkillIds.JOURNEY_FLOW, 0, CampaignToolKind.DISCOVERY,
                        "JOURNEY_NODE_CATALOG", 4_096, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.JOURNEY_COMPOSE, "1.0",
                        CampaignSkillIds.JOURNEY_FLOW, 1, CampaignToolKind.CALCULATION,
                        "JOURNEY_COMPOSE", 65_536, 65_536, true, true),
                new CampaignToolDescriptor(CampaignToolIds.JOURNEY_VALIDATE, "1.0",
                        CampaignSkillIds.JOURNEY_FLOW, 2, CampaignToolKind.VALIDATION,
                        "JOURNEY_VALIDATE", 65_536, 65_536, true, true),
                new CampaignToolDescriptor(CampaignToolIds.JOURNEY_SIMULATE, "1.0",
                        CampaignSkillIds.JOURNEY_FLOW, 3, CampaignToolKind.CALCULATION,
                        "JOURNEY_SIMULATE", 65_536, 65_536, true, true));
    }

    private static List<CampaignToolDescriptor> scheduleDescriptors() {
        return List.of(
                new CampaignToolDescriptor(CampaignToolIds.SCHEDULE_NORMALIZE, "1.0",
                        CampaignSkillIds.SCHEDULE_BASIC, 0, CampaignToolKind.CALCULATION,
                        "SCHEDULE_NORMALIZE", 4_096, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.SCHEDULE_VALIDATE, "1.0",
                        CampaignSkillIds.SCHEDULE_BASIC, 1, CampaignToolKind.VALIDATION,
                        "SCHEDULE_VALIDATE", 4_096, 8_192, true, true),
                planned(CampaignToolIds.SCHEDULE_OCCURRENCES_PREVIEW, CampaignSkillIds.SCHEDULE_BASIC, 2,
                        CampaignToolKind.PREVIEW, "SCHEDULE_OCCURRENCES_PREVIEW"));
    }

    private static List<CampaignToolDescriptor> governanceDescriptors() {
        return List.of(
                new CampaignToolDescriptor(CampaignToolIds.GOVERNANCE_EXPOSURE_CALCULATE, "1.0",
                        CampaignSkillIds.GOVERNANCE_LIMIT, 0, CampaignToolKind.CALCULATION,
                        "GOVERNANCE_EXPOSURE_CALCULATION", 4_096, 8_192, true, true),
                new CampaignToolDescriptor(CampaignToolIds.GOVERNANCE_VALIDATE, "1.0",
                        CampaignSkillIds.GOVERNANCE_LIMIT, 1, CampaignToolKind.VALIDATION,
                        "GOVERNANCE_VALIDATE", 4_096, 8_192, true, true));
    }

    private static CampaignToolDescriptor planned(
            String toolId, String skillId, int rank, CampaignToolKind kind, String purposeCode) {
        return new CampaignToolDescriptor(
                toolId, "1.0", skillId, rank, kind, purposeCode, 4_096, 4_096, false, false);
    }
}