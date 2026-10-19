package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.runner.DecodingParameters;
import com.company.campaign.evaluation.segment.runner.EvaluationCasePins;
import com.company.campaign.evaluation.segment.runner.EvaluationReport;
import com.company.campaign.evaluation.segment.runner.EvaluationRunContract;
import com.company.campaign.evaluation.segment.runner.MetricId;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic immutable Gate inputs.
 *
 * <p>These fixtures build a structurally valid, Gate-eligible T13 report and a complete deterministic
 * artifact without touching the checked-in Corpus or fabricating a human review decision. A threshold
 * boundary test varies exactly one reported metric; every other field stays byte-identical.
 */
final class GateTestSupport {

    static final Instant AS_OF = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant WATERMARK = Instant.parse("2026-09-30T00:00:00Z");
    static final Instant DECIDED_AT = Instant.parse("2026-10-02T09:30:00Z");
    static final String TIMEZONE = "Asia/Shanghai";
    static final String HIGH = "HIGH";

    private GateTestSupport() {
    }

    static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ metrics

    static EvaluationReport.MetricValue metric(MetricId id, int numerator, int denominator, int invalid) {
        boolean defined = denominator > 0;
        return new EvaluationReport.MetricValue(
                id.metricCode(), numerator, denominator, 0, invalid, defined,
                defined ? GateDecimals.ratio(numerator, denominator) : null);
    }

    /** A defined metric with no invalid attempts. */
    static EvaluationReport.MetricValue metric(MetricId id, int numerator, int denominator) {
        return metric(id, numerator, denominator, 0);
    }

    /** Every metric at a passing value; the two reported-only rates stay deliberately mid-range. */
    static List<EvaluationReport.MetricValue> passingMetrics() {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>();
        for (MetricId id : MetricId.ordered()) {
            metrics.add(switch (id) {
                case CANONICAL_AST_EXACT_MATCH, MEMBER_SET_EXACT_MATCH, CLARIFICATION_RECALL,
                        CLARIFICATION_PRECISION, UNSUPPORTED_RECALL, DENIED_RECALL, REPEAT_CONSISTENCY,
                        INTENT_SLOT_ACCURACY, FIRST_PASS_SUCCESS, REFINEMENT_TARGET_ACCURACY ->
                        metric(id, 100, 100);
                case SILENT_CRITICAL_ASSUMPTION_RATE, TOOL_RESOLUTION_ERROR_RATE -> metric(id, 0, 100);
                case ONE_REPAIR_SUCCESS -> metric(id, 50, 100);
            });
        }
        return List.copyOf(metrics);
    }

    /** The same passing set with one metric replaced, so a boundary test changes exactly one number. */
    static List<EvaluationReport.MetricValue> metricsWith(MetricId replaced, EvaluationReport.MetricValue value) {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        metrics.replaceAll(metric -> metric.metricId().equals(replaced.metricCode()) ? value : metric);
        return List.copyOf(metrics);
    }

    /** A complete high-risk breakdown that satisfies both high-risk thresholds. */
    static List<EvaluationReport.MetricValue> passingHighRiskMetrics() {
        List<EvaluationReport.MetricValue> metrics = new ArrayList<>(passingMetrics());
        return List.copyOf(metrics);
    }

    static Map<String, List<EvaluationReport.MetricValue>> highRiskMap(
            List<EvaluationReport.MetricValue> highRisk) {
        Map<String, List<EvaluationReport.MetricValue>> map = new LinkedHashMap<>();
        map.put(HIGH, highRisk);
        return map;
    }

    // ------------------------------------------------------------------ report

    /** A gate-eligible, sound report with one high-risk Case of exactly five attempts. */
    static EvaluationReport gateEligibleReport(List<EvaluationReport.MetricValue> overall,
            List<EvaluationReport.MetricValue> highRisk) {
        EvaluationRunContract contract = contract(CorpusUsageClassification.GATE_ELIGIBLE, List.of(), 5, HIGH);
        List<EvaluationReport.CaseOutcome> cases = List.of(caseOutcome("case-high-1", HIGH, 5));
        EvaluationReport.Summary summary = new EvaluationReport.Summary(1, 5, 5, 0, 5, 0, 0, 0, false);
        return EvaluationReport.of(
                "gate-test-report", contract, false, cases, overall, Map.of(), highRiskMap(highRisk), summary);
    }

    static EvaluationReport gateEligibleReport() {
        return gateEligibleReport(passingMetrics(), passingHighRiskMetrics());
    }

    static EvaluationReport report(EvaluationRunContract contract, boolean runInvalid,
            List<EvaluationReport.CaseOutcome> cases, List<EvaluationReport.MetricValue> overall,
            List<EvaluationReport.MetricValue> highRisk, EvaluationReport.Summary summary) {
        return EvaluationReport.of(
                "gate-test-report", contract, runInvalid, cases, overall, Map.of(), highRiskMap(highRisk),
                summary);
    }

    static EvaluationReport.CaseOutcome caseOutcome(String caseId, String riskLevel, int attempts) {
        List<EvaluationReport.AttemptOutcome> outcomes = new ArrayList<>(attempts);
        for (int number = 1; number <= attempts; number++) {
            outcomes.add(new EvaluationReport.AttemptOutcome(
                    number, "replay-" + caseId + "-" + number, "RESOLVED", null, null, List.of(), 1,
                    false, false, null));
        }
        return new EvaluationReport.CaseOutcome(caseId, "CATEGORY_ONE", riskLevel, attempts, outcomes, 0, List.of(), null);
    }

    /** One high-risk Case whose five required attempts were not all run. */
    static EvaluationReport.CaseOutcome unaccountedCase(String caseId, int notRun) {
        List<EvaluationReport.AttemptOutcome> outcomes = new ArrayList<>();
        int executed = 5 - notRun;
        for (int number = 1; number <= executed; number++) {
            outcomes.add(new EvaluationReport.AttemptOutcome(
                    number, "replay-" + caseId + "-" + number, "RESOLVED", null, null, List.of(), 1,
                    false, false, null));
        }
        List<Integer> notRunNumbers = new ArrayList<>();
        for (int number = executed + 1; number <= 5; number++) {
            notRunNumbers.add(number);
        }
        return new EvaluationReport.CaseOutcome(
                caseId, "CATEGORY_ONE", HIGH, 5, outcomes, notRun, notRunNumbers, "NOT_RUN_DUE_TO_RUN_INVALID");
    }

    static EvaluationRunContract contract(CorpusUsageClassification classification, List<String> reviewIssueCodes,
            int highRiskRepeats, String caseRisk) {
        return contract(classification, reviewIssueCodes, highRiskRepeats, caseRisk,
                "gate-replay/1.0", hash("system-prompt"), "resolver/1.0", hash("metadata-catalog"),
                hash("corpus"), hash("review-set"), AS_OF, "code-revision-gate", hash("fixture-payload"));
    }

    /** A parameterized contract, so a readiness test can change exactly one gated input. */
    static EvaluationRunContract contract(
            CorpusUsageClassification classification, List<String> reviewIssueCodes, int highRiskRepeats,
            String caseRisk, String modelVersion, ContentHash artifactHash, String parserVersion,
            ContentHash metadataHash, ContentHash corpusHash, ContentHash reviewSetHash, Instant asOf,
            String codeRevision, ContentHash fixturePayloadHash) {
        return new EvaluationRunContract(
                "segment-evaluation-run-contract/1.0",
                "segment-evaluation-report/1.0",
                "gate-test-run",
                new EvaluationRunContract.ModelPin("gate-replay", "gate-replay", modelVersion),
                DecodingParameters.deterministicReplay(4096, 20261006L),
                new EvaluationRunContract.ArtifactPins(List.of(
                        EvaluationRunContract.ArtifactPin.present("system-prompt", artifactHash),
                        EvaluationRunContract.ArtifactPin.present("skill", hash("skill")),
                        EvaluationRunContract.ArtifactPin.present("tool-schema", hash("tool-schema")))),
                new EvaluationRunContract.ParserPins(
                        "intent-parser/1.0", "intent-codec/1.0", parserVersion, "canonicalizer/1.0",
                        "validator/1.0", "planner/1.0"),
                new EvaluationRunContract.MetadataPins("metadata-catalog/1.0", metadataHash),
                new EvaluationRunContract.CandidateCorpusPins(
                        "corpus/1.0", corpusHash, hash("corpus-manifest"), "metadata-catalog/1.0",
                        metadataHash, "fixture-catalog/1.0", hash("fixture-catalog"),
                        1, 1, highRiskRepeats),
                new EvaluationRunContract.ReviewPins(
                        classification, reviewIssueCodes, reviewSetHash, hash("review-manifest")),
                new EvaluationRunContract.TimePins(asOf, TIMEZONE, asOf.minusSeconds(86_400L)),
                new EvaluationRunContract.CodePins(codeRevision, "environment-gate"),
                List.of(casePins("case-high-1", caseRisk, fixturePayloadHash)));
    }

    static EvaluationCasePins casePins(String caseId, String riskLevel, ContentHash fixturePayloadHash) {
        return new EvaluationCasePins(
                caseId, "CATEGORY_ONE", riskLevel, riskLevel.equals(HIGH) ? 5 : 1, false,
                hash(caseId + "-case"), hash(caseId + "-expected"), hash(caseId + "-review"),
                "snapshot-1", "snapshot/1.0", hash("snapshot-payload"),
                "context-1", hash("context-payload"), "zh-CN", AS_OF, TIMEZONE, WATERMARK,
                List.of(new EvaluationCasePins.FixturePin(
                        "fixture-1", "fixture/1.0", fixturePayloadHash,
                        "mapping/1.0", hash("mapping"), "scope/1.0", hash("scope"), "fx-1", hash("fx"))));
    }

    // ------------------------------------------------------------------ deterministic evidence

    /** A complete deterministic artifact bound to the exact run pins of the given report. */
    static DeterministicLayerEvidence completeEvidence(EvaluationReport report) {
        return completeEvidence(GateRunPins.from(report.contract()).contentHash());
    }

    /** A complete deterministic artifact bound to one exact run-pin hash. */
    static DeterministicLayerEvidence completeEvidence(ContentHash runPinsHash) {
        return DeterministicLayerEvidence.complete(
                runPinsHash, 25,
                ReplayDeterminismEvidence.identical(GateVersions.REQUIRED_REPLAY_COUNT, hash("replay")));
    }

    /** A complete deterministic artifact bound to the default Gate-eligible report's run pins. */
    static DeterministicLayerEvidence completeEvidence() {
        return completeEvidence(gateEligibleReport());
    }

    // ------------------------------------------------------------------ requests and decisions

    static GateEvaluationRequest request(EvaluationReport report) {
        return request(report, GateEvidencePins.of(report), completeEvidence(report));
    }

    static GateEvaluationRequest request(
            EvaluationReport report, GateEvidencePins pins, DeterministicLayerEvidence evidence) {
        return new GateEvaluationRequest("gate-decision-1", report, pins, evidence, DECIDED_AT);
    }

    static GateDecision passedDecision() {
        return new SegmentAiGateEvaluator().evaluate(request(gateEligibleReport()));
    }

    // ------------------------------------------------------------------ owner quorum

    /** One bounded owner approval at the explicit decision time. */
    static GateOwnerApproval owner(String ownerRef, GateOwnerRole ownerRole) {
        return new GateOwnerApproval(ownerRef, ownerRole, DECIDED_AT);
    }

    /** One complete PRODUCT/DATA/TEST owner quorum with distinct opaque references. */
    static List<GateOwnerApproval> quorum() {
        return List.of(
                owner("owner-product-1", GateOwnerRole.PRODUCT),
                owner("owner-data-1", GateOwnerRole.DATA),
                owner("owner-test-1", GateOwnerRole.TEST));
    }

    static GateApprovalCommand quorumCommand(ContentHash decisionHash) {
        return new GateApprovalCommand(decisionHash, quorum());
    }

    /** A lookup port that returns one fixed latest approved decision for the Segment capability. */
    static GateApprovalLookupPort lookup(GateApprovedDecision approved) {
        return capabilityId -> approved == null ? java.util.Optional.empty() : java.util.Optional.of(approved);
    }

    /** An authorization port that explicitly approves every exact owner, role and decision hash asked. */
    static GateApprovalAuthorizationPort permissiveAuthorization() {
        return (ownerRef, ownerRole, decisionHash) ->
                GateApprovalAuthorizationDecision.allow(ownerRef, ownerRole, decisionHash);
    }
}