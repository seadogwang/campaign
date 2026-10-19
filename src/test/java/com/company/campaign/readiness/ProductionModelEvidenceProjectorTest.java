package com.company.campaign.readiness;

import com.company.campaign.production.model.ProductionModelEvidence;
import com.company.campaign.production.model.ProductionModelEvidenceStatus;
import com.company.campaign.production.model.ProductionModelProperties;
import com.company.campaign.readiness.adapter.ProductionModelEvidenceProjector;
import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35B: production model evidence reaches the closed readiness catalog, and no fixture, scripted server or
 * configuration value can satisfy the production-only LIVE structured-model requirement.
 */
class ProductionModelEvidenceProjectorTest {

    private static final Instant OBSERVED = ReadinessTestSupport.OBSERVED_AT;
    private static final ContentHash CONFIGURATION_HASH =
            ContentHash.sha256("configuration".getBytes(StandardCharsets.UTF_8));
    private static final String FINGERPRINT =
            ContentHash.sha256("model".getBytes(StandardCharsets.UTF_8)).value();

    private static ProductionModelEvidence evidence(ProductionModelEvidenceStatus status, Instant instant) {
        return new ProductionModelEvidence(
                ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, status, instant, List.of());
    }

    @Test
    void theCatalogNowCarriesTheProductionOnlyStructuredModelRequirement() {
        ReadinessRequirement requirement = ReadinessRequirement.LIVE_PRODUCTION_STRUCTURED_MODEL;
        assertThat(requirement.productionOnly()).isTrue();
        assertThat(requirement.level()).isEqualTo(ReadinessLevel.LIVE_READY);
        assertThat(requirement.allowedOrigins()).containsExactly(EvidenceOrigin.PRODUCTION);
        assertThat(ReadinessCatalog.requirementsFor(ReadinessLevel.LIVE_READY)).contains(requirement);
    }

    @Test
    void aConfigurationOrScriptedObservationIsNeverProductionEvidence() {
        RequirementEvidence projected = ProductionModelEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "model",
                ProductionModelEvidence.unverified(
                        ProductionModelProperties.PROVIDER_KIND, FINGERPRINT,
                        ProductionModelProperties.CONTRACT_VERSION, CONFIGURATION_HASH, List.of()),
                OBSERVED, null);
        assertThat(projected.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(projected.reasonCode()).isEqualTo(ReadinessReasonCodes.MODEL_EVIDENCE_ABSENT);
        assertThat(projected.origin()).isEqualTo(EvidenceOrigin.FIXTURE);
        assertThat(projected.productionGateEvidence()).isFalse();
    }

    @Test
    void aFailedLiveVerificationIsReportedAsFailed() {
        RequirementEvidence projected = ProductionModelEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "model",
                evidence(ProductionModelEvidenceStatus.FAILED_LIVE, OBSERVED),
                OBSERVED, null);
        assertThat(projected.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(projected.reasonCode()).isEqualTo(ReadinessReasonCodes.MODEL_LIVE_VERIFICATION_FAILED);
        assertThat(projected.origin()).isEqualTo(EvidenceOrigin.REAL_LOCAL);
    }

    @Test
    void aLiveVerificationOutsideProductionIsNotProductionEvidence() {
        RequirementEvidence projected = ProductionModelEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.LOCAL), "model",
                evidence(ProductionModelEvidenceStatus.VERIFIED_LIVE, OBSERVED),
                OBSERVED, null);
        assertThat(projected.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(projected.reasonCode()).isEqualTo(ReadinessReasonCodes.MODEL_EVIDENCE_NOT_PRODUCTION);
    }

    @Test
    void aLiveVerificationOfTheExactConfigurationInProductionSatisfiesTheRequirement() {
        RequirementEvidence projected = ProductionModelEvidenceProjector.project(
                ReadinessTestSupport.context(ReadinessEnvironment.PRODUCTION), "model",
                evidence(ProductionModelEvidenceStatus.VERIFIED_LIVE, OBSERVED),
                OBSERVED, null);
        assertThat(projected.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(projected.origin()).isEqualTo(EvidenceOrigin.PRODUCTION);
        assertThat(projected.productionGateEvidence()).isTrue();

        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-model", ReadinessLevel.LIVE_READY, ReadinessEnvironment.PRODUCTION,
                ReadinessTestSupport.AS_OF, List.of(projected)));
        assertThat(assessment.acceptedEvidence()).hasSize(1);
        assertThat(assessment.blockerCodes())
                .doesNotContain(ReadinessBlockerCodes.EVIDENCE_NOT_PRODUCTION)
                .doesNotContain(ReadinessBlockerCodes.EVIDENCE_WRONG_ORIGIN);
    }
}