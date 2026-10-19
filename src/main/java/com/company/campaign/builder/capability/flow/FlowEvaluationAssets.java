package com.company.campaign.builder.capability.flow;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the versioned, in-code evaluation asset one Flow node pack pins.
 *
 * <p>The asset is derived entirely from the pack's ordered in-code fixture corpus, so the pack and its
 * evaluation evidence can never disagree. Each fixture is pinned by the canonical hash of the payload document
 * it exercises — never by the document itself — and the asset's own content hash is the canonical hash of the
 * closed declaration: the asset kind, the capability code, the asset version and every fixture's id, polarity
 * and payload hash in declaration order.
 *
 * <p>The result is deterministic and self-contained: it reads no file, clock, registry or provider, and it
 * carries no template body, reward secret, credential, member identifier or provider response.
 */
final class FlowEvaluationAssets {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The closed asset kind every Flow node pack's evaluation asset declares. */
    static final String ASSET_KIND = "t19-flow-node-capability-evaluation/1.0";

    private FlowEvaluationAssets() {
    }

    /** The one evaluation asset of a pack, derived from its capability code and its fixture corpus. */
    static CapabilityEvaluationAsset assetFor(String capabilityCode, List<FlowEvaluationFixture> fixtures) {
        List<CapabilityEvaluationFixture> positive = new ArrayList<>();
        List<CapabilityEvaluationFixture> negative = new ArrayList<>();
        List<Object> material = new ArrayList<>(fixtures.size() + 3);
        material.add(ASSET_KIND);
        material.add(capabilityCode);
        material.add(FlowPackVersions.EVALUATION_ASSET_VERSION);
        for (FlowEvaluationFixture fixture : fixtures) {
            ContentHash payloadHash = CANONICAL_JSON.hash(fixture.payload());
            CapabilityEvaluationFixture entry = new CapabilityEvaluationFixture(
                    fixture.fixtureId(), fixture.expectation(), payloadHash);
            if (fixture.isAccepted()) {
                positive.add(entry);
            } else {
                negative.add(entry);
            }
            material.add(List.of(fixture.fixtureId(), fixture.expectation().name(), payloadHash.value()));
        }
        return new CapabilityEvaluationAsset(
                assetId(capabilityCode),
                FlowPackVersions.EVALUATION_ASSET_VERSION,
                CANONICAL_JSON.hash(material),
                positive,
                negative);
    }

    /** The one bounded evaluation asset identity of a Flow node capability. */
    static String assetId(String capabilityCode) {
        return "t19." + capabilityCode + ".evaluation";
    }
}