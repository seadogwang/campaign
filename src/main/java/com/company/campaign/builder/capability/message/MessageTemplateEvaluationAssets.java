package com.company.campaign.builder.capability.message;

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
 * The versioned evaluation asset the {@code message.template} pack pins.
 *
 * <p>It states the asset identity and version, keeps accepted and rejected fixtures strictly apart, and
 * binds one canonical content hash over the whole declaration. Each fixture carries only a bounded id, its
 * polarity and the hash of the canonical payload it exercises — never a payload body, a template text, a
 * credential, a member identifier or a provider response.
 *
 * <p>The evaluation payload corpus itself lives under {@code evaluation/capabilities/t19-v1} and is owned
 * by the deferred T19 section 7; this asset is the pack's frozen pin of that corpus, so the pack and the
 * corpus can never disagree about which payload each fixture exercises.
 */
final class MessageTemplateEvaluationAssets {

    /** The one evaluation asset identity and version this pack pins. */
    static final String ASSET_ID = "t19-message-template-evaluation-v1";
    static final String ASSET_VERSION = "1.0";

    private static final CapabilityEvaluationAsset ASSET = build();

    private MessageTemplateEvaluationAssets() {
    }

    /** The one frozen evaluation asset of this pack. */
    static CapabilityEvaluationAsset asset() {
        return ASSET;
    }

    private static CapabilityEvaluationAsset build() {
        List<CapabilityEvaluationFixture> positive = List.of(
                fixture("fixture-message-template-valid", Expectation.ACCEPTED,
                        "message-template-valid"),
                fixture("fixture-message-template-channel-bound", Expectation.ACCEPTED,
                        "message-template-channel-bound"));
        List<CapabilityEvaluationFixture> negative = List.of(
                fixture("fixture-message-template-unknown-reference", Expectation.REJECTED,
                        "message-template-unknown-reference"),
                fixture("fixture-message-template-disabled-version", Expectation.REJECTED,
                        "message-template-disabled-version"),
                fixture("fixture-message-template-unavailable-version", Expectation.REJECTED,
                        "message-template-unavailable-version"),
                fixture("fixture-message-template-extra-variable", Expectation.REJECTED,
                        "message-template-extra-variable"),
                fixture("fixture-message-template-missing-variable", Expectation.REJECTED,
                        "message-template-missing-variable"),
                fixture("fixture-message-template-wrong-type", Expectation.REJECTED,
                        "message-template-wrong-type"),
                fixture("fixture-message-template-over-length", Expectation.REJECTED,
                        "message-template-over-length"),
                fixture("fixture-message-template-channel-mismatch", Expectation.REJECTED,
                        "message-template-channel-mismatch"),
                fixture("fixture-message-template-unknown-field", Expectation.REJECTED,
                        "message-template-unknown-field"));

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