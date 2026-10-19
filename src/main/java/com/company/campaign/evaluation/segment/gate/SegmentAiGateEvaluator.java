package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The host-owned deterministic Gate evaluator.
 *
 * <p>It turns one frozen T13 report plus a trusted deterministic-layer evidence artifact into one canonical
 * proposed {@link GateDecision}. It calls no model, reruns no Case and executes no fixture: every number it
 * judges is already frozen in the report, and every threshold it applies is stated by Segment specification
 * section 23.
 *
 * <p>The order is literal:
 * <ol>
 *   <li>prove the declared report hash, the frozen candidate-corpus and review pins, and the Gate eligibility
 *       of the report's frozen revision;</li>
 *   <li>prove the run is sound and accounts for every required attempt;</li>
 *   <li>prove the deterministic-layer evidence is bound to those exact run pins, complete, 100% and exactly
 *       100 identical replays;</li>
 *   <li>prove the high-risk Cases carry exactly five required attempts;</li>
 *   <li>prove every required threshold metric is present, defined, valid and unduplicated;</li>
 *   <li>only then evaluate the thresholds exactly and independently.</li>
 * </ol>
 *
 * <p>Invalidity takes precedence over numeric failure: any of the first five conditions produces {@code
 * INVALID} with the bounded issue codes that explain it, and no threshold is judged. A sound, Gate-eligible
 * run whose complete evidence is below any threshold is {@code FAILED}; a sound, Gate-eligible run that meets
 * every threshold is {@code PASSED}. No composite AI score is ever computed.
 */
public final class SegmentAiGateEvaluator {

    /** Evaluates one Gate request and returns the canonical proposed decision. */
    public GateDecision evaluate(GateEvaluationRequest request) {
        if (request == null) {
            throw GateException.of(
                    GateErrorCodes.REPORT_HASH_MISMATCH, "A Gate evaluation requires its complete request");
        }
        EvaluationReport report = request.report();
        GateRunPins runPins = GateRunPins.from(report.contract());

        Set<String> invalid = new LinkedHashSet<>();
        collectEligibilityIssues(request, report, invalid);
        collectEvidenceIssues(report, runPins, request.deterministicEvidence(), invalid);

        if (!invalid.isEmpty()) {
            return GateDecision.of(
                    request.decisionId(), GateOutcome.INVALID, report.contentHash(), runPins,
                    request.deterministicEvidence(), List.of(), new ArrayList<>(invalid),
                    report.contract().review().issueCodes(), request.decidedAt());
        }

        List<ThresholdCheck> checks = evaluateThresholds(report);
        boolean failed = checks.stream().anyMatch(check -> !check.passed());
        if (failed) {
            return GateDecision.of(
                    request.decisionId(), GateOutcome.FAILED, report.contentHash(), runPins,
                    request.deterministicEvidence(), checks, List.of(GateErrorCodes.THRESHOLD_NOT_MET),
                    report.contract().review().issueCodes(), request.decidedAt());
        }
        return GateDecision.of(
                request.decisionId(), GateOutcome.PASSED, report.contentHash(), runPins,
                request.deterministicEvidence(), checks, List.of(),
                report.contract().review().issueCodes(), request.decidedAt());
    }

    // ------------------------------------------------------------------ eligibility and soundness

    private void collectEligibilityIssues(
            GateEvaluationRequest request, EvaluationReport report, Set<String> invalid) {
        if (!request.evidencePins().reportHashMatches(report)) {
            invalid.add(GateErrorCodes.REPORT_HASH_MISMATCH);
        }
        if (!report.contract().review().classification().isGateEligible()) {
            invalid.add(GateErrorCodes.REPORT_NOT_GATE_ELIGIBLE);
            invalid.add(GateErrorCodes.REVIEW_EVIDENCE_NOT_READY);
        }
        if (report.summary().runInvalid()
                || report.evidenceClass() == EvaluationReport.ReportEvidenceClass.INVALID) {
            invalid.add(GateErrorCodes.REPORT_RUN_INVALID);
        }
        if (!request.evidencePins().pinsMatch(report)) {
            invalid.add(GateErrorCodes.REPORT_PIN_MISMATCH);
        }
        if (hasUnaccountedAttempts(report)) {
            invalid.add(GateErrorCodes.ATTEMPTS_UNACCOUNTED);
        }
        if (hasDuplicateMetricEvidence(report)) {
            invalid.add(GateErrorCodes.METRIC_EVIDENCE_DUPLICATE);
        }
    }

    private static boolean hasUnaccountedAttempts(EvaluationReport report) {
        if (report.summary().notRunAttemptCount() > 0) {
            return true;
        }
        for (EvaluationReport.CaseOutcome outcome : report.cases()) {
            if (outcome.notRunAttempts() > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDuplicateMetricEvidence(EvaluationReport report) {
        if (hasDuplicateMetricIds(report.metrics())) {
            return true;
        }
        for (List<EvaluationReport.MetricValue> group : report.perRisk().values()) {
            if (hasDuplicateMetricIds(group)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDuplicateMetricIds(List<EvaluationReport.MetricValue> metrics) {
        Set<String> seen = new LinkedHashSet<>();
        for (EvaluationReport.MetricValue metric : metrics) {
            if (!seen.add(metric.metricId())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ deterministic layer and repeats

    private void collectEvidenceIssues(
            EvaluationReport report, GateRunPins runPins, DeterministicLayerEvidence evidence,
            Set<String> invalid) {
        // The evidence must name the exact gated inputs of the report it verifies, before any threshold is
        // judged: otherwise a passing artifact could be reused with a different model, Prompt, Metadata,
        // Corpus, fixture, time/watermark, code/environment or review set.
        if (!evidence.bindsRunPins(runPins)) {
            invalid.add(GateErrorCodes.DETERMINISTIC_EVIDENCE_PIN_MISMATCH);
        }
        if (!evidence.isComplete()) {
            invalid.add(GateErrorCodes.DETERMINISTIC_EVIDENCE_INCOMPLETE);
        }
        if (!evidence.allChecksPassing()) {
            invalid.add(GateErrorCodes.DETERMINISTIC_CHECK_FAILED);
        }
        if (!evidence.replayDeterminismSatisfied()) {
            invalid.add(GateErrorCodes.REPLAY_DETERMINISM_FAILED);
        }
        if (!highRiskRepeatsAreExact(report)) {
            invalid.add(GateErrorCodes.HIGH_RISK_REPEAT_REQUIREMENT_INVALID);
        }
        for (ThresholdRule rule : GateThresholds.required()) {
            EvaluationReport.MetricValue metric = metricFor(report, rule);
            if (metric == null) {
                invalid.add(GateErrorCodes.THRESHOLD_EVIDENCE_MISSING);
            } else if (!metricEvidenceIsUsable(metric)) {
                invalid.add(GateErrorCodes.THRESHOLD_EVIDENCE_INVALID);
            }
        }
    }

    private static boolean highRiskRepeatsAreExact(EvaluationReport report) {
        if (report.contract().candidateCorpus().requiredHighRiskRepeats()
                != GateVersions.REQUIRED_HIGH_RISK_ATTEMPTS) {
            return false;
        }
        int highRiskCases = 0;
        for (EvaluationReport.CaseOutcome outcome : report.cases()) {
            if (RiskLevel.HIGH.name().equals(outcome.riskLevel())) {
                highRiskCases++;
                if (outcome.requiredAttempts() != GateVersions.REQUIRED_HIGH_RISK_ATTEMPTS) {
                    return false;
                }
            }
        }
        return highRiskCases > 0;
    }

    private static boolean metricEvidenceIsUsable(EvaluationReport.MetricValue metric) {
        if (!metric.defined() || metric.invalid() > 0 || metric.value() == null) {
            return false;
        }
        try {
            GateDecimals.canonical(metric.value(), "observed metric value");
        } catch (GateException malformed) {
            return false;
        }
        return GateDecimals.ratio(metric.numerator(), metric.denominator()).equals(metric.value());
    }

    // ------------------------------------------------------------------ threshold evaluation

    private static List<ThresholdCheck> evaluateThresholds(EvaluationReport report) {
        List<ThresholdCheck> checks = new ArrayList<>(GateThresholds.required().size());
        for (ThresholdRule rule : GateThresholds.required()) {
            checks.add(ThresholdCheck.evaluate(rule, metricFor(report, rule)));
        }
        return List.copyOf(checks);
    }

    private static EvaluationReport.MetricValue metricFor(EvaluationReport report, ThresholdRule rule) {
        List<EvaluationReport.MetricValue> metrics = rule.scope() == ThresholdScope.HIGH_RISK
                ? report.perRisk().get(RiskLevel.HIGH.name())
                : report.metrics();
        if (metrics == null) {
            return null;
        }
        EvaluationReport.MetricValue found = null;
        for (EvaluationReport.MetricValue metric : metrics) {
            if (metric.metricId().equals(rule.metricId())) {
                if (found != null) {
                    // A duplicated required metric is ambiguous evidence, never a silent first-match.
                    return null;
                }
                found = metric;
            }
        }
        return found;
    }
}