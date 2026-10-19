package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T20 test-only support that builds a self-consistent {@link ResolvedCampaignManifest} from resolved
 * components through the same package-private construction path the production compiler uses.
 *
 * <p>It exists because the Manifest constructor is deliberately package-private and non-forgeable, so a
 * Journey test that is not in the compiler package needs one small bridge. It fabricates no production
 * availability and no Gate approval: it is a deterministic contract fixture only.
 */
public final class JourneyManifestFixtures {

    /** The frozen catalog version used by T20 Journey fixtures. */
    public static final String CATALOG_VERSION = "catalog-t20-1";

    /** The frozen policy version used by T20 Journey fixtures. */
    public static final String POLICY_VERSION = "policy-t20-1";

    private JourneyManifestFixtures() {
    }

    /** Builds a CREATE manifest holding exactly the supplied resolved components. */
    public static ResolvedCampaignManifest manifest(List<ResolvedComponent> components) {
        Map<String, String> pins = new LinkedHashMap<>();
        for (ResolvedComponent component : components) {
            pins.putIfAbsent(component.capabilityCode(), component.contractVersion());
        }
        List<CapabilityPin> capabilityPins = new ArrayList<>();
        for (Map.Entry<String, String> entry : pins.entrySet()) {
            capabilityPins.add(new CapabilityPin(entry.getKey(), entry.getValue()));
        }
        return ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION,
                CompilerVersions.COMPILER_VERSION,
                ProposalMode.CREATE,
                null, null, null, null, null,
                components,
                capabilityPins,
                CATALOG_VERSION,
                ContentHash.sha256("catalog-t20-1".getBytes(StandardCharsets.UTF_8)),
                POLICY_VERSION,
                ContentHash.sha256("policy-t20-1".getBytes(StandardCharsets.UTF_8)));
    }
}
