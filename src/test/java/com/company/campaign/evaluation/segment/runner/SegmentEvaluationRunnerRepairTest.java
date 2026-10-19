package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.builder.segment.SegmentModelCallKind;
import com.company.campaign.builder.segment.SegmentSchemaRepairPolicy;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3: one first-pass call, at most one bounded schema-only repair, and neither ever triggered by a
 * semantic, clarification, unsupported, denial, resolution, validation or fixture outcome. First-pass Success
 * and One-repair Success are reported independently, so a repaired success never inflates the first pass.
 */
class SegmentEvaluationRunnerRepairTest {

    private static EvaluationReport.MetricValue metric(EvaluationReport report, MetricId id) {
        return report.metrics().stream()
                .filter(value -> value.metricId().equals(id.metricCode()))
                .findFirst()
                .orElseThrow();
    }

    private static EvaluationReport.AttemptOutcome onlyAttempt(EvaluationReport report) {
        return report.cases().get(0).attempts().get(0);
    }

    @Test
    void aFirstPassSuccessIsCountedWithoutAnyRepair(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-repair-01", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(root, "t13-repair-01", T13Scenarios.tierPort());

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        assertThat(attempt.providerCalls()).isEqualTo(1);
        assertThat(attempt.repairAttempted()).isFalse();
        assertThat(attempt.firstPassAccepted()).isTrue();
        assertThat(metric(report, MetricId.FIRST_PASS_SUCCESS).numerator()).isEqualTo(1);
        assertThat(metric(report, MetricId.FIRST_PASS_SUCCESS).denominator()).isEqualTo(1);
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).defined()).isFalse();
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).value()).isNull();
    }

    @Test
    void oneSchemaOnlyRepairSucceedsAndDoesNotInflateTheFirstPass(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-repair-02", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(
                root, "t13-repair-02", new T13TestHost(
                        T13Scenarios.repairOncePort(), SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.RESOLVED.name());
        assertThat(attempt.providerCalls()).isEqualTo(2);
        assertThat(attempt.repairAttempted()).isTrue();
        assertThat(attempt.repairSucceeded()).isTrue();
        assertThat(attempt.firstPassAccepted()).isFalse();
        assertThat(metric(report, MetricId.FIRST_PASS_SUCCESS).numerator()).isZero();
        assertThat(metric(report, MetricId.FIRST_PASS_SUCCESS).denominator()).isEqualTo(1);
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).numerator()).isEqualTo(1);
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).denominator()).isEqualTo(1);
        assertThat(report.summary().runInvalid()).isFalse();
    }

    @Test
    void aFailedRepairIsAClassifiedAttemptAndNotAnInvalidRun(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-repair-03", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(
                root, "t13-repair-03", new T13TestHost(
                        T13Scenarios.alwaysProsePort(), SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.INVALID.name());
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.REPAIR_SCHEMA_REJECTION);
        assertThat(attempt.taxonomy().invalidatesRun()).isFalse();
        assertThat(report.summary().runInvalid()).isFalse();
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).numerator()).isZero();
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).denominator()).isEqualTo(1);
    }

    @Test
    void aDisabledPolicyNeverRepairs(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-repair-04", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(
                root, "t13-repair-04", new T13TestHost(
                        T13Scenarios.alwaysProsePort(), SegmentSchemaRepairPolicy.DISABLED));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.providerCalls()).isEqualTo(1);
        assertThat(attempt.repairAttempted()).isFalse();
        assertThat(attempt.taxonomy()).isEqualTo(FailureTaxonomy.FIRST_PASS_SCHEMA_REJECTION);
        assertThat(metric(report, MetricId.ONE_REPAIR_SUCCESS).defined()).isFalse();
    }

    @Test
    void theRepairRequestCarriesOnlyTheFrozenSafeContextAndBoundedIssueCodes(@TempDir Path givenRoot) {
        T13Scenarios.corpusWith(givenRoot, T13Scenarios.tierCase(
                "case-repair-05", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13Scenarios.RecordingPort recording =
                new T13Scenarios.RecordingPort(T13Scenarios.repairOncePort());

        T13Scenarios.run(givenRoot, "t13-repair-05",
                new T13TestHost(recording, SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        assertThat(recording.requests()).hasSize(2);
        var first = recording.requests().get(0).callContext();
        var repair = recording.requests().get(1).callContext();
        assertThat(first.kind()).isEqualTo(SegmentModelCallKind.FIRST_PASS);
        assertThat(first.schemaIssueCodes()).isEmpty();
        assertThat(repair.kind()).isEqualTo(SegmentModelCallKind.SCHEMA_REPAIR);
        assertThat(repair.schemaIssueCodes()).isNotEmpty();
        assertThat(repair.schemaIssueCodes()).allSatisfy(code ->
                assertThat(code).matches("^[A-Z][A-Z0-9_]{0,63}$"));
        // The frozen safe context is byte-identical: only the call kind and the issue codes differ.
        assertThat(recording.requests().get(1).permittedDefinitions())
                .isEqualTo(recording.requests().get(0).permittedDefinitions());
        assertThat(recording.requests().get(1).metadataSnapshot())
                .isEqualTo(recording.requests().get(0).metadataSnapshot());
        assertThat(recording.requests().get(1).instructions())
                .isEqualTo(recording.requests().get(0).instructions());
    }

    @Test
    void aSemanticUnsupportedOutcomeNeverRepairs(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-repair-06", RiskLevel.MEDIUM, SegmentCategory.UNSUPPORTED_CONCEPTS_AND_RAW_SQL));

        T13Scenarios.CountingDelegate port = new T13Scenarios.CountingDelegate(T13Scenarios.unsupportedPort());
        EvaluationReport report = T13Scenarios.run(
                root, "t13-repair-06", new T13TestHost(port, SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.disposition()).isEqualTo(Disposition.UNSUPPORTED.name());
        assertThat(attempt.repairAttempted()).isFalse();
        assertThat(port.calls()).isEqualTo(1);
        assertThat(attempt.observedStages()).contains("INTENT").doesNotContain("RESOLUTION");
    }

    @Test
    void aClarificationOutcomeNeverRepairs(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.clarificationCase("case-repair-07", RiskLevel.MEDIUM));

        T13Scenarios.CountingDelegate port = new T13Scenarios.CountingDelegate(
                com.company.campaign.builder.segment.testsupport.SliceTestFixtures.StubModelPort.returning(
                        com.company.campaign.builder.segment.testsupport.SliceTestFixtures.singleClauseJson(
                                T13Scenarios.TIER_UTTERANCE, "MEMBER_UNKNOWN", "ATTRIBUTE",
                                "\"operator\":\"EQUAL\",", "\"value\":{\"kind\":\"STRING\",\"raw\":\"x\"},",
                                T13Scenarios.TIER_UTTERANCE, 0)));
        EvaluationReport report = T13Scenarios.run(
                root, "t13-repair-07", new T13TestHost(port, SegmentSchemaRepairPolicy.ONE_SCHEMA_REPAIR));

        EvaluationReport.AttemptOutcome attempt = onlyAttempt(report);
        assertThat(attempt.repairAttempted()).isFalse();
        assertThat(port.calls()).isEqualTo(1);
        assertThat(attempt.taxonomy() == null
                || attempt.taxonomy() != FailureTaxonomy.FIRST_PASS_SCHEMA_REJECTION).isTrue();
        assertThat(List.of(Disposition.UNSUPPORTED.name(), Disposition.NEEDS_INPUT.name()))
                .contains(attempt.disposition());
    }
}