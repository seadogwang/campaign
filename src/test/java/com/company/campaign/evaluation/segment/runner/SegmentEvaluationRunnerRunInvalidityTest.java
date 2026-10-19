package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.query.fixture.FixtureMapping;
import com.company.campaign.query.fixture.FixtureScopeBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 5 of T13-R1: a run-invalidating attempt increments the {@code invalid} count of every metric, is
 * never silently dropped from a denominator and never counted as an ordinary failure, while every required
 * attempt of every Case stays accounted for.
 */
class SegmentEvaluationRunnerRunInvalidityTest {

    private static T13TestCorpus.CaseHolder highRiskCase(String caseId) {
        return T13Scenarios.tierCase(caseId, RiskLevel.HIGH,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS);
    }

    private static void writeTwoHighRiskCaseCorpus(Path root) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(highRiskCase("case-invalid-01"))
                .caseSpec(highRiskCase("case-invalid-02"))
                .write();
    }

    private static void assertCompleteAccounting(EvaluationReport report) {
        assertThat(report.summary().requiredAttemptCount())
                .isEqualTo(report.summary().executedAttemptCount() + report.summary().notRunAttemptCount());
        assertThat(report.cases()).allSatisfy(outcome -> {
            assertThat(outcome.requiredAttempts())
                    .isEqualTo(outcome.attempts().size() + outcome.notRunAttempts());
            assertThat(outcome.attempts().size() + outcome.notRunAttempts())
                    .isEqualTo(outcome.riskLevel().equals("HIGH") ? 5 : 1);
            if (outcome.notRunAttempts() > 0) {
                assertThat(outcome.notRunCode()).isEqualTo(SegmentEvaluationRunner.NOT_RUN_DUE_TO_RUN_INVALID);
            }
        });
        assertThat(report.summary().executedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().notRunAttemptCount()).isEqualTo(9);
    }

    private static void assertEveryMetricCountsTheInvalidAttempt(EvaluationReport report, int invalidAttempts) {
        assertThat(report.metrics()).allSatisfy(metric -> {
            assertThat(metric.invalid()).as(metric.metricId()).isEqualTo(invalidAttempts);
            if (metric.denominator() > 0) {
                assertThat(metric.numerator()).isLessThanOrEqualTo(metric.denominator());
            }
        });
        report.perRisk().values().forEach(values -> assertThat(values)
                .allSatisfy(metric -> assertThat(metric.invalid()).isEqualTo(invalidAttempts)));
        report.perCategory().values().forEach(values -> assertThat(values)
                .allSatisfy(metric -> assertThat(metric.invalid()).isEqualTo(invalidAttempts)));
    }

    @Test
    void aProviderFailureIsCountedAsInvalidInEveryMetric(@TempDir Path root) {
        writeTwoHighRiskCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-invalid-provider",
                new T13TestHost(SliceTestFixtures.StubModelPort.throwing(),
                        SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        assertCompleteAccounting(report);
        assertEveryMetricCountsTheInvalidAttempt(report, 1);
        // A run-invalidating attempt is not counted as a first-pass success or an ordinary failure.
        assertThat(report.metrics().stream()
                .filter(metric -> metric.metricId().equals(MetricId.FIRST_PASS_SUCCESS.metricCode()))
                .findFirst().orElseThrow().denominator()).isZero();
    }

    @Test
    void anObserverFailureIsCountedAsInvalidInEveryMetric(@TempDir Path root) {
        writeTwoHighRiskCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-invalid-observer",
                new T13TestHost(T13Scenarios.tierPort()).failingObservation());

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE);
        assertCompleteAccounting(report);
        assertEveryMetricCountsTheInvalidAttempt(report, 1);
    }

    @Test
    void aChangedFixturePinIsCountedAsInvalidInEveryMetric(@TempDir Path root) {
        writeTwoHighRiskCaseCorpus(root);

        T13TestHost host = new T13TestHost(T13Scenarios.tierPort()) {
            private int calls;

            @Override
            public FixtureExecutionServices fixtureServices(String fixtureId) {
                calls++;
                if (calls <= 1) {
                    return super.fixtureServices(fixtureId);
                }
                return new FixtureExecutionServices(
                        FixtureMapping.empty("t13-fixture-mapping-2"),
                        FixtureScopeBinding.publish("t13-fixture-scope-2", List.of()),
                        super.fixtureServices(fixtureId).fxRateSnapshot());
            }
        };

        EvaluationReport report = T13Scenarios.run(root, "t13-invalid-pin-change", host);

        assertThat(report.summary().runInvalid()).isTrue();
        assertCompleteAccounting(report);
        assertEveryMetricCountsTheInvalidAttempt(report, 1);
    }

    @Test
    void aGenuineFixtureExecutionFailureInvalidatesTheRun(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-invalid-fixture", RiskLevel.MEDIUM,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        T13TestHost host = new T13TestHost(T13Scenarios.tierPort());
        FixtureExecutionServices unchanged = host.fixtureServices(T13TestHost.TIER_FIXTURE);
        host.services(T13TestHost.TIER_FIXTURE, new FixtureExecutionServices(
                FixtureMapping.empty("t13-fixture-mapping-empty"),
                unchanged.scopeBinding(), unchanged.fxRateSnapshot()));

        EvaluationReport report = T13Scenarios.run(root, "t13-invalid-fixture", host);

        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.evidence().fixtureExecutionFailed()).isTrue();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.FIXTURE_EXECUTION_FAILURE);
        assertThat(attempt.taxonomy().invalidatesRun()).isTrue();
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        // A fixture execution failure alone is a mismatch as well as a run invalidity, never a silent skip.
        assertThat(attempt.evidence().mismatched()).isTrue();
        assertEveryMetricCountsTheInvalidAttempt(report, 1);
    }

    @Test
    void aSemanticValidationRejectionDoesNotInvalidateTheRun(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-invalid-semantic", RiskLevel.MEDIUM,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(
                root, "t13-invalid-semantic",
                new T13TestHost(T13Scenarios.alwaysProsePort(), SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        assertThat(report.summary().runInvalid()).isFalse();
        assertThat(report.cases().get(0).attempts().get(0).disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(report.cases().get(0).attempts().get(0).taxonomy().invalidatesRun()).isFalse();
        assertThat(report.summary().notRunAttemptCount()).isZero();
    }
}