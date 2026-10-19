package com.company.campaign.readiness.adapter;

import com.company.campaign.production.runtime.ProviderRuntimeTopologyEvidence;
import com.company.campaign.readiness.EvidenceOrigin;
import com.company.campaign.readiness.EvidenceStatus;
import com.company.campaign.readiness.ReadinessEnvironment;
import com.company.campaign.readiness.ReadinessEvaluationRequest;
import com.company.campaign.readiness.ReadinessEvaluator;
import com.company.campaign.readiness.ReadinessLevel;
import com.company.campaign.readiness.ReadinessReasonCodes;
import com.company.campaign.readiness.ReadinessRequirement;
import com.company.campaign.readiness.ReadinessStatus;
import com.company.campaign.readiness.RequirementEvidence;
import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35C2: the production provider runtime host projects configured durable isolation and deterministic cycle
 * coverage, and never promotes either into a live-operations claim.
 */
class ProviderRuntimeEvidenceProjectorTest {

    private static final String PREFIX = "provider-runtime";
    private static final String SUBJECT = "prw-00000000000000000000000000000000";

    @Test
    void configuredDurableIsolationAndCycleCoverageAreProjectedAsLocalEvidence() {
        List<RequirementEvidence> evidence = project(ReadinessEnvironment.CI, true, true);

        RequirementEvidence isolation = byRequirement(evidence, ReadinessRequirement.LOCAL_RUNTIME_ISOLATION);
        assertThat(isolation.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(isolation.origin()).isEqualTo(EvidenceOrigin.REAL_LOCAL);
        assertThat(isolation.productionGateEvidence()).isFalse();

        RequirementEvidence cycle = byRequirement(evidence, ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS);
        assertThat(cycle.status()).isEqualTo(EvidenceStatus.SATISFIED);
    }

    @Test
    void anUnconfiguredHostProjectsBlockedLocalEvidence() {
        List<RequirementEvidence> evidence = project(ReadinessEnvironment.CI, false, false);

        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_RUNTIME_ISOLATION).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_RUNTIME_ISOLATION).reasonCode())
                .isEqualTo(ReadinessReasonCodes.RUNTIME_ISOLATION_ABSENT);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS).reasonCode())
                .isEqualTo(ReadinessReasonCodes.CONCURRENCY_COVERAGE_ABSENT);
    }

    @Test
    void theLiveScheduleAlertsAndRollbackRequirementsStayBlockedEvenInProduction() {
        List<RequirementEvidence> evidence = project(ReadinessEnvironment.PRODUCTION, true, true);

        assertThat(byRequirement(evidence, ReadinessRequirement.LIVE_SCHEDULE_SAFETY).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LIVE_SCHEDULE_SAFETY).reasonCode())
                .isEqualTo(ReadinessReasonCodes.SCHEDULE_COVERAGE_LOCAL_ONLY);
        assertThat(byRequirement(evidence, ReadinessRequirement.LIVE_OPERATIONAL_ALERTS).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LIVE_OPERATIONAL_ROLLBACK).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
    }

    @Test
    void theProjectionNeverSpeaksForTheLiveProviderRequirement() {
        List<RequirementEvidence> evidence = project(ReadinessEnvironment.PRODUCTION, true, true);
        assertThat(evidence)
                .noneMatch(record ->
                        record.requirement() == ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);
    }

    @Test
    void theProjectionCannotMakeALiveAssessmentReadyByItself() {
        List<RequirementEvidence> evidence = project(ReadinessEnvironment.PRODUCTION, true, true);
        assertThat(evidence).hasSize(5);

        var assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-provider-runtime", ReadinessLevel.LIVE_READY, ReadinessEnvironment.PRODUCTION,
                ReadinessTestSupport.AS_OF, evidence));

        assertThat(assessment.status()).isNotEqualTo(ReadinessStatus.READY);
        assertThat(assessment.acceptedEvidence())
                .noneMatch(reference ->
                        reference.requirement() == ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);
    }

    private static List<RequirementEvidence> project(
            ReadinessEnvironment environment, boolean durableIsolation, boolean deterministicCycle) {
        ProviderRuntimeTopologyEvidence topology = new ProviderRuntimeTopologyEvidence(
                ProviderRuntimeTopologyEvidence.FORMAT_VERSION,
                SUBJECT,
                durableIsolation,
                deterministicCycle,
                ReadinessTestSupport.hash("provider-runtime-topology"));
        return ProviderRuntimeEvidenceProjector.project(
                ReadinessTestSupport.context(environment), PREFIX, topology,
                ReadinessTestSupport.OBSERVED_AT, null);
    }

    private static RequirementEvidence byRequirement(
            List<RequirementEvidence> evidence, ReadinessRequirement requirement) {
        return evidence.stream()
                .filter(record -> record.requirement() == requirement)
                .findFirst()
                .orElseThrow();
    }
}