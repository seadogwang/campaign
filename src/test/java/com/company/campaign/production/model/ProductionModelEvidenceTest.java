package com.company.campaign.production.model;

import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T35B: provider evidence is bounded, redacted, and honest about whether the exact configuration was ever
 * exercised live.
 */
class ProductionModelEvidenceTest {

    private static final ContentHash CONFIGURATION_HASH =
            ContentHash.sha256("configuration".getBytes(StandardCharsets.UTF_8));
    private static final String FINGERPRINT =
            ContentHash.sha256("model".getBytes(StandardCharsets.UTF_8)).value();

    @Test
    void aConfigurationAloneIsNeverLiveVerified() {
        ProductionModelEvidence evidence = ProductionModelEvidence.unverified(
                ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, List.of("gate-1"));
        assertThat(evidence.liveVerified()).isFalse();
        assertThat(evidence.lastLiveVerificationStatus())
                .isEqualTo(ProductionModelEvidenceStatus.NOT_VERIFIED);
        assertThat(evidence.lastLiveVerificationInstant()).isNull();
        assertThat(evidence.gatePins()).containsExactly("gate-1");
    }

    @Test
    void aVerifiedLiveRecordCarriesItsInstant() {
        ProductionModelEvidence evidence = new ProductionModelEvidence(
                ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH,
                ProductionModelEvidenceStatus.VERIFIED_LIVE, Instant.parse("2026-01-01T00:00:00Z"),
                List.of());
        assertThat(evidence.liveVerified()).isTrue();
    }

    @Test
    void aVerificationThatRanWithoutAnInstantIsNotRepresentable() {
        assertThatThrownBy(() -> new ProductionModelEvidence(
                ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH,
                ProductionModelEvidenceStatus.VERIFIED_LIVE, null, List.of()))
                .isInstanceOf(ProductionModelException.class);
    }

    @Test
    void anUnknownProviderKindOrFingerprintIsNotRepresentable() {
        assertThatThrownBy(() -> ProductionModelEvidence.unverified(
                "", FINGERPRINT, ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, List.of()))
                .isInstanceOf(ProductionModelException.class);
        assertThatThrownBy(() -> ProductionModelEvidence.unverified(
                ProductionModelProperties.PROVIDER_KIND, "not-a-fingerprint",
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, List.of()))
                .isInstanceOf(ProductionModelException.class);
    }

    @Test
    void theDiagnosticCarriesNoRawModelIdOrEndpoint() {
        String described = ProductionModelEvidence.unverified(
                ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, List.of()).toString();
        assertThat(described)
                .contains(ProductionModelProperties.PROVIDER_KIND)
                .contains(FINGERPRINT)
                .doesNotContain("claude");
    }
}