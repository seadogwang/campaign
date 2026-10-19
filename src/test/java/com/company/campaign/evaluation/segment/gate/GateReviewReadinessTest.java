package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.HIGH;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.caseOutcome;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.contract;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.gateEligibleReport;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.hash;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingHighRiskMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.report;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.unaccountedCase;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3 and section 5: review readiness and failure precedence.
 *
 * <p>A development-only, run-invalid or not-fully-reviewed report is {@code INVALID} with the frozen review
 * issue codes preserved in bounded evidence, invalidity takes precedence over numeric failure, and a stale
 * development report can never be relabelled as Gate evidence.
 */
class GateReviewReadinessTest {

    private final SegmentAiGateEvaluator evaluator = new SegmentAiGateEvaluator();

    @Test
    void aDevelopmentOnlyReportIsInvalidAndPreservesTheFrozenReviewCodes() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.DEVELOPMENT_ONLY,
                        List.of(EvaluationErrorCodes.REVIEW_PENDING, EvaluationErrorCodes.REVIEW_BINDING_STALE),
                        5, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 5)),
                passingMetrics(), passingHighRiskMetrics(), soundSummary());

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes())
                .contains(GateErrorCodes.REPORT_NOT_GATE_ELIGIBLE, GateErrorCodes.REVIEW_EVIDENCE_NOT_READY);
        assertThat(decision.reviewIssueCodes())
                .containsExactly(EvaluationErrorCodes.REVIEW_PENDING, EvaluationErrorCodes.REVIEW_BINDING_STALE);
    }

    @Test
    void aRejectedOrMissingOrStaleReviewExpectationIsAlwaysInvalid() {
        for (String code : List.of(
                EvaluationErrorCodes.REVIEW_REJECTED, EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING,
                EvaluationErrorCodes.REVIEW_BINDING_STALE, EvaluationErrorCodes.REVIEW_PENDING)) {
            EvaluationReport report = report(
                    contract(CorpusUsageClassification.DEVELOPMENT_ONLY, List.of(code), 5, HIGH),
                    false, List.of(caseOutcome("case-high-1", HIGH, 5)),
                    passingMetrics(), passingHighRiskMetrics(), soundSummary());

            GateDecision decision = evaluate(report);

            assertThat(decision.outcome()).as(code).isEqualTo(GateOutcome.INVALID);
            assertThat(decision.reviewIssueCodes()).as(code).containsExactly(code);
        }
    }

    @Test
    void anInvalidReviewedCorpusIsInvalid() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.INVALID, List.of(EvaluationErrorCodes.REVIEW_HASH_MISMATCH),
                        5, HIGH),
                true, List.of(caseOutcome("case-high-1", HIGH, 5)),
                passingMetrics(), passingHighRiskMetrics(), invalidSummary());

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.REPORT_RUN_INVALID);
    }

    @Test
    void aRunInvalidReportIsInvalidEvenWhenTheCorpusIsGateEligible() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH),
                true, List.of(caseOutcome("case-high-1", HIGH, 5)),
                passingMetrics(), passingHighRiskMetrics(), invalidSummary());

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes())
                .contains(GateErrorCodes.REPORT_RUN_INVALID)
                .doesNotContain(GateErrorCodes.REPORT_NOT_GATE_ELIGIBLE);
    }

    @Test
    void anUnaccountedAttemptIsInvalid() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH),
                false, List.of(unaccountedCase("case-high-1", 1)),
                passingMetrics(), passingHighRiskMetrics(),
                new EvaluationReport.Summary(1, 5, 4, 1, 4, 0, 0, 0, false));

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.ATTEMPTS_UNACCOUNTED);
    }

    @Test
    void highRiskCasesWithoutExactlyFiveAttemptsAreInvalid() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 3)),
                passingMetrics(), passingHighRiskMetrics(),
                new EvaluationReport.Summary(1, 3, 3, 0, 3, 0, 0, 0, false));

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.HIGH_RISK_REPEAT_REQUIREMENT_INVALID);
    }

    @Test
    void aManifestThatRequiresOtherThanFiveHighRiskRepeatsIsInvalid() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 3, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 5)),
                passingMetrics(), passingHighRiskMetrics(), soundSummary());

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(GateErrorCodes.HIGH_RISK_REPEAT_REQUIREMENT_INVALID);
    }

    @Test
    void invalidityTakesPrecedenceOverNumericFailure() {
        EvaluationReport report = report(
                contract(CorpusUsageClassification.DEVELOPMENT_ONLY, List.of(EvaluationErrorCodes.REVIEW_PENDING),
                        5, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 5)),
                GateTestSupport.metricsWith(
                        com.company.campaign.evaluation.segment.runner.MetricId.CANONICAL_AST_EXACT_MATCH,
                        GateTestSupport.metric(
                                com.company.campaign.evaluation.segment.runner.MetricId
                                        .CANONICAL_AST_EXACT_MATCH,
                                0, 100)),
                passingHighRiskMetrics(), soundSummary());

        GateDecision decision = evaluate(report);

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).doesNotContain(GateErrorCodes.THRESHOLD_NOT_MET);
        assertThat(decision.thresholdChecks()).isEmpty();
    }

    @Test
    void aStaleDevelopmentReportCanNeverBeRelabelledAsGateEvidence() {
        EvaluationReport development = report(
                contract(CorpusUsageClassification.DEVELOPMENT_ONLY, List.of(EvaluationErrorCodes.REVIEW_PENDING),
                        5, HIGH),
                false, List.of(caseOutcome("case-high-1", HIGH, 5)),
                passingMetrics(), passingHighRiskMetrics(), soundSummary());
        GateEvidencePins relabelled = new GateEvidencePins(
                hash("relabelled-report-hash"),
                development.contract().candidateCorpus().corpusHash(),
                development.contract().candidateCorpus().manifestHash(),
                development.contract().review().reviewSetHash(),
                development.contract().review().manifestHash());

        GateDecision decision = evaluator.evaluate(new GateEvaluationRequest(
                "gate-decision-1", development, relabelled, GateTestSupport.completeEvidence(development),
                GateTestSupport.DECIDED_AT));

        assertThat(decision.outcome()).isEqualTo(GateOutcome.INVALID);
        assertThat(decision.issueCodes()).contains(
                GateErrorCodes.REPORT_HASH_MISMATCH, GateErrorCodes.REPORT_NOT_GATE_ELIGIBLE);
        assertThat(decision.outcome()).isNotEqualTo(GateOutcome.PASSED);
    }

    @Test
    void aGateEligibleReportWithAReceiptedPinIsStillPassed() {
        assertThat(evaluate(gateEligibleReport()).outcome()).isEqualTo(GateOutcome.PASSED);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private GateDecision evaluate(EvaluationReport report) {
        return evaluator.evaluate(GateTestSupport.request(report));
    }

    private static EvaluationReport.Summary soundSummary() {
        return new EvaluationReport.Summary(1, 5, 5, 0, 5, 0, 0, 0, false);
    }

    private static EvaluationReport.Summary invalidSummary() {
        return new EvaluationReport.Summary(1, 5, 5, 0, 5, 0, 0, 0, true);
    }
}