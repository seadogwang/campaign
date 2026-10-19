package com.company.campaign.production.provider;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T35C1: the published production Provider evidence is immutable, redacted, and never claims a live verification
 * it does not have.
 */
class ProductionProviderEvidenceTest {

    private static final Instant VERIFIED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRY = Instant.parse("2026-01-08T00:00:00Z");
    private static final ContentHash CONFIGURATION = hash("configuration");
    private static final String FINGERPRINT = ProductionProviderEvidence.fingerprint("production-message-provider");

    private static ContentHash hash(String label) {
        return ContentHash.sha256(label.getBytes(StandardCharsets.UTF_8));
    }

    private static ProviderCapability capability(boolean production) {
        return ProviderCapability.of(
                "production-message-provider", "tenant-1", ActionKind.MESSAGE, true, true, production);
    }

    @Test
    void configurationAloneIsNeverVerified() {
        ContentHash capabilityHash = capability(true).capabilityHash();
        ProductionProviderEvidence evidence = ProductionProviderEvidence.unverified(
                ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                ProductionProviderProperties.CONTRACT_VERSION, capabilityHash, List.of());

        assertThat(evidence.liveVerified()).isFalse();
        assertThat(evidence.lastLiveVerificationStatus())
                .isEqualTo(ProductionProviderVerificationStatus.NOT_VERIFIED);
        assertThat(evidence.lastLiveVerificationInstant()).isNull();
        assertThat(evidence.expiry()).isNull();
    }

    @Test
    void anAcceptedLiveVerificationCarriesItsInstantAndExpiry() {
        ContentHash capabilityHash = capability(true).capabilityHash();
        ProductionProviderEvidence evidence = new ProductionProviderEvidence(
                ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                ProductionProviderProperties.CONTRACT_VERSION, capabilityHash, List.of(),
                ProductionProviderVerificationStatus.VERIFIED_LIVE, VERIFIED_AT, EXPIRY);

        assertThat(evidence.liveVerified()).isTrue();
        assertThat(evidence.expiredAt(VERIFIED_AT)).isFalse();
        assertThat(evidence.expiredAt(EXPIRY)).isTrue();
        assertThat(evidence.describes(capability(true))).isTrue();
        assertThat(evidence.describes(capability(false))).isFalse();
    }

    @Test
    void aVerificationThatNeverRanMustNotCarryAnInstant() {
        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                new ProductionProviderEvidence(
                        ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                        ProductionProviderProperties.CONTRACT_VERSION, hash("capability"), List.of(),
                        ProductionProviderVerificationStatus.NOT_VERIFIED, VERIFIED_AT, null)).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);
    }

    @Test
    void anAcceptedVerificationMustCarryACurrentExpiry() {
        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                new ProductionProviderEvidence(
                        ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                        ProductionProviderProperties.CONTRACT_VERSION, hash("capability"), List.of(),
                        ProductionProviderVerificationStatus.VERIFIED_LIVE, VERIFIED_AT, null)).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);

        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                new ProductionProviderEvidence(
                        ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                        ProductionProviderProperties.CONTRACT_VERSION, hash("capability"), List.of(),
                        ProductionProviderVerificationStatus.VERIFIED_LIVE, VERIFIED_AT,
                        VERIFIED_AT.minusSeconds(1))).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);
    }

    @Test
    void aMalformedFingerprintOrAnOverlongPinListIsRefused() {
        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                ProductionProviderEvidence.unverified(
                        ProductionProviderKind.MESSAGE, "not-a-fingerprint", ActionKind.MESSAGE, CONFIGURATION,
                        ProductionProviderProperties.CONTRACT_VERSION, hash("capability"), List.of())).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);

        List<String> tooMany = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            tooMany.add("pin-" + index);
        }
        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                ProductionProviderEvidence.unverified(
                        ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                        ProductionProviderProperties.CONTRACT_VERSION, hash("capability"), tooMany)).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);
    }

    @Test
    void aLiveVerificationRecordMustDescribeItsExactConfiguration() {
        ContentHash capabilityHash = hash("capability");
        ProductionProviderLiveVerification verification = new ProductionProviderLiveVerification(
                ProductionProviderKind.MESSAGE, ActionKind.MESSAGE,
                ProductionProviderVerificationStatus.VERIFIED_LIVE, VERIFIED_AT, EXPIRY,
                CONFIGURATION, capabilityHash);

        assertThat(verification.describes(
                ProductionProviderKind.MESSAGE, ActionKind.MESSAGE, CONFIGURATION, capabilityHash)).isTrue();
        assertThat(verification.describes(
                ProductionProviderKind.MESSAGE, ActionKind.MESSAGE, hash("other"), capabilityHash)).isFalse();
        assertThat(verification.describes(
                ProductionProviderKind.REWARD, ActionKind.REWARD, CONFIGURATION, capabilityHash)).isFalse();
    }

    @Test
    void aLiveVerificationThatRanMustCarryAnInstant() {
        assertThat(catchThrowableOfType(ProductionProviderException.class, () ->
                new ProductionProviderLiveVerification(
                        ProductionProviderKind.MESSAGE, ActionKind.MESSAGE,
                        ProductionProviderVerificationStatus.FAILED_LIVE, null, null,
                        CONFIGURATION, hash("capability"))).code())
                .isEqualTo(ProductionProviderErrorCodes.PROVIDER_EVIDENCE_INVALID);
    }

    @Test
    void theEvidenceDiagnosticRedactsEveryProviderValue() {
        ProductionProviderEvidence evidence = new ProductionProviderEvidence(
                ProductionProviderKind.MESSAGE, FINGERPRINT, ActionKind.MESSAGE, CONFIGURATION,
                ProductionProviderProperties.CONTRACT_VERSION, capability(true).capabilityHash(), List.of(),
                ProductionProviderVerificationStatus.VERIFIED_LIVE, VERIFIED_AT, EXPIRY);

        assertThat(evidence.toString())
                .doesNotContain("production-message-provider")
                .doesNotContain("tenant-1")
                .doesNotContain("https://");
    }
}