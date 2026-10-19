package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The narrow host-owned factory that turns an already-resolved authoring component set into a Manifest.
 *
 * <p>It exists for one reason: the accepted T20 graph contract is decided against a
 * {@link ResolvedCampaignManifest}, and a Journey authoring request is validated before any Campaign exists, so
 * there is no compiler-produced Manifest to decide against yet. The factory builds a <em>derived authoring
 * Manifest</em> from components that were already normalized by their accepted capability contracts, with the
 * two frozen version pins of the authoring boundary and no base fact of any kind.
 *
 * <p>It is deliberately not a second compiler: it performs no merge, resolves no capability, reads no policy,
 * saves nothing, publishes nothing and is never a Campaign truth. A caller cannot use it to inject a hand-built
 * payload, because every component it accepts carries its own normalized payload and contract version from a
 * real contract resolution.
 */
public final class JourneyAuthoringManifests {

    /** The frozen catalog version an authoring Manifest is pinned to. */
    public static final String AUTHORING_CATALOG_VERSION = "journey-authoring-catalog";

    /** The frozen policy version an authoring Manifest is pinned to. */
    public static final String AUTHORING_POLICY_VERSION = "journey-authoring-policy";

    private static final ContentHash AUTHORING_CATALOG_HASH =
            ContentHash.sha256(AUTHORING_CATALOG_VERSION.getBytes(StandardCharsets.UTF_8));

    private static final ContentHash AUTHORING_POLICY_HASH =
            ContentHash.sha256(AUTHORING_POLICY_VERSION.getBytes(StandardCharsets.UTF_8));

    private JourneyAuthoringManifests() {
    }

    /**
     * Builds the derived authoring Manifest of one already-normalized component set.
     *
     * <p>Every capability pin is derived from the components themselves, so the Manifest is self-consistent by
     * construction and a component set that pins one capability at two contract versions is refused by the
     * accepted Manifest invariants rather than silently accepted.
     */
    public static ResolvedCampaignManifest ofAuthoringComponents(List<ResolvedComponent> components) {
        Objects.requireNonNull(components, "components");
        Map<String, String> pins = new LinkedHashMap<>();
        for (ResolvedComponent component : components) {
            if (component == null) {
                throw new CompilerException(
                        CompilerErrorCodes.COMPILER_INPUT_MISSING,
                        "components",
                        "An authoring Manifest requires its resolved component set.");
            }
            pins.putIfAbsent(component.capabilityCode(), component.contractVersion());
        }
        List<CapabilityPin> capabilityPins = new ArrayList<>(pins.size());
        for (Map.Entry<String, String> entry : pins.entrySet()) {
            capabilityPins.add(new CapabilityPin(entry.getKey(), entry.getValue()));
        }
        return ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION,
                CompilerVersions.COMPILER_VERSION,
                ProposalMode.CREATE,
                null, null, null, null, null,
                List.copyOf(components),
                capabilityPins,
                AUTHORING_CATALOG_VERSION,
                AUTHORING_CATALOG_HASH,
                AUTHORING_POLICY_VERSION,
                AUTHORING_POLICY_HASH,
                null);
    }
}