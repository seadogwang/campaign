package com.company.campaign.readiness;

import com.company.campaign.action.ActionKind;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.readiness.adapter.ProviderCapabilityEvidenceProjector;
import com.company.campaign.readiness.adapter.ScheduleRuntimeEvidenceProjector;
import com.company.campaign.readiness.adapter.SegmentAuthoringEvidenceProjector;
import com.company.campaign.readiness.adapter.SegmentGateEvidenceProjector;
import com.company.campaign.readiness.adapter.UiEnvironmentEvidenceProjector;
import com.company.campaign.readiness.testsupport.ReadinessTestSupport;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T25 trusted-adapter tests.
 *
 * <p>They prove the three rules the task pack requires of the adapters: only a current approved {@code PASSED}
 * Segment AI Gate with exact frozen pins satisfies its AI requirement; a Provider capability with
 * {@code production=false} satisfies only the LOCAL simulated failure-mode requirement and can never satisfy
 * a LIVE Provider requirement; and a T24 UI evaluation asset is never production Gate evidence and cannot
 * prove browser end-to-end coverage or a LIVE acceptance scenario.
 */
class ReadinessAdapterTest {

    private static final ReadinessEnvironment LOCAL = ReadinessEnvironment.LOCAL;
    private static final ReadinessEnvironment PRODUCTION = ReadinessEnvironment.PRODUCTION;
    private static final ReadinessHostContext LOCAL_CONTEXT = ReadinessTestSupport.context(LOCAL);
    private static final ReadinessHostContext PRODUCTION_CONTEXT = ReadinessTestSupport.context(PRODUCTION);
    private static final java.time.Instant OBSERVED = ReadinessTestSupport.OBSERVED_AT;

    private static RequirementEvidence byRequirement(
            List<RequirementEvidence> evidence, ReadinessRequirement requirement) {
        return evidence.stream()
                .filter(record -> record.requirement() == requirement)
                .findFirst()
                .orElseThrow();
    }

    // ------------------------------------------------------------------ Segment AI Gate

    @Test
    void anAbsentGateApprovalBlocksTheAiRequirement() {
        List<RequirementEvidence> evidence = SegmentGateEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", SegmentGateEvidenceProjector.GateFacts.absent("segment.rule-based"),
                OBSERVED, null);

        RequirementEvidence gate = byRequirement(
                evidence, ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(gate.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(gate.reasonCode()).isEqualTo(ReadinessReasonCodes.AI_GATE_APPROVAL_MISSING);
        assertThat(gate.origin()).isEqualTo(EvidenceOrigin.REAL_LOCAL);
        assertThat(gate.productionGateEvidence()).isFalse();
        assertThat(evidence).hasSize(3);
    }

    @Test
    void onlyACurrentApprovedPassedGateWithExactPinsSatisfiesTheAiRequirement() {
        ContentHash pins = ReadinessTestSupport.hash("pins");
        ContentHash decision = ReadinessTestSupport.hash("decision");

        RequirementEvidence blockedByUnapproved = byRequirement(
                SegmentGateEvidenceProjector.project(LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                                "segment.rule-based", true, false, GateOutcome.PASSED, true,
                                pins, pins, decision),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(blockedByUnapproved.reasonCode()).isEqualTo(ReadinessReasonCodes.AI_GATE_NOT_APPROVED);

        RequirementEvidence blockedByOutcome = byRequirement(
                SegmentGateEvidenceProjector.project(LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                                "segment.rule-based", true, true, GateOutcome.FAILED, true,
                                pins, pins, decision),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(blockedByOutcome.reasonCode()).isEqualTo(ReadinessReasonCodes.AI_GATE_OUTCOME_FAILED);

        RequirementEvidence blockedByInvalid = byRequirement(
                SegmentGateEvidenceProjector.project(LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                                "segment.rule-based", true, true, GateOutcome.INVALID, true,
                                pins, pins, decision),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(blockedByInvalid.reasonCode()).isEqualTo(ReadinessReasonCodes.AI_GATE_OUTCOME_INVALID);

        RequirementEvidence blockedByDevelopmentOnlyCorpus = byRequirement(
                SegmentGateEvidenceProjector.project(LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                                "segment.rule-based", true, true, GateOutcome.PASSED, false,
                                pins, pins, decision),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(blockedByDevelopmentOnlyCorpus.reasonCode())
                .isEqualTo(ReadinessReasonCodes.AI_GATE_DEVELOPMENT_ONLY);

        RequirementEvidence blockedByStalePins = byRequirement(
                SegmentGateEvidenceProjector.project(LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                                "segment.rule-based", true, true, GateOutcome.PASSED, true,
                                pins, ReadinessTestSupport.hash("newer-pins"), decision),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(blockedByStalePins.reasonCode()).isEqualTo(ReadinessReasonCodes.AI_GATED_PINS_STALE);

        List<RequirementEvidence> satisfied = SegmentGateEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                        "segment.rule-based", true, true, GateOutcome.PASSED, true, pins, pins, decision),
                OBSERVED, null);
        RequirementEvidence gate = byRequirement(satisfied, ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED);
        assertThat(gate.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(gate.reasonCode()).isNull();
        assertThat(gate.subject()).isEqualTo("segment.rule-based");
        assertThat(byRequirement(satisfied, ReadinessRequirement.AI_GATED_PINS_CURRENT).status())
                .isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(byRequirement(satisfied,
                ReadinessRequirement.AI_GATED_PIN_CHANGE_REQUIRES_EVALUATION).status())
                .isEqualTo(EvidenceStatus.SATISFIED);
    }

    @Test
    void aChangedGatedPinBundleRequiresEvaluationAgain() {
        List<RequirementEvidence> evidence = SegmentGateEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", new SegmentGateEvidenceProjector.GateFacts(
                        "segment.rule-based", true, true, GateOutcome.PASSED, true,
                        ReadinessTestSupport.hash("approved-pins"),
                        ReadinessTestSupport.hash("current-pins"),
                        ReadinessTestSupport.hash("decision")),
                OBSERVED, null);

        assertThat(byRequirement(evidence, ReadinessRequirement.AI_GATED_PINS_CURRENT).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(byRequirement(evidence, ReadinessRequirement.AI_GATED_PINS_CURRENT).reasonCode())
                .isEqualTo(ReadinessReasonCodes.AI_GATED_PINS_STALE);
        assertThat(byRequirement(evidence, ReadinessRequirement.AI_SEGMENT_GATE_APPROVED_PASSED).reasonCode())
                .isEqualTo(ReadinessReasonCodes.AI_GATED_PINS_STALE);
    }

    // ------------------------------------------------------------------ Segment Authoring Integration

    @Test
    void anAbsentOrDevelopmentOnlyAuthoringIntegrationBlocksTheAiRequirement() {
        RequirementEvidence absent = byRequirement(
                SegmentAuthoringEvidenceProjector.project(
                        LOCAL_CONTEXT, "ev", SegmentAuthoringEvidenceProjector.AuthoringFacts.absent(),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_AUTHORING_INTEGRATION);
        assertThat(absent.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(absent.reasonCode()).isEqualTo(ReadinessReasonCodes.AUTHORING_INTEGRATION_ABSENT);

        RequirementEvidence developmentOnly = byRequirement(
                SegmentAuthoringEvidenceProjector.project(
                        LOCAL_CONTEXT, "ev", new SegmentAuthoringEvidenceProjector.AuthoringFacts(
                                "segment.rule-based", "1.0", true, true,
                                ReadinessTestSupport.hash("dsl"),
                                ReadinessTestSupport.hash("ast"),
                                ReadinessTestSupport.hash("plan"),
                                ReadinessTestSupport.hash("preview"),
                                ReadinessTestSupport.hash("skill")),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_AUTHORING_INTEGRATION);
        assertThat(developmentOnly.reasonCode())
                .isEqualTo(ReadinessReasonCodes.AUTHORING_INTEGRATION_DEVELOPMENT_ONLY);
    }

    @Test
    void aCompleteAcceptedAuthoringProofSatisfiesTheAiRequirement() {
        RequirementEvidence evidence = byRequirement(
                SegmentAuthoringEvidenceProjector.project(
                        LOCAL_CONTEXT, "ev", new SegmentAuthoringEvidenceProjector.AuthoringFacts(
                                "segment.rule-based", "1.0", true, false,
                                ReadinessTestSupport.hash("dsl"),
                                ReadinessTestSupport.hash("ast"),
                                ReadinessTestSupport.hash("plan"),
                                ReadinessTestSupport.hash("preview"),
                                ReadinessTestSupport.hash("skill")),
                        OBSERVED, null),
                ReadinessRequirement.AI_SEGMENT_AUTHORING_INTEGRATION);

        assertThat(evidence.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(evidence.evidenceHash()).isEqualTo(
                new SegmentAuthoringEvidenceProjector.AuthoringFacts(
                        "segment.rule-based", "1.0", true, false,
                        ReadinessTestSupport.hash("dsl"),
                        ReadinessTestSupport.hash("ast"),
                        ReadinessTestSupport.hash("plan"),
                        ReadinessTestSupport.hash("preview"),
                        ReadinessTestSupport.hash("skill")).factsHash());
    }

    // ------------------------------------------------------------------ Provider capability

    @Test
    void aSimulatedProviderSatisfiesOnlyTheLocalFailureModeRequirement() {
        ProviderCapability simulated = ProviderCapability.of(
                "simulated-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE,
                true, true, false);

        List<RequirementEvidence> evidence = ProviderCapabilityEvidenceProjector.project(
                PRODUCTION_CONTEXT, "ev", simulated, OBSERVED, null);

        RequirementEvidence local = byRequirement(
                evidence, ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES);
        assertThat(local.status()).isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(local.origin()).isEqualTo(EvidenceOrigin.SIMULATED);
        assertThat(local.productionGateEvidence()).isFalse();

        RequirementEvidence live = byRequirement(
                evidence, ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);
        assertThat(live.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(live.reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_NOT_PRODUCTION);
        assertThat(live.origin()).isEqualTo(EvidenceOrigin.SIMULATED);
        assertThat(live.productionGateEvidence()).isFalse();
    }

    @Test
    void aProductionProviderIsRefusedAsSimulatedFailureModeEvidence() {
        ProviderCapability production = ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE,
                true, true, true);

        List<RequirementEvidence> evidence = ProviderCapabilityEvidenceProjector.project(
                PRODUCTION_CONTEXT, "ev", production, OBSERVED, null);

        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES).reasonCode())
                .isEqualTo(ReadinessReasonCodes.SIMULATED_PROVIDER_FAILURE_MODES_ABSENT);
    }

    @Test
    void aProductionCapableProviderObservedLocallyIsABlockerNotAProductionClaim() {
        ProviderCapability production = ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE,
                true, true, true);

        RequirementEvidence live = byRequirement(
                ProviderCapabilityEvidenceProjector.project(LOCAL_CONTEXT, "ev", production, OBSERVED, null),
                ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);

        assertThat(live.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(live.reasonCode()).isEqualTo(ReadinessReasonCodes.PRODUCTION_RUN_NOT_PERFORMED);
        assertThat(live.origin()).isEqualTo(EvidenceOrigin.REAL_LOCAL);
        assertThat(live.productionGateEvidence()).isFalse();
    }

    @Test
    void aProviderWithoutIdempotencyOrReconciliationBlocksTheLiveRequirement() {
        ProviderCapability incomplete = ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE,
                true, false, true);

        RequirementEvidence live = byRequirement(
                ProviderCapabilityEvidenceProjector.project(
                        PRODUCTION_CONTEXT, "ev", incomplete, OBSERVED, null),
                ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);

        assertThat(live.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(live.reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_CAPABILITY_INCOMPLETE);
    }

    @Test
    void aProductionCapabilityWithoutLiveVerificationBlocksTheLiveRequirement() {
        ProviderCapability production = ProviderCapability.of(
                "production-message-provider", ReadinessTestSupport.TENANT, ActionKind.MESSAGE,
                true, true, true);

        RequirementEvidence live = byRequirement(
                ProviderCapabilityEvidenceProjector.project(
                        PRODUCTION_CONTEXT, "ev", production, OBSERVED, null),
                ReadinessRequirement.LIVE_PROVIDER_IDEMPOTENCY_AND_RECONCILIATION);

        // A capability is configuration-shaped: production=true by itself is never live verification evidence.
        assertThat(live.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(live.reasonCode()).isEqualTo(ReadinessReasonCodes.PROVIDER_EVIDENCE_ABSENT);
        assertThat(live.productionGateEvidence()).isFalse();
    }

    // ------------------------------------------------------------------ schedule, runtime and action

    @Test
    void acceptedDeterministicCoverageSatisfiesLocalCoverageAndNeverClaimsLiveOperations() {
        ScheduleRuntimeEvidenceProjector.CoverageFacts facts =
                new ScheduleRuntimeEvidenceProjector.CoverageFacts(
                        "runtime-t22-v1", true, true, ReadinessTestSupport.hash("coverage"));

        List<RequirementEvidence> evidence = ScheduleRuntimeEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", facts, OBSERVED, null);

        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS).status())
                .isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_RUNTIME_ISOLATION).status())
                .isEqualTo(EvidenceStatus.SATISFIED);

        RequirementEvidence schedule = byRequirement(evidence, ReadinessRequirement.LIVE_SCHEDULE_SAFETY);
        assertThat(schedule.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(schedule.reasonCode()).isEqualTo(ReadinessReasonCodes.SCHEDULE_COVERAGE_LOCAL_ONLY);

        RequirementEvidence rollback = byRequirement(evidence, ReadinessRequirement.LIVE_OPERATIONAL_ROLLBACK);
        assertThat(rollback.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(rollback.reasonCode()).isEqualTo(ReadinessReasonCodes.PRODUCTION_EVIDENCE_ABSENT);
    }

    @Test
    void absentCoverageIsReportedAsABlocker() {
        List<RequirementEvidence> evidence = ScheduleRuntimeEvidenceProjector.project(
                LOCAL_CONTEXT, "ev",
                ScheduleRuntimeEvidenceProjector.CoverageFacts.absent(
                        "runtime-t22-v1", ReadinessTestSupport.hash("coverage")),
                OBSERVED, null);

        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_CONCURRENCY_AND_LIMITS).reasonCode())
                .isEqualTo(ReadinessReasonCodes.CONCURRENCY_COVERAGE_ABSENT);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_RUNTIME_ISOLATION).reasonCode())
                .isEqualTo(ReadinessReasonCodes.RUNTIME_ISOLATION_ABSENT);
    }

    // ------------------------------------------------------------------ T24 UI environment

    @Test
    void aT24EvaluationAssetCanNeverProveBrowserEndToEndOrLiveAcceptance() {
        assertThat(UiEnvironmentEvidenceProjector.PRODUCTION_GATE_EVIDENCE).isFalse();
        assertThat(UiEnvironmentEvidenceProjector.admissibleAsProductionGateEvidence()).isFalse();

        List<RequirementEvidence> evidence = UiEnvironmentEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", new UiEnvironmentEvidenceProjector.UiEvidenceFacts(
                        "ui-t24-cases",
                        "evaluation/ui/t24-v1/cases.json",
                        ReadinessTestSupport.hash("ui-asset"),
                        true,
                        false),
                OBSERVED, null);

        RequirementEvidence browser = byRequirement(evidence, ReadinessRequirement.LOCAL_BROWSER_END_TO_END);
        assertThat(browser.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(browser.origin()).isEqualTo(EvidenceOrigin.EVALUATION_ASSET);
        assertThat(browser.reasonCode()).isEqualTo(ReadinessReasonCodes.BROWSER_END_TO_END_ABSENT);

        RequirementEvidence acceptance = byRequirement(
                evidence, ReadinessRequirement.LIVE_ACCEPTANCE_SCENARIOS_PRODUCTION_LIKE);
        assertThat(acceptance.status()).isEqualTo(EvidenceStatus.BLOCKED);
        assertThat(acceptance.reasonCode())
                .isEqualTo(ReadinessReasonCodes.UI_ASSETS_NOT_PRODUCTION_EVIDENCE);

        assertThat(evidence).allSatisfy(record ->
                assertThat(record.productionGateEvidence()).isFalse());
    }

    @Test
    void aRealBrowserRunSatisfiesTheLocalRequirementButNeverTheLiveOne() {
        List<RequirementEvidence> evidence = UiEnvironmentEvidenceProjector.project(
                LOCAL_CONTEXT, "ev", new UiEnvironmentEvidenceProjector.UiEvidenceFacts(
                        "ui-browser-run",
                        "readiness/browser/run-1",
                        ReadinessTestSupport.hash("browser-run"),
                        false,
                        true),
                OBSERVED, null);

        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_BROWSER_END_TO_END).status())
                .isEqualTo(EvidenceStatus.SATISFIED);
        assertThat(byRequirement(evidence, ReadinessRequirement.LOCAL_BROWSER_END_TO_END).origin())
                .isEqualTo(EvidenceOrigin.REAL_LOCAL);
        assertThat(byRequirement(evidence, ReadinessRequirement.LIVE_ACCEPTANCE_SCENARIOS_PRODUCTION_LIKE).status())
                .isEqualTo(EvidenceStatus.BLOCKED);
    }

    // ------------------------------------------------------------------ end to end through the evaluator

    @Test
    void projectedGateAuthoringAndCoverageEvidenceReachTheAiLevel() {
        ContentHash pins = ReadinessTestSupport.hash("pins");
        List<RequirementEvidence> evidence = new ArrayList<>(ReadinessTestSupport.completeEvidence(
                LOCAL, ReadinessLevel.LOCAL_READY));
        evidence.addAll(SegmentGateEvidenceProjector.project(
                LOCAL_CONTEXT, "gate", new SegmentGateEvidenceProjector.GateFacts(
                        "segment.rule-based", true, true, GateOutcome.PASSED, true, pins, pins,
                        ReadinessTestSupport.hash("decision")),
                OBSERVED, null));
        evidence.addAll(SegmentAuthoringEvidenceProjector.project(
                LOCAL_CONTEXT, "authoring", new SegmentAuthoringEvidenceProjector.AuthoringFacts(
                        "segment.rule-based", "1.0", true, false,
                        ReadinessTestSupport.hash("dsl"),
                        ReadinessTestSupport.hash("ast"),
                        ReadinessTestSupport.hash("plan"),
                        ReadinessTestSupport.hash("preview"),
                        ReadinessTestSupport.hash("skill")),
                OBSERVED, null));

        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-1", ReadinessLevel.AI_READY, LOCAL, ReadinessTestSupport.AS_OF, evidence));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.READY);
        assertThat(assessment.blockers()).isEmpty();
        assertThat(assessment.acceptedEvidence()).hasSize(14);
    }

    @Test
    void projectedAiEvidenceAloneCannotReachTheLiveLevel() {
        List<RequirementEvidence> evidence = new ArrayList<>(ReadinessTestSupport.completeEvidence(
                LOCAL, ReadinessLevel.AI_READY));
        evidence.removeIf(record -> record.requirement()
                == ReadinessRequirement.LOCAL_SIMULATED_PROVIDER_FAILURE_MODES
                || record.requirement() == ReadinessRequirement.LOCAL_BROWSER_END_TO_END);
        evidence.addAll(ProviderCapabilityEvidenceProjector.project(
                LOCAL_CONTEXT, "provider",
                ProviderCapability.of("simulated-message-provider", ReadinessTestSupport.TENANT,
                        ActionKind.MESSAGE, true, true, false),
                OBSERVED, null));
        evidence.addAll(UiEnvironmentEvidenceProjector.project(
                LOCAL_CONTEXT, "ui", new UiEnvironmentEvidenceProjector.UiEvidenceFacts(
                        "ui-t24-cases", "evaluation/ui/t24-v1/cases.json",
                        ReadinessTestSupport.hash("ui-asset"), true, false),
                OBSERVED, null));

        ReadinessAssessment assessment = new ReadinessEvaluator().evaluate(new ReadinessEvaluationRequest(
                "assessment-1", ReadinessLevel.LIVE_READY, LOCAL, ReadinessTestSupport.AS_OF, evidence));

        assertThat(assessment.status()).isEqualTo(ReadinessStatus.BLOCKED);
        // T35B adds the one production-only LIVE_PRODUCTION_STRUCTURED_MODEL requirement, which this evidence
        // set cannot cover, so exactly one more missing-requirement blocker is reported.
        assertThat(assessment.blockers()).hasSize(11);
        assertThat(assessment.acceptedEvidence()).hasSize(13);
        assertThat(assessment.blockerCodes())
                .contains(ReadinessBlockerCodes.REQUIREMENT_MISSING)
                .contains(ReadinessReasonCodes.PROVIDER_NOT_PRODUCTION)
                .contains(ReadinessReasonCodes.UI_ASSETS_NOT_PRODUCTION_EVIDENCE);
    }
}
