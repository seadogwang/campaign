package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.MetricId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.AS_OF;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.HIGH;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.caseOutcome;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.completeEvidence;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.contract;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.gateEligibleReport;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.hash;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.metric;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingHighRiskMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.report;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3 and section 5: missing, undefined, invalid, duplicated and inconsistent evidence is always
 * {@code INVALID}, and the deterministic-layer artifact must be complete, fully passing and exactly 100
 * identical replays.
 */
class GateEvidenceValidityTest {

    private final SegmentAiGateEvaluator evaluator = new SegmentAiGateEvaluator();

    // --- metric evidence -------------------------------------------------------------------------

    @Test
    void anUndefinedRequiredDenominatorIsInvalidNeverZeroOrPass() {
        GateDecision decision = evaluate(
                replace(MetricId.CLARIFICATION_RECALL, metric(MetricId.CLARIFICATION_RECALL, 0, 0)),
                passingHighRiskMetrics(), completeEvidence());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.THRESHOLD_EVIDENCE_INVALID);
        assertThat(decision.thresholdChecks()).isEmpty();
    }

    @Test
    void anInvalidRequiredMetricCountIsInvalid() {
        GateDecision decision = evaluate(
                replace(MetricId.UNSUPPORTED_RECALL, metric(MetricId.UNSUPPORTED_RECALL, 99, 100, 1)),
                passingHighRiskMetrics(), completeEvidence());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.THRESHOLD_EVIDENCE_INVALID);
    }

    @Test
    void aMissingRequiredMetricIsInvalid() {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        metrics.removeIf(metric -> metric.metricId().equals(MetricId.DENIED_RECALL.metricCode()));

        GateDecision decision = evaluate(metrics, passingHighRiskMetrics(), completeEvidence());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.THRESHOLD_EVIDENCE_MISSING);
    }

    @Test
    void aMissingHighRiskMetricIsInvalid() {
        List<EvaluationReport.MetricValue> highRisk = new ArrayList<>(passingHighRiskMetrics());
        highRisk.removeIf(metric -> metric.metricId().equals(MetricId.REPEAT_CONSISTENCY.metricCode()));

        GateDecision decision = evaluate(passingMetrics(), highRisk, completeEvidence());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.THRESHOLD_EVIDENCE_MISSING);
    }

    @Test
    void aDuplicatedRequiredMetricIsInvalid() {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        metrics.add(metric(MetricId.CANONICAL_AST_EXACT_MATCH, 1, 100));

        GateDecision decision = evaluate(metrics, passingHighRiskMetrics(), completeEvidence());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.METRIC_EVIDENCE_DUPLICATE);
    }

    @Test
    void anInconsistentReportHashIsInvalid() {
        EvaluationReport report = gateEligibleReport();
        GateEvidencePins wrong = new GateEvidencePins(
                hash("not-the-report"), report.contract().candidateCorpus().corpusHash(),
                report.contract().candidateCorpus().manifestHash(),
                report.contract().review().reviewSetHash(), report.contract().review().manifestHash());

        GateDecision decision = evaluator.evaluate(
                new GateEvaluationRequest("gate-decision-1", report, wrong, completeEvidence(),
                        GateTestSupport.DECIDED_AT));

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.REPORT_HASH_MISMATCH);
    }

    @Test
    void aStaleCandidateCorpusOrReviewPinIsInvalid() {
        EvaluationReport report = gateEligibleReport();
        GateEvidencePins wrong = new GateEvidencePins(
                report.contentHash(), hash("other-corpus"), report.contract().candidateCorpus().manifestHash(),
                report.contract().review().reviewSetHash(), report.contract().review().manifestHash());

        GateDecision decision = evaluator.evaluate(
                new GateEvaluationRequest("gate-decision-1", report, wrong, completeEvidence(),
                        GateTestSupport.DECIDED_AT));

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.REPORT_PIN_MISMATCH);
    }

    // --- deterministic-layer evidence ------------------------------------------------------------

    @Test
    void aDeterministicCheckBelowOneHundredPercentIsInvalid() {
        List<DeterministicCheck> checks = new ArrayList<>();
        for (DeterministicCheckId id : DeterministicCheckId.ordered()) {
            checks.add(id == DeterministicCheckId.VALIDATOR
                    ? new DeterministicCheck(id, 24, 25)
                    : DeterministicCheck.passing(id, 25));
        }
        DeterministicLayerEvidence evidence = evidence(checks, identicalReplay());

        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics(), evidence);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.DETERMINISTIC_CHECK_FAILED);
    }

    @Test
    void anIncompleteDeterministicArtifactIsInvalid() {
        List<DeterministicCheck> checks = new ArrayList<>();
        for (DeterministicCheckId id : DeterministicCheckId.ordered()) {
            if (id != DeterministicCheckId.SIDE_EFFECT_SAFETY) {
                checks.add(DeterministicCheck.passing(id, 25));
            }
        }
        DeterministicLayerEvidence evidence = evidence(checks, identicalReplay());

        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics(), evidence);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE);
    }

    @Test
    void aReplayCountOtherThanOneHundredIsInvalid() {
        List<DeterministicCheck> checks = completeCheckingWith(50);
        DeterministicLayerEvidence evidence = evidence(
                checks, ReplayDeterminismEvidence.identical(50, hash("replay")));

        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics(), evidence);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.REPLAY_DETERMINISM_FAILED);
    }

    @Test
    void nonIdenticalReplayHashesAreInvalid() {
        DeterministicLayerEvidence evidence = evidence(
                completeCheckingWith(GateVersions.REQUIRED_REPLAY_COUNT),
                new ReplayDeterminismEvidence(GateVersions.REQUIRED_REPLAY_COUNT, 2, hash("replay")));

        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics(), evidence);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.REPLAY_DETERMINISM_FAILED);
    }

    // --- pin binding ------------------------------------------------------------------------------

    @Test
    void reusedDeterministicEvidenceWithChangedRunPinsIsInvalid() {
        DeterministicLayerEvidence evidence = completeEvidence(gateEligibleReport());
        List<EvaluationRunContract> changed = List.of(
                changedContract("gate-replay/2.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("other-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/2.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("other-metadata"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("other-corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("other-review-set"), AS_OF, "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), Instant.parse("2026-10-03T00:00:00Z"),
                        "code-revision-gate", hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-other",
                        hash("fixture-payload")),
                changedContract("gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                        hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate",
                        hash("other-fixture-payload")));

        for (EvaluationRunContract changedContract : changed) {
            EvaluationReport changedReport = report(
                    changedContract, false, List.of(caseOutcome("case-high-1", HIGH, 5)), passingMetrics(),
                    passingHighRiskMetrics(), new EvaluationReport.Summary(1, 5, 5, 0, 5, 0, 0, 0, false));

            GateDecision decision = evaluator.evaluate(new GateEvaluationRequest(
                    "gate-decision-1", changedReport, GateEvidencePins.of(changedReport), evidence,
                    GateTestSupport.DECIDED_AT));

            assertThat(decision.outcome()).as(changedContract.model().modelVersion())
                    .isEqualTo(GateOutcome.INVALID);
            assertThat(decision.issueCodes()).contains(GateErrorCodes.DETERMINISTIC_EVIDENCE_PIN_MISMATCH);
            assertThat(decision.thresholdChecks()).isEmpty();
        }
    }

    private static EvaluationRunContract changedContract(
            String modelVersion, com.company.campaign.shared.canonical.ContentHash artifactHash,
            String parserVersion, com.company.campaign.shared.canonical.ContentHash metadataHash,
            com.company.campaign.shared.canonical.ContentHash corpusHash,
            com.company.campaign.shared.canonical.ContentHash reviewSetHash, Instant asOf, String codeRevision,
            com.company.campaign.shared.canonical.ContentHash fixturePayloadHash) {
        return contract(
                CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH, modelVersion, artifactHash,
                parserVersion, metadataHash, corpusHash, reviewSetHash, asOf, codeRevision, fixturePayloadHash);
    }

    @Test
    void deterministicEvidenceWithoutAPinBindingIsInvalid() {
        DeterministicLayerEvidence unbound = DeterministicLayerEvidence.complete(
                null, 25, ReplayDeterminismEvidence.identical(GateVersions.REQUIRED_REPLAY_COUNT, hash("replay")));

        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics(), unbound);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.DETERMINISTIC_EVIDENCE_PIN_MISMATCH);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private GateDecision evaluate(List<EvaluationReport.MetricValue> overall,
            List<EvaluationReport.MetricValue> highRisk, DeterministicLayerEvidence evidence) {
        EvaluationReport report = gateEligibleReport(overall, highRisk);
        return evaluator.evaluate(GateTestSupport.request(report, GateEvidencePins.of(report), evidence));
    }

    private static List<EvaluationReport.MetricValue> replace(
            MetricId id, EvaluationReport.MetricValue value) {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        metrics.replaceAll(metric -> metric.metricId().equals(id.metricCode()) ? value : metric);
        return metrics;
    }

    private static DeterministicLayerEvidence evidence(
            List<DeterministicCheck> checks, ReplayDeterminismEvidence replay) {
        // Bound to the exact run pins of the synthetic Gate-eligible report these tests evaluate.
        return new DeterministicLayerEvidence(
                GateVersions.DETERMINISTIC_EVIDENCE_VERSION,
                GateRunPins.from(gateEligibleReport().contract()).contentHash(), checks, replay);
    }

    private static ReplayDeterminismEvidence identicalReplay() {
        return ReplayDeterminismEvidence.identical(GateVersions.REQUIRED_REPLAY_COUNT, hash("replay"));
    }

    private static List<DeterministicCheck> completeCheckingWith(int replayTotal) {
        List<DeterministicCheck> checks = new ArrayList<>();
        for (DeterministicCheckId id : DeterministicCheckId.ordered()) {
            int total = id == DeterministicCheckId.REPLAY_DETERMINISM ? replayTotal : 25;
            checks.add(DeterministicCheck.passing(id, total));
        }
        return checks;
    }
}