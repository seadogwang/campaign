package com.company.campaign.builder.capability.promotion;

import com.company.campaign.builder.capability.pack.CapabilityEvaluationAsset;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture;
import com.company.campaign.builder.capability.pack.CapabilityEvaluationFixture.Expectation;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The versioned evaluation asset the {@code promotion.reference} pack pins.
 *
 * <p>It states the asset identity and version, keeps accepted and rejected fixtures strictly apart, and
 * binds one canonical content hash over the whole declaration. Each fixture carries only a bounded id, its
 * polarity and the hash of the canonical payload it exercises — never a payload body, a reward secret, a
 * credential, a member identifier or a provider response.
 *
 * <p>The evaluation payload corpus itself lives under {@code evaluation/capabilities/t19-v1} and is owned
 * by the deferred T19 section 7; this asset is the pack's frozen pin of that corpus, so the pack and the
 * corpus can never disagree about which payload each fixture exercises.
 */
final class RewardReferenceEvaluationAssets {

    /** The one evaluation asset identity and version this pack pins. */
    static final String ASSET_ID = "t19-promotion-reference-evaluation-v1";
    static final String ASSET_VERSION = "1.0";

    private static final CapabilityEvaluationAsset ASSET = build();

    private RewardReferenceEvaluationAssets() {
    }

    /** The one frozen evaluation asset of this pack. */
    static CapabilityEvaluationAsset asset() {
        return ASSET;
    }

    private static CapabilityEvaluationAsset build() {
        List<CapabilityEvaluationFixture> positive = List.of(
                fixture("fixture-promotion-reference-valid", Expectation.ACCEPTED,
                        "promotion-reference-valid"),
                fixture("fixture-promotion-reference-default-quantity", Expectation.ACCEPTED,
                        "promotion-reference-default-quantity"));
        List<CapabilityEvaluationFixture> negative = List.of(
                fixture("fixture-promotion-reference-unknown-reference", Expectation.REJECTED,
                        "promotion-reference-unknown-reference"),
                fixture("fixture-promotion-reference-disabled-version", Expectation.REJECTED,
                        "promotion-reference-disabled-version"),
                fixture("fixture-promotion-reference-unavailable-version", Expectation.REJECTED,
                        "promotion-reference-unavailable-version"),
                fixture("fixture-promotion-reference-zero-quantity", Expectation.REJECTED,
                        "promotion-reference-zero-quantity"),
                fixture("fixture-promotion-reference-negative-quantity", Expectation.REJECTED,
                        "promotion-reference-negative-quantity"),
                fixture("fixture-promotion-reference-amount-overflow", Expectation.REJECTED,
                        "promotion-reference-amount-overflow"),
                fixture("fixture-promotion-reference-over-per-grant-maximum", Expectation.REJECTED,
                        "promotion-reference-over-per-grant-maximum"),
                fixture("fixture-promotion-reference-client-inventory", Expectation.REJECTED,
                        "promotion-reference-client-inventory"),
                fixture("fixture-promotion-reference-unauthorized-field", Expectation.REJECTED,
                        "promotion-reference-unauthorized-field"),
                fixture("fixture-promotion-reference-client-grant-result", Expectation.REJECTED,
                        "promotion-reference-client-grant-result"),
                fixture("fixture-promotion-reference-unknown-field", Expectation.REJECTED,
                        "promotion-reference-unknown-field"));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("assetId", ASSET_ID);
        descriptor.put("assetVersion", ASSET_VERSION);
        descriptor.put("positiveFixtures", positive);
        descriptor.put("negativeFixtures", negative);
        ContentHash contentHash = new CanonicalJson().hash(descriptor);
        return new CapabilityEvaluationAsset(ASSET_ID, ASSET_VERSION, contentHash, positive, negative);
    }

    private static CapabilityEvaluationFixture fixture(String id, Expectation expectation, String descriptor) {
        ContentHash payloadHash = ContentHash.sha256(descriptor.getBytes(StandardCharsets.UTF_8));
        return new CapabilityEvaluationFixture(id, expectation, payloadHash);
    }
}