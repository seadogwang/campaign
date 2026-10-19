package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 3 of T13-R1: the actual Query Plan is compared against the exact expected Plan the freeze
 * produced from the reviewed expected evidence, separately from the actual Plan/Preview/DSL pin coherence.
 */
class SegmentEvaluationRunnerExpectedPlanTest {

    private static final String ZERO_HASH = "sha256:" + "0".repeat(64);

    @Test
    void aMatchingResolvedCaseComparesTheExpectedPlanExactly(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                        "case-plan-01", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                .rejected("member.code"));

        EvaluationReport report = T13Scenarios.run(root, "t13-plan-01", T13Scenarios.tierPort());
        EvaluationReport.ComparisonEvidence evidence = report.cases().get(0).attempts().get(0).evidence();

        assertThat(evidence.planApplicable()).isTrue();
        assertThat(evidence.expectedPlanHash()).isNotNull();
        assertThat(evidence.expectedPlanMatched()).isTrue();
        assertThat(evidence.planStructureMatched()).isTrue();
        assertThat(evidence.planHash()).isEqualTo(evidence.expectedPlanHash());
        // The expected Plan comparison is what this test proves and it matches exactly. The only mismatch is
        // the separate, applicable grounded-explanation dimension: the reviewed corpus claim code cannot equal
        // the accepted positional claim-N identifier, which T13-R3 reports honestly rather than suppressing.
        assertThat(evidence.explanationApplicable()).isTrue();
        assertThat(evidence.explanationClaimsMatched()).isFalse();
        assertThat(evidence.mismatched()).isTrue();
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
    }

    @Test
    void aStaleExpectedPlanHashFailsThePlanComparisonWhileTheAstStillMatches(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-plan-02", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13TestHost host = new T13TestHost(T13Scenarios.tierPort());
        SegmentEvaluationRunner runner = new SegmentEvaluationRunner(root);

        SegmentEvaluationRunner.FrozenRun frozen =
                runner.freeze(T13TestAssets.request("t13-plan-02"), host);
        assertThat(frozen.expectedPlanHashes()).containsKey("case-plan-02");

        // A deliberately stale expected Plan pin, exactly as a moved Metadata or pinned-plan revision would be.
        Map<String, ContentHash> stale = new LinkedHashMap<>(frozen.expectedPlanHashes());
        stale.put("case-plan-02", new ContentHash(ZERO_HASH));
        SegmentEvaluationRunner.FrozenRun tampered = new SegmentEvaluationRunner.FrozenRun(
                frozen.contract(), frozen.bundle(), frozen.snapshots(), frozen.datasets(),
                frozen.expectedAstHashes(), stale, frozen.expectedInvalidDslHashes(),
                frozen.expectedEvidenceCodes(), frozen.host());

        EvaluationReport report = runner.execute(tampered);
        EvaluationReport.ComparisonEvidence evidence = report.cases().get(0).attempts().get(0).evidence();

        // The canonical AST still matches exactly; only the expected Plan pin is wrong.
        assertThat(evidence.canonicalAstMatched()).isTrue();
        assertThat(evidence.planStructureMatched()).isTrue();
        assertThat(evidence.expectedPlanMatched()).isFalse();
        assertThat(evidence.expectedPlanHash()).isEqualTo(new ContentHash(ZERO_HASH));
        assertThat(evidence.mismatched()).isTrue();
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().runInvalid()).isFalse();
    }

    @Test
    void aReviewedExpectationTheGenuineValidatorRefusesIsRecordedAsCorpusInvalidity(@TempDir Path root) {
        // A metric nested inside an EXISTS is refused by the accepted T07 validator, so this reviewed
        // expectation cannot be canonicalized or planned and must be recorded as corpus invalidity.
        SegmentDsl unsupportedExpectation = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                MetadataBinding.fromSnapshot(T13TestHost.snapshot()),
                new GroupPredicate("n-root", PredicateType.ALL, List.of(
                        new AttributePredicate(
                                "n-tier", "member.tier", ComparisonOperator.EQUAL,
                                ConditionValue.of(new EnumValue("GOLD"))),
                        new ExistsPredicate(
                                "n-exists", "member.orders", new RollingWindow(30, WindowUnit.DAY),
                                new MetricPredicate(
                                        "n-count", "order.count", new RollingWindow(30, WindowUnit.DAY),
                                        List.of(), ComparisonOperator.GREATER_THAN,
                                        ConditionValue.of(new IntegerValue(0)))))));

        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-plan-03", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .ast(unsupportedExpectation)
                .memberIds(T13TestHost.TIER_FIXTURE);
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(root, "t13-plan-03", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.evidence().expectedEvidenceCode())
                .isEqualTo(SegmentEvaluationRunner.EXPECTED_EVIDENCE_AST_INVALID);
        assertThat(attempt.evidence().mismatched()).isFalse();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);
        // Unsound input evidence is not a model answer: the run is INVALID and every metric counts the one
        // executed attempt as invalid rather than as an ordinary exclusion or failure.
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        assertThat(report.summary().executedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().notRunAttemptCount()).isZero();
        assertThat(report.metrics()).allSatisfy(metric -> assertThat(metric.invalid()).isEqualTo(1));
    }

    @Test
    void aResolvedExpectationWithNoReviewedAstMakesTheReportInvalid(@TempDir Path root) {
        // A resolved expectation claims a canonical AST; with none, the reviewed evidence is missing, which is
        // input/pin invalidity rather than a silent skip or a model mismatch.
        T13TestCorpus.CaseHolder holder = new T13TestCorpus.CaseHolder(
                "case-plan-04", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.MEDIUM,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        holder.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .memberIds(T13TestHost.TIER_FIXTURE);
        T13Scenarios.corpusWith(root, holder);

        EvaluationReport report = T13Scenarios.run(root, "t13-plan-04", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.evidence().expectedEvidenceCode())
                .isEqualTo(SegmentEvaluationRunner.EXPECTED_EVIDENCE_AST_MISSING);
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
    }

    @Test
    void anExpectedEvidenceFailureStopsLaterAttemptsDeterministically(@TempDir Path root) {
        T13TestCorpus.CaseHolder invalid = new T13TestCorpus.CaseHolder(
                "case-plan-05", SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS, RiskLevel.HIGH,
                T13Scenarios.TIER_UTTERANCE, T13TestHost.SNAPSHOT_ID, T13TestHost.CONTEXT_FIXTURE);
        // A deliberately resolved expectation whose reviewed DSL the genuine T07 refuses.
        invalid.fixture(T13TestHost.TIER_FIXTURE)
                .disposition(Disposition.RESOLVED)
                .ast(SegmentDsl.publish(
                        SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER,
                        MetadataBinding.fromSnapshot(T13TestHost.snapshot()),
                        new ExistsPredicate(
                                "n-exists", "member.orders", new RollingWindow(30, WindowUnit.DAY),
                                new MetricPredicate(
                                        "n-count", "order.count", new RollingWindow(30, WindowUnit.DAY),
                                        List.of(), ComparisonOperator.GREATER_THAN,
                                        ConditionValue.of(new IntegerValue(0))))))
                .memberIds(T13TestHost.TIER_FIXTURE);
        T13Scenarios.corpusWith(root, invalid);

        EvaluationReport report = T13Scenarios.run(root, "t13-plan-05", T13Scenarios.tierPort());

        EvaluationReport.CaseOutcome outcome = report.cases().get(0);
        assertThat(outcome.attempts()).hasSize(1);
        assertThat(outcome.notRunAttempts()).isEqualTo(4);
        assertThat(outcome.notRunAttemptNumbers()).containsExactly(2, 3, 4, 5);
        assertThat(outcome.notRunCode()).isEqualTo(SegmentEvaluationRunner.NOT_RUN_DUE_TO_RUN_INVALID);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(5);
        assertThat(report.summary().executedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().notRunAttemptCount()).isEqualTo(4);
    }
}