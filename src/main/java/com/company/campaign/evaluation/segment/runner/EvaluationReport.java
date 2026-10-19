package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The immutable T13 evaluation report.
 *
 * <p>It is a candidate-corpus diagnostic, never a Gate verdict. There is deliberately no {@code PASSED} or
 * {@code FAILED} value anywhere in this contract: the only evidence classes are {@code GATE_ELIGIBLE},
 * {@code DEVELOPMENT_ONLY} and {@code INVALID}, and the class is frozen with the run-start review
 * classification. A report that recorded {@code DEVELOPMENT_ONLY} stays {@code DEVELOPMENT_ONLY} for its whole
 * life; a new reviewed state requires a new run, because the classification, the review issue codes and the
 * review-set hash are components of the frozen run contract and therefore of this report's own hash.
 *
 * <p>What the report may contain is a bounded, closed vocabulary: Case identifiers, attempt numbers, stable
 * codes, safe artifact hashes, aggregate counts, replay identifiers and exact decimals. It never contains a raw
 * utterance, a raw model response, a credential, a stack trace, SQL, a storage identifier, a tenant/user/
 * DataScope fact, unrestricted Metadata, a member identifier or a member sample. Exact member sets exist only
 * inside the comparison boundary and are represented here by set hashes, counts and symmetric-difference
 * counts.
 */
public final class EvaluationReport {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** The evidence class of one report. There is no pass/fail member by construction. */
    public enum ReportEvidenceClass {

        /** The frozen corpus was gate-eligible at run start. Only T14 may act on this. */
        GATE_ELIGIBLE,

        /** The frozen corpus was usable but not gate-eligible, so the report is diagnostic only. */
        DEVELOPMENT_ONLY,

        /** The run was unsound: an infrastructure, input or observation failure occurred. */
        INVALID;

        /** The class implied by a run-start review classification. */
        public static ReportEvidenceClass of(com.company.campaign.evaluation.segment.review.CorpusUsageClassification c) {
            return switch (c) {
                case GATE_ELIGIBLE -> GATE_ELIGIBLE;
                case DEVELOPMENT_ONLY -> DEVELOPMENT_ONLY;
                case INVALID -> INVALID;
            };
        }

        /** True when the report may not be acted on as Gate evidence. */
        public boolean isDiagnosticOnly() {
            return this != GATE_ELIGIBLE;
        }
    }

    private final String reportSchemaVersion;
    private final String reportId;
    private final ReportEvidenceClass evidenceClass;
    private final EvaluationRunContract contract;
    private final List<CaseOutcome> cases;
    private final List<MetricValue> metrics;
    private final Map<String, List<MetricValue>> perCategory;
    private final Map<String, List<MetricValue>> perRisk;
    private final Summary summary;

    private EvaluationReport(
            String reportSchemaVersion,
            String reportId,
            ReportEvidenceClass evidenceClass,
            EvaluationRunContract contract,
            List<CaseOutcome> cases,
            List<MetricValue> metrics,
            Map<String, List<MetricValue>> perCategory,
            Map<String, List<MetricValue>> perRisk,
            Summary summary) {
        this.reportSchemaVersion = reportSchemaVersion;
        this.reportId = reportId;
        this.evidenceClass = evidenceClass;
        this.contract = contract;
        this.cases = List.copyOf(new ArrayList<>(cases));
        this.metrics = List.copyOf(new ArrayList<>(metrics));
        this.perCategory = freeze(perCategory);
        this.perRisk = freeze(perRisk);
        this.summary = summary;
    }

    /** Builds one report. The evidence class is derived from the frozen review classification, never chosen. */
    public static EvaluationReport of(
            String reportId,
            EvaluationRunContract contract,
            boolean runInvalid,
            List<CaseOutcome> cases,
            List<MetricValue> metrics,
            Map<String, List<MetricValue>> perCategory,
            Map<String, List<MetricValue>> perRisk,
            Summary summary) {
        if (contract == null || summary == null || reportId == null) {
            throw EvaluationRunException.of(
                    EvaluationRunErrorCodes.PIN_MISSING, "A report requires its contract, identity and summary");
        }
        ReportEvidenceClass evidenceClass = runInvalid
                ? ReportEvidenceClass.INVALID
                : ReportEvidenceClass.of(contract.review().classification());
        return new EvaluationReport(
                T13Versions.REPORT_SCHEMA_VERSION, reportId, evidenceClass, contract, cases, metrics,
                perCategory, perRisk, summary);
    }

    /**
     * Freezes a breakdown map while preserving its iteration order.
     *
     * <p>{@code Map.copyOf} would leave the order unspecified; the canonical serialization sorts object keys so
     * the report bytes are stable either way, but a reader walking the report in memory must also see the same
     * deterministic order on every run.
     */
    private static Map<String, List<MetricValue>> freeze(Map<String, List<MetricValue>> source) {
        Map<String, List<MetricValue>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<MetricValue>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return java.util.Collections.unmodifiableMap(copy);
    }

    public String reportSchemaVersion() {
        return reportSchemaVersion;
    }

    public String reportId() {
        return reportId;
    }

    public ReportEvidenceClass evidenceClass() {
        return evidenceClass;
    }

    public EvaluationRunContract contract() {
        return contract;
    }

    public List<CaseOutcome> cases() {
        return cases;
    }

    public List<MetricValue> metrics() {
        return metrics;
    }

    public Map<String, List<MetricValue>> perCategory() {
        return perCategory;
    }

    public Map<String, List<MetricValue>> perRisk() {
        return perRisk;
    }

    public Summary summary() {
        return summary;
    }

    /** The exact bytes the report hash covers: the report without its own hash component. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(new Material(
                reportSchemaVersion, reportId, evidenceClass.name(), contract, cases, metrics, perCategory,
                perRisk, summary));
    }

    /** The canonical report hash; identical frozen inputs and outcomes produce byte-identical bytes. */
    public ContentHash contentHash() {
        return ContentHash.sha256(canonicalBytes());
    }

    /** The self-excluded hash material: every component except the report hash itself. */
    private record Material(
            String reportSchemaVersion,
            String reportId,
            String evidenceClass,
            EvaluationRunContract contract,
            List<CaseOutcome> cases,
            List<MetricValue> metrics,
            Map<String, List<MetricValue>> perCategory,
            Map<String, List<MetricValue>> perRisk,
            Summary summary
    ) {
    }

    /** The exhaustive accounting of one Case. No Case may be absent from this list. */
    public record CaseOutcome(
            String caseId,
            String category,
            String riskLevel,
            int requiredAttempts,
            List<AttemptOutcome> attempts,
            int notRunAttempts,
            List<Integer> notRunAttemptNumbers,
            String notRunCode
    ) {

        public CaseOutcome {
            attempts = List.copyOf(new ArrayList<>(attempts));
            notRunAttemptNumbers = List.copyOf(new ArrayList<>(notRunAttemptNumbers));
            if (requiredAttempts != attempts.size() + notRunAttempts) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "A Case must account for every required attempt: " + caseId);
            }
            if (notRunAttemptNumbers.size() != notRunAttempts) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "A Case must name every attempt it did not run: " + caseId);
            }
            if (notRunAttempts > 0 && notRunCode == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "A Case with an unrun attempt requires its bounded not-run code: " + caseId);
            }
        }
    }

    /**
     * The bounded evidence and classification of one attempt.
     *
     * <p>{@code taxonomy} is absent only when the attempt is not a failure at all: a resolved attempt whose
     * observed evidence matches the frozen expectation.
     */
    public record AttemptOutcome(
            int attemptNumber,
            String replayId,
            String disposition,
            String failureCode,
            FailureTaxonomy taxonomy,
            List<String> observedStages,
            int providerCalls,
            boolean repairAttempted,
            boolean repairSucceeded,
            ComparisonEvidence evidence
    ) {

        public AttemptOutcome {
            if (attemptNumber < 1 || replayId == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.RUN_IDENTIFIER_INVALID,
                        "An attempt outcome requires its deterministic number and replay identifier");
            }
            observedStages = List.copyOf(new ArrayList<>(observedStages));
        }

        /** True when the first pass produced a strictly accepted Intent. */
        public boolean firstPassAccepted() {
            return providerCalls >= 1 && observedStages.contains("INTENT") && !repairAttempted;
        }
    }

    /**
     * The bounded, typed comparison evidence of one attempt.
     *
     * <p>Every field is a boolean, a bounded code sequence, a safe hash or a count. Exact member identifiers
     * never appear: a member-set comparison is represented by the expected and actual set hashes, their counts
     * and the symmetric-difference count.
     */
    public record ComparisonEvidence(
            String comparisonVersion,
            boolean refinementAttempt,
            String expectedEvidenceCode,
            boolean dispositionMatched,
            String expectedDisposition,
            String actualDisposition,
            boolean intentSlotsMatched,
            int expectedIntentSlotCount,
            int actualIntentSlotCount,
            boolean intentSourceEvidenceMatched,
            boolean selectedCandidatesMatched,
            boolean rejectedCandidatesMatched,
            boolean canonicalAstMatched,
            ContentHash expectedCanonicalAstHash,
            ContentHash actualCanonicalAstHash,
            boolean validationIssuesMatched,
            List<String> expectedValidationIssueCodes,
            List<String> actualValidationIssueCodes,
            boolean invalidExpectedDslMatched,
            ContentHash expectedInvalidDslHash,
            ContentHash actualInvalidDslHash,
            boolean planStructureMatched,
            boolean planApplicable,
            boolean expectedPlanMatched,
            ContentHash expectedPlanHash,
            ContentHash planHash,
            boolean clarificationCodesMatched,
            List<String> expectedClarificationQuestionCodes,
            List<String> actualClarificationQuestionCodes,
            boolean explanationApplicable,
            boolean explanationClaimsMatched,
            List<String> expectedExplanationClaimIds,
            List<String> actualExplanationClaimIds,
            boolean forbiddenAssumptionsMatched,
            boolean refinementMatched,
            String expectedRefinementOperation,
            String actualRefinementOperation,
            boolean refinementTargetMatched,
            boolean refinementScopeMatched,
            boolean fixtureExecutionFailed,
            List<MemberSetComparison> memberSets
    ) {

        public ComparisonEvidence {
            if (!T13Versions.COMPARISON_VERSION.equals(comparisonVersion)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.COMPARISON_INVALID,
                        "Comparison evidence must declare the exact T13 comparison version");
            }
            expectedValidationIssueCodes = List.copyOf(new ArrayList<>(expectedValidationIssueCodes));
            actualValidationIssueCodes = List.copyOf(new ArrayList<>(actualValidationIssueCodes));
            expectedClarificationQuestionCodes = List.copyOf(new ArrayList<>(expectedClarificationQuestionCodes));
            actualClarificationQuestionCodes = List.copyOf(new ArrayList<>(actualClarificationQuestionCodes));
            expectedExplanationClaimIds = List.copyOf(new ArrayList<>(expectedExplanationClaimIds));
            actualExplanationClaimIds = List.copyOf(new ArrayList<>(actualExplanationClaimIds));
            memberSets = List.copyOf(new ArrayList<>(memberSets));
        }

        /**
         * The evidence of an attempt whose pipeline could not be evaluated at all.
         *
         * <p>Every dimension is recorded as unmatched and no member set is claimed, so an unevaluated attempt
         * is never mistaken for a matched one and never contributes a fabricated set comparison.
         */
        public static ComparisonEvidence unevaluated(
                com.company.campaign.contracts.Disposition expected, String actualDisposition) {
            return unevaluated(expected, actualDisposition, false, null);
        }

        /**
         * The evidence of an attempt whose reviewed expectation itself could not be frozen.
         *
         * <p>Every dimension is recorded as unmatched, no member set is claimed, and the bounded
         * {@code expectedEvidenceCode} states exactly which part of the reviewed expected evidence the
         * genuine T07 or T08 stage refused. That is an input/corpus invalidity recorded per attempt, never a
         * silent skip and never a model mismatch.
         */
        public static ComparisonEvidence unevaluated(
                com.company.campaign.contracts.Disposition expected,
                String actualDisposition,
                boolean refinementAttempt,
                String expectedEvidenceCode) {
            return new ComparisonEvidence(
                    T13Versions.COMPARISON_VERSION, refinementAttempt, expectedEvidenceCode, false,
                    expected.name(), actualDisposition, false, 0, 0, false, false, false, false, null, null,
                    false, List.of(), List.of(), false, null, null,
                    false, false, false, null, null, false, List.of(), List.of(),
                    false, false, List.of(), List.of(),
                    false, false, null, null, false, false, false, List.of());
        }

        /**
         * True when any <em>applicable</em> compared dimension disagrees with the frozen expectation.
         *
         * <p>Applicability is explicit and path-aware. A create-only dimension — Intent slots, source evidence,
         * selected/rejected candidates, clarification codes and the grounded explanation claims — is not
         * applicable to a refinement attempt, which performs no Intent resolution. A refinement-only dimension
         * — the accepted operation, target and scope — is not applicable to a create attempt or to a Case with
         * no reviewed refinement placeholder. The expected canonical AST and Query Plan, and the member sets, are
         * applicable only to a {@code RESOLVED} expectation; when the reviewed disposition prohibits them — a
         * clarification, an unsupported request, a denial or a deterministic refusal — the absence of both an
         * expected and an actual AST/Plan is a match rather than a missing comparison. A reviewed
         * {@code INVALID} expectation instead compares its exact issue-code sequence and the frozen editable
         * hash of the invalid DSL it expects, because an invalid document has no canonical AST.
         *
         * <p>The grounded-explanation-claim dimension is applicable only where the accepted execution path can
         * genuinely produce a grounded explanation — the T11 create path observes one only on its terminal
         * success, so a refinement attempt or a non-resolved verdict has no comparable claim sequence and
         * reports {@code explanationApplicable=false} rather than manufacturing a mismatch from absent claims.
         * When it is applicable, a disagreement between the reviewed claim identifiers and the accepted
         * explanation's identifiers is a real mismatch like any other compared dimension: it drives
         * {@link #mismatched()}, the attempt taxonomy and the summary mismatch count.
         *
         * <p>The reviewed corpus and the accepted T11 explanation currently speak different vocabularies: the
         * corpus can only declare claim identifiers in the upper-case machine-code grammar (for example
         * {@code CLAIM_TIER_EQUALS_GOLD}), while {@link com.company.campaign.builder.agent.GroundedExplanation}
         * emits positional {@code claim-N} identifiers. The two sets cannot intersect, and T13 deliberately does
         * not bridge them with a case-specific business mapping. That structural incompatibility is reported
         * honestly as a comparison mismatch on every applicable attempt instead of being suppressed; it is a
         * remaining corpus/T11 contract gap, not a model regression.
         */
        public boolean mismatched() {
            if (expectedEvidenceCode != null) {
                // The reviewed expectation could not be frozen, so nothing can be compared against it. The
                // attempt is classified as input/corpus invalidity instead of a model mismatch.
                return false;
            }
            if (!dispositionMatched || !canonicalAstMatched || !validationIssuesMatched
                    || !invalidExpectedDslMatched || !forbiddenAssumptionsMatched) {
                return true;
            }
            if (planApplicable && (!planStructureMatched || !expectedPlanMatched)) {
                return true;
            }
            if (refinementAttempt) {
                if (expectedRefinementOperation != null && !refinementMatched) {
                    return true;
                }
            } else if (!intentSlotsMatched || !intentSourceEvidenceMatched || !selectedCandidatesMatched
                    || !rejectedCandidatesMatched || !clarificationCodesMatched
                    || (explanationApplicable && !explanationClaimsMatched)) {
                return true;
            }
            if (fixtureExecutionFailed) {
                return true;
            }
            if (planApplicable) {
                for (MemberSetComparison set : memberSets) {
                    if (!set.matched()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * True when the genuine fixture execution refused, so no member set could be compared.
         *
         * <p>A fixture that cannot execute the accepted plan is a genuine infrastructure/execution failure,
         * not a model or pipeline outcome: under T13-R1 it is classified as
         * {@link FailureTaxonomy#FIXTURE_EXECUTION_FAILURE}, which invalidates the whole report. It is
         * deliberately split from a semantic validation rejection, which stays an accounted pipeline outcome
         * and never invalidates an otherwise sound run.
         */
        public boolean fixtureExecutionFailed() {
            return fixtureExecutionFailed;
        }

        /** True when every member set matched exactly. */
        public boolean memberSetsMatched() {
            for (MemberSetComparison set : memberSets) {
                if (!set.matched()) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One fixture's exact member-set comparison, expressed without any member identifier. */
    public record MemberSetComparison(
            String fixtureId,
            ContentHash expectedSetHash,
            ContentHash actualSetHash,
            int expectedCount,
            int actualCount,
            int symmetricDifferenceCount
    ) {

        public MemberSetComparison {
            if (fixtureId == null || expectedSetHash == null || actualSetHash == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.COMPARISON_INVALID, "A member-set comparison requires its hashes");
            }
            if (expectedCount < 0 || actualCount < 0 || symmetricDifferenceCount < 0) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.COMPARISON_INVALID, "A member-set comparison requires counts");
            }
            if (expectedSetHash.equals(actualSetHash) != (symmetricDifferenceCount == 0)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.COMPARISON_INVALID,
                        "A member-set comparison must agree with its own hashes");
            }
        }

        /** True only on exact set equality. */
        public boolean matched() {
            return symmetricDifferenceCount == 0 && expectedSetHash.equals(actualSetHash);
        }
    }

    /**
     * One separately reported metric.
     *
     * <p>An undefined denominator is reported explicitly as undefined with its numerator and denominator,
     * never as zero and never as 100%.
     */
    public record MetricValue(
            String metricId,
            int numerator,
            int denominator,
            int excluded,
            int invalid,
            boolean defined,
            String value
    ) {

        public MetricValue {
            if (metricId == null) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID, "A metric value requires its identifier");
            }
            if (numerator < 0 || denominator < 0 || excluded < 0 || invalid < 0) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID, "A metric count must not be negative");
            }
            if (defined != (denominator > 0)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "A metric is defined exactly when its denominator is positive");
            }
            if (defined != (value != null)) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "An undefined metric must not report a value, and a defined one must");
            }
            if (numerator > denominator) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID, "A metric numerator must not exceed its denominator");
            }
        }
    }

    /** The run-level accounting summary. */
    public record Summary(
            int caseCount,
            int requiredAttemptCount,
            int executedAttemptCount,
            int notRunAttemptCount,
            int resolvedAttemptCount,
            int invalidAttemptCount,
            int mismatchedAttemptCount,
            int failedBeyondExpectationCount,
            boolean runInvalid
    ) {

        public Summary {
            if (caseCount < 0 || requiredAttemptCount < 0 || executedAttemptCount < 0 || notRunAttemptCount < 0) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID, "A summary count must not be negative");
            }
            if (requiredAttemptCount != executedAttemptCount + notRunAttemptCount) {
                throw EvaluationRunException.of(
                        EvaluationRunErrorCodes.METRIC_INVALID,
                        "A summary must account for every required attempt");
            }
        }
    }
}