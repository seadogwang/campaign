package com.company.campaign.readiness;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.production.provider.ProductionProviderEvidence;
import com.company.campaign.production.provider.ProductionProviderKind;
import com.company.campaign.production.provider.ProductionProviderProperties;
import com.company.campaign.production.provider.ProductionProviderVerificationStatus;
import com.company.campaign.readiness.adapter.ProviderCapabilityEvidenceProjector;
import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35C1: production Provider live evidence reaches the closed readiness catalog, and no capability — however
 * production-capable — can satisfy the production-only LIVE Provider requirement on its own.
 *
 * <p>Only current, successful live verification of the exact configuration, in a production scope, satisfies it;
 * a configuration, a scripted loopback server, a stale verification and a failed verification all become bounded
 * blockers. The simulated Provider LOCAL failure-mode requirement is preserved unchanged.
 */
class ProductionProviderEvidenceProjectorTest {

    private static final Instant OBSERVED = ReadinessTestSupport.OBSERVED_AT;
    private static final Instant EXPIRY = OBSERVED.plus(Duration.ofDays(1));
    private static final ContentHash CONFIGURATION =
            ContentHash.sha256("configuration".getBytes(StandardCharsets.UTF_8));

    private static ProviderCapability capability() {
        return ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE, true, true, true);
    }

    private static ProviderCapability capability(boolean production) {
        return ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE, true, true,
                production);
    }

    private static ProductionProviderEvidence evidence(
            ProviderCapability capability, ProductionProviderVerificationStatus status, Instant instant,
            Instant expiry) {
        return new ProductionProviderEvidence(
                ProductionProviderKind.MESSAGE,
                ProductionProviderEvidence.fingerprint(capability.providerId()),
                ActionKind.MESSAGE,
                CONFIGURATION,
                ProductionProviderProperties.CONTRACT_VERSION,
                capability.capabilityHash(),
                List.of(),
                status,
                instant,
                expiry);
    }

    private static RequirementEvidence liveRecord(List<RequirementEvidence> evidence) {
        return evidence.stream()
                .filter(record -> record.requirement()
                        == ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION)
                .findFirst()
                .orElseThrow();
    }

    private static RequirementEvidence localRecord(List<RequirementEvidence> evidence) {
        return evidence.stream()
                .filter(record -> record.requirement()
                        == ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theLiveProviderRequirementIsProductionOnly() {
        ReadinessRequirement requirement = ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION;
        assertThat(requirement.productionOnly()).isTrue();
        assertThat(requirement.allowedOrigins()).containsExactly(EvidenceOrigin.PRODUCTION);
    }

    @Test
    void aProductionCapabilityWithoutLiveVerificationIsNotLiveEvidence() {
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", capability(), OBSERVED, null);

        RequirementEvidence liveEvidence = liveRecord(projected);
        assertThat(liveEvidence.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveEvidence.reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_EVIDENCE_ABSENT);
        assertThat(liveEvidence.origin()).isEqualTo(EvidenceOrigin.REAL_LOCAL);
        assertThat(liveEvidence.productionGateEvidence()).isFalse();
    }

    @Test
    void anUnverifiedEvidenceRecordIsNeverProductionEvidence() {
        ProviderCapability productionCapability = capability();
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", productionCapability,
                evidence(productionCapability, ProductionProviderVerificationStatus.NOT_VERIFIED, null, null),
                OBSERVED, null);

        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_EVIDENCE_ABSENT);
    }

    @Test
    void aFailedLiveVerificationIsReportedAsFailed() {
        ProviderCapability productionCapability = capability();
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", productionCapability,
                evidence(productionCapability, ProductionProviderVerificationStatus.FAILED_LIVE, OBSERVED, null),
                OBSERVED, null);

        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_LIVE_VERIFICATION_FAILED);
    }

    @Test
    void aLiveVerificationOutsideProductionIsNotProductionEvidence() {
        ProviderCapability productionCapability = capability();
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.LOCAL), "ev", productionCapability,
                evidence(productionCapability, ProductionProviderVerificationStatus.VERIFIED_LIVE, OBSERVED, EXPIRY),
                OBSERVED, null);

        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_EVIDENCE_NOT_PRODUCTION);
    }

    @Test
    void aStaleLiveVerificationIsNotCurrentEvidence() {
        ProviderCapability productionCapability = capability();
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", productionCapability,
                evidence(productionCapability, ProductionProviderVerificationStatus.VERIFIED_LIVE, OBSERVED, EXPIRY),
                EXPIRY, null);

        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_EVIDENCE_EXPIRED);
    }

    @Test
    void evidenceForADifferentCapabilityIsRefused() {
        ProviderCapability productionCapability = capability();
        ProductionProviderEvidence other = new ProductionProviderEvidence(
                ProductionProviderKind.MESSAGE,
                ProductionProviderEvidence.fingerprint("another-provider"),
                ActionKind.MESSAGE,
                CONFIGURATION,
                ProductionProviderProperties.CONTRACT_VERSION,
                ContentHash.sha256("another-capability".getBytes(StandardCharsets.UTF_8)),
                List.of(),
                ProductionProviderVerificationStatus.VERIFIED_LIVE,
                OBSERVED,
                EXPIRY);

        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", productionCapability, other,
                OBSERVED, null);

        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_CAPABILITY_INCOMPLETE);
    }

    @Test
    void aCurrentLiveVerificationOfTheExactConfigurationInProductionSatisfiesTheRequirement() {
        ProviderCapability productionCapability = capability();
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", productionCapability,
                evidence(productionCapability, ProductionProviderVerificationStatus.VERIFIED_LIVE, OBSERVED, EXPIRY),
                OBSERVED, null);

        RequirementEvidence liveEvidence = liveRecord(projected);
        assertThat(liveEvidence.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(liveEvidence.origin()).isEqualTo(EvidenceOrigin.PRODUCTION);
        assertThat(liveEvidence.productionGateEvidence()).isTrue();

        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-provider", ReadinessLevel.LIVE_READY, ReadinessEnvironment.PRODUCTION,
                ReadinessTestSupport.AS_OF, List.of(liveEvidence)));
        assertThat(assessment.acceptedEvidence()).hasSize(1);
        assertThat(assessment.blockerCodes())
                .doesNotContain(ReadinessBlockerCodes.EVIDENCE_NOT_PRODUCTION)
                .doesNotContain(ReadinessBlockerCodes.EVIDENCE_WRONG_ORIGIN);
    }

    @Test
    void theSimulatedProviderLocalEvidenceIsPreserved() {
        ProviderCapability simulated = capability(false);
        List<RequirementEvidence> projected = ProviderCapabilityEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "ev", simulated, OBSERVED, null);

        RequirementEvidence local = localRecord(projected);
        assertThat(local.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(local.origin()).isEqualTo(EvidenceOrigin.SIMULATED);
        assertThat(local.productionGateEvidence()).isFalse();

        // A simulated Provider can never reach the LIVE requirement, with or without evidence.
        assertThat(liveRecord(projected).status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(liveRecord(projected).reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_NOT_PRODUCTION);
    }
}