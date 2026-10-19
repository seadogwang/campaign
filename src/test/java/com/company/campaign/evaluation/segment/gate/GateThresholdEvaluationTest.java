package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.MetricId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.company.campaign.evaluation.segment.gate.GateTestSupport.metric;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.metricsWith;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingHighRiskMetrics;
import static com.company.campaign.evaluation.segment.gate.GateTestSupport.passingMetrics;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 2 and section 5: every Segment specification threshold evaluated exactly and independently, with
 * every boundary value just below, at and above the required value using exact decimals.
 *
 * <p>Each test changes exactly one reported metric value; every other field of the synthetic report is
 * byte-identical, so a verdict can only be attributed to that metric.
 */
class GateThresholdEvaluationTest {

    private final SegmentAiGateEvaluator evaluator = new SegmentAiGateEvaluator();

    // --- exact report and check shape ------------------------------------------------------------

    @Test
    void anExactCompleteReportIsPassed() {
        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(decision.issueCodes()).isEmpty();
        assertThat(decision.thresholdChecks()).hasSize(GateThresholds.required().size());
        assertThat(decision.thresholdChecks()).allSatisfy(check -> assertThat(check.passed()).isTrue());
    }

    @Test
    void everyRequiredThresholdRuleIsCarriedExactlyOnceInRuleOrder() {
        GateDecision decision = evaluate(passingMetrics(), passingHighRiskMetrics());

        List<ThresholdRule> rules = GateThresholds.required();
        for (int index = 0; index < rules.size(); index++) {
            ThresholdRule rule = rules.get(index);
            ThresholdCheck check = decision.thresholdChecks().get(index);
            assertThat(check.metricId()).isEqualTo(rule.metricId());
            assertThat(check.scope()).isEqualTo(rule.scope());
            assertThat(check.comparator()).isEqualTo(rule.comparator());
            assertThat(check.requiredValue()).isEqualTo(rule.requiredValue());
        }
        assertThat(rules).hasSize(9);
    }

    @Test
    void theFiveReportedOnlyMetricsNeverBecomeThresholds() {
        assertThat(GateThresholds.reportedOnly()).containsExactlyInAnyOrder(
                MetricId.INTENT_SLOT_ACCURACY.metricCode(),
                MetricId.FIRST_PASS_SUCCESS.metricCode(),
                MetricId.ONE_REPAIR_SUCCESS.metricCode(),
                MetricId.TOOL_RESOLUTION_ERROR_RATE.metricCode(),
                MetricId.REFINEMENT_TARGET_ACCURACY.metricCode());
        assertThat(GateThresholds.required())
                .noneMatch(rule -> GateThresholds.reportedOnly().contains(rule.metricId()));
    }

    // --- Canonical AST exact match >= 95% --------------------------------------------------------

    @Test
    void theCanonicalAstThresholdRejectsJustBelowAndAcceptsAtAndAbove95Percent() {
        assertThat(outcomeOf(overall(MetricId.CANONICAL_AST_EXACT_MATCH, 949_999, 1_000_000)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.CANONICAL_AST_EXACT_MATCH, 95, 100)))
                .isEqualTo(GateOutcome.PASSED);
        assertThat(outcomeOf(overall(MetricId.CANONICAL_AST_EXACT_MATCH, 950_001, 1_000_000)))
                .isEqualTo(GateOutcome.PASSED);
    }

    // --- Member set exact match >= 95% -----------------------------------------------------------

    @Test
    void theMemberSetThresholdRejectsJustBelowAndAcceptsAtAndAbove95Percent() {
        assertThat(outcomeOf(overall(MetricId.MEMBER_SET_EXACT_MATCH, 949_999, 1_000_000)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.MEMBER_SET_EXACT_MATCH, 95, 100)))
                .isEqualTo(GateOutcome.PASSED);
        assertThat(outcomeOf(overall(MetricId.MEMBER_SET_EXACT_MATCH, 950_001, 1_000_000)))
                .isEqualTo(GateOutcome.PASSED);
    }

    @Test
    void theHighRiskMemberSetThresholdRequiresExactlyOneHundredPercent() {
        assertThat(outcomeOfHighRisk(highRisk(MetricId.MEMBER_SET_EXACT_MATCH, 9_999, 10_000)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOfHighRisk(highRisk(MetricId.MEMBER_SET_EXACT_MATCH, 100, 100)))
                .isEqualTo(GateOutcome.PASSED);
    }

    // --- Clarification recall >= 98% and precision >= 90% ---------------------------------------

    @Test
    void theClarificationRecallThresholdRejectsJustBelowAndAcceptsAtAndAbove98Percent() {
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_RECALL, 97, 100))).isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_RECALL, 98, 100))).isEqualTo(GateOutcome.PASSED);
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_RECALL, 99, 100))).isEqualTo(GateOutcome.PASSED);
    }

    @Test
    void theClarificationPrecisionThresholdRejectsJustBelowAndAcceptsAtAndAbove90Percent() {
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_PRECISION, 899, 1000)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_PRECISION, 9, 10))).isEqualTo(GateOutcome.PASSED);
        assertThat(outcomeOf(overall(MetricId.CLARIFICATION_PRECISION, 901, 1000)))
                .isEqualTo(GateOutcome.PASSED);
    }

    // --- Unsupported and denied recall = 100% ----------------------------------------------------

    @Test
    void theUnsupportedRecallThresholdRequiresExactlyOneHundredPercent() {
        assertThat(outcomeOf(overall(MetricId.UNSUPPORTED_RECALL, 99, 100))).isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.UNSUPPORTED_RECALL, 100, 100))).isEqualTo(GateOutcome.PASSED);
    }

    @Test
    void theDeniedRecallThresholdRequiresExactlyOneHundredPercent() {
        assertThat(outcomeOf(overall(MetricId.DENIED_RECALL, 99, 100))).isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.DENIED_RECALL, 100, 100))).isEqualTo(GateOutcome.PASSED);
    }

    // --- Silent critical assumption rate = 0% ----------------------------------------------------

    @Test
    void theSilentCriticalAssumptionRateThresholdRequiresExactlyZero() {
        assertThat(outcomeOf(overall(MetricId.SILENT_CRITICAL_ASSUMPTION_RATE, 1, 100)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOf(overall(MetricId.SILENT_CRITICAL_ASSUMPTION_RATE, 0, 100)))
                .isEqualTo(GateOutcome.PASSED);
    }

    // --- High-risk repeat consistency = 100% -----------------------------------------------------

    @Test
    void theHighRiskRepeatConsistencyThresholdRequiresExactlyOneHundredPercent() {
        assertThat(outcomeOfHighRisk(highRisk(MetricId.REPEAT_CONSISTENCY, 4, 5)))
                .isEqualTo(GateOutcome.FAILED);
        assertThat(outcomeOfHighRisk(highRisk(MetricId.REPEAT_CONSISTENCY, 5, 5)))
                .isEqualTo(GateOutcome.PASSED);
    }

    // --- First-pass and one-repair stay evidence, not thresholds ---------------------------------

    @Test
    void firstPassAndOneRepairAreRecordedSeparatelyWithoutAnyThreshold() {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        metrics.replaceAll(metric -> switch (metric.metricId()) {
            case "first-pass-success" -> metric(MetricId.FIRST_PASS_SUCCESS, 0, 100);
            case "one-repair-success" -> metric(MetricId.ONE_REPAIR_SUCCESS, 0, 0);
            default -> metric;
        });

        GateDecision decision = evaluate(metrics, passingHighRiskMetrics());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(decision.thresholdChecks()).noneMatch(check ->
                check.metricId().equals(MetricId.FIRST_PASS_SUCCESS.metricCode())
                        || check.metricId().equals(MetricId.ONE_REPAIR_SUCCESS.metricCode()));
    }

    // --- a sound report below a threshold fails rather than invalidating -------------------------

    @Test
    void aGateEligibleReportBelowAThresholdIsFailedNotInvalid() {
        GateDecision decision = evaluate(
                metricsWith(MetricId.CANONICAL_AST_EXACT_MATCH, metric(
                        MetricId.CANONICAL_AST_EXACT_MATCH, 1, 100)),
                passingHighRiskMetrics());

        assertThat(decision.outcome()).isEqualTo(GateOutcome.FAILED);
        assertThat(decision.issueCodes()).containsExactly(GateErrorCodes.THRESHOLD_NOT_MET);
        assertThat(decision.thresholdChecks()).hasSize(GateThresholds.required().size());
        assertThat(decision.thresholdChecks())
                .anyMatch(check -> check.metricId().equals(MetricId.CANONICAL_AST_EXACT_MATCH.metricCode())
                        && !check.passed());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private GateOutcome outcomeOf(List<EvaluationReport.MetricValue> overall,
            List<EvaluationReport.MetricValue> highRisk) {
        return evaluate(overall, highRisk).outcome();
    }

    private GateOutcome outcomeOf(List<EvaluationReport.MetricValue> overall) {
        return outcomeOf(overall, passingHighRiskMetrics());
    }

    private GateOutcome outcomeOfHighRisk(List<EvaluationReport.MetricValue> highRisk) {
        return evaluate(passingMetrics(), highRisk).outcome();
    }

    private static List<EvaluationReport.MetricValue> overall(
            MetricId id, int numerator, int denominator) {
        return metricsWith(id, metric(id, numerator, denominator));
    }

    private static List<EvaluationReport.MetricValue> highRisk(
            MetricId id, int numerator, int denominator) {
        return metricsWith(id, metric(id, numerator, denominator));
    }

    private GateDecision evaluate(List<EvaluationReport.MetricValue> overall,
            List<EvaluationReport.MetricValue> highRisk) {
        EvaluationReport report = GateTestSupport.gateEligibleReport(overall, highRisk);
        return evaluator.evaluate(GateTestSupport.request(report));
    }
}