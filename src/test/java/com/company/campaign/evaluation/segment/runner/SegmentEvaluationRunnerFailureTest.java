package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.SegmentIntentModelPort;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sections 4 and 6: one infrastructure failure is recorded and makes the whole report {@code INVALID}, no Case
 * or attempt is dropped, and the failure taxonomy keeps the invaliding classes apart from the bounded ones.
 */
class SegmentEvaluationRunnerFailureTest {

    private static T13TestCorpus.CaseHolder highRiskCase(String caseId) {
        return T13Scenarios.tierCase(caseId, RiskLevel.HIGH,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS);
    }

    private static void writeTwoCaseCorpus(Path root) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(highRiskCase("case-failure-01"))
                .caseSpec(highRiskCase("case-failure-02"))
                .write();
    }

    @Test
    void aProviderFailureMakesTheReportInvalidWithoutDroppingACase(@TempDir Path root) {
        writeTwoCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-failure-provider",
                new T13TestHost(SliceTestFixtures.StubModelPort.throwing(),
                        SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        assertThat(report.cases()).hasSize(2);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(10);
        assertThat(report.summary().executedAttemptCount()).isEqualTo(1);
        assertThat(report.summary().notRunAttemptCount()).isEqualTo(9);

        EvaluationReport.CaseOutcome first = report.cases().get(0);
        assertThat(first.attempts()).hasSize(1);
        assertThat(first.attempts().get(0).disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(first.attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE);
        assertThat(first.attempts().get(0).taxonomy().invalidatesRun()).isTrue();
        assertThat(first.notRunAttempts()).isEqualTo(4);
        assertThat(first.notRunCode()).isEqualTo(SegmentEvaluationRunner.NOT_RUN_DUE_TO_RUN_INVALID);

        EvaluationReport.CaseOutcome second = report.cases().get(1);
        assertThat(second.caseId()).isEqualTo("case-failure-02");
        assertThat(second.attempts()).isEmpty();
        assertThat(second.notRunAttempts()).isEqualTo(5);
        assertThat(second.notRunCode()).isEqualTo(SegmentEvaluationRunner.NOT_RUN_DUE_TO_RUN_INVALID);
        assertThat(second.requiredAttempts()).isEqualTo(second.notRunAttempts());
    }

    @Test
    void anObservationBoundaryFailureFailsTheRunClosed(@TempDir Path root) {
        writeTwoCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(
                root, "t13-failure-observer",
                new T13TestHost(T13Scenarios.tierPort()).failingObservation());

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        EvaluationReport.AttemptOutcome attempt = report.cases().get(0).attempts().get(0);
        assertThat(attempt.disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(attempt.failureCode()).isEqualTo("SEGMENT_AGENT_OBSERVATION_FAILED");
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE);
        assertThat(attempt.observedStages()).isEmpty();
        assertThat(report.cases()).hasSize(2);
    }

    @Test
    void theFailureTaxonomySeparatesEveryRequiredClass() {
        assertThat(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY.invalidatesRun()).isTrue();
        assertThat(FailureTaxonomy.REVIEW_CLASSIFICATION_INVALIDITY.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE.invalidatesRun()).isTrue();
        assertThat(FailureTaxonomy.FIRST_PASS_SCHEMA_REJECTION.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.REPAIR_SCHEMA_REJECTION.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.MODEL_SEMANTIC_MISMATCH.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.TOOL_ADMISSION_OR_RESOLUTION_FAILURE.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.VALIDATION_PLANNING_OR_FIXTURE_FAILURE.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.COMPARISON_MISMATCH.invalidatesRun()).isFalse();
        assertThat(FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE.invalidatesRun()).isTrue();

        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_PROVIDER_FAILED"))
                .isEqualTo(FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE);
        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED"))
                .isEqualTo(FailureTaxonomy.FIRST_PASS_SCHEMA_REJECTION);
        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_RESOLUTION_REJECTED"))
                .isEqualTo(FailureTaxonomy.TOOL_ADMISSION_OR_RESOLUTION_FAILURE);
        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_VALIDATION_REJECTED"))
                .isEqualTo(FailureTaxonomy.VALIDATION_PLANNING_OR_FIXTURE_FAILURE);
        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_METADATA_PIN_MISMATCH"))
                .isEqualTo(FailureTaxonomy.INPUT_OR_PIN_INVALIDITY);
        assertThat(FailureTaxonomy.classify("SEGMENT_AGENT_OBSERVATION_FAILED"))
                .isEqualTo(FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE);
        assertThat(FailureTaxonomy.classify("SEGMENT_REFINEMENT_AUDIT_UNAVAILABLE"))
                .isEqualTo(FailureTaxonomy.OBSERVER_OR_AUDIT_FAILURE);
    }

    @Test
    void aComparisonMismatchIsNotAnInfrastructureFailure(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                        "case-failure-03", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS)
                .memberIds(T13TestHost.TIER_FIXTURE, "m-002"));

        EvaluationReport report = T13Scenarios.run(root, "t13-failure-mismatch", T13Scenarios.tierPort());

        // The run itself is sound; the synthetic corpus is simply not candidate-complete, so its frozen
        // classification keeps the report diagnostic (the full-corpus test observes DEVELOPMENT_ONLY).
        assertThat(report.summary().runInvalid()).isFalse();
        assertThat(report.evidenceClass()).isEqualTo(EvaluationReport.ReportEvidenceClass.INVALID);
        assertThat(report.contract().review().classification().name()).isEqualTo("INVALID");
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.COMPARISON_MISMATCH);
        assertThat(report.summary().mismatchedAttemptCount()).isEqualTo(1);
    }

    @Test
    void anAuthorizationDenialIsNotAutomaticallyRepairedAndIsNotInfrastructure(
            @TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-failure-04", RiskLevel.MEDIUM,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        SegmentIntentModelPort throwingPort = SliceTestFixtures.StubModelPort.throwing();
        EvaluationReport report = T13Scenarios.run(root, "t13-failure-denial",
                new T13TestHost(throwingPort, SegmentSchemaRepairPolicy.DISABLED));

        // The provider throws before any model output exists, so the attempt is infrastructure and invalid.
        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.cases()).hasSize(1);
        assertThat(report.cases().get(0).attempts()).hasSize(1);
    }
}