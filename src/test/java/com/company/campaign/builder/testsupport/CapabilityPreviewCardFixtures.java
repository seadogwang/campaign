package com.company.campaign.builder.testsupport;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.compiler.CapabilityPreviewCards;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only drafts of the generic T19 capability preview card.
 *
 * <p>A card is a closed, hash-bound value with no builder of its own, so every test needs a small fluent draft
 * to vary exactly one fact at a time and, above all, to build the honest card a genuine contract contributes
 * for the component it just resolved.
 */
public final class CapabilityPreviewCardFixtures {

    /** The one card kind and card contract version the plan format can carry. */
    public static final String CARD_KIND = CapabilityPlanPreviewCard.CAPABILITY_PREVIEW;
    public static final String CARD_VERSION = CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION;

    private CapabilityPreviewCardFixtures() {
    }

    /** A card that binds exactly one resolved component together with one runtime fact. */
    public static CapabilityPlanPreviewCard bound(ResolvedComponent component, RuntimeCompatibility runtime) {
        return builder().bind(component).runtime(runtime).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A small fluent draft so each test can vary exactly one carried fact. */
    public static final class Builder {

        private String componentLogicalId = "component-a";
        private String capabilityCode = "sample.capability";
        private String contractVersion = "1.0";
        private ContentHash normalizedPayloadHash = hash("payload");
        private ContentHash resolvedCatalogReferenceHash;
        private RuntimeCompatibility runtimeCompatibility = FixtureCapability.runtime();
        private List<String> summaryCodes = new ArrayList<>(List.of("READY"));
        private List<String> riskCodes = new ArrayList<>(List.of("LOW"));
        private ContentHash evaluationAssetHash = hash("evaluation");

        private Builder() {
        }

        /** Binds every component-derived fact exactly: identity, capability, contract version and both pins. */
        public Builder bind(ResolvedComponent component) {
            this.componentLogicalId = component.logicalId();
            this.capabilityCode = component.capabilityCode();
            this.contractVersion = component.contractVersion();
            this.normalizedPayloadHash = component.value().contentHash();
            this.resolvedCatalogReferenceHash =
                    CapabilityPreviewCards.canonicalReferenceHash(component.references());
            return this;
        }

        public Builder componentLogicalId(String value) {
            this.componentLogicalId = value;
            return this;
        }

        public Builder capabilityCode(String value) {
            this.capabilityCode = value;
            return this;
        }

        public Builder contractVersion(String value) {
            this.contractVersion = value;
            return this;
        }

        public Builder normalizedPayloadHash(ContentHash value) {
            this.normalizedPayloadHash = value;
            return this;
        }

        public Builder resolvedCatalogReferenceHash(ContentHash value) {
            this.resolvedCatalogReferenceHash = value;
            return this;
        }

        public Builder runtime(RuntimeCompatibility value) {
            this.runtimeCompatibility = value;
            return this;
        }

        public Builder evaluationAssetHash(ContentHash value) {
            this.evaluationAssetHash = value;
            return this;
        }

        public CapabilityPlanPreviewCard build() {
            return new CapabilityPlanPreviewCard(CARD_KIND, CARD_VERSION, componentLogicalId, capabilityCode,
                    contractVersion, normalizedPayloadHash, resolvedCatalogReferenceHash, runtimeCompatibility,
                    summaryCodes, riskCodes, evaluationAssetHash);
        }
    }

    /** One deterministic hash of some test text. */
    public static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}