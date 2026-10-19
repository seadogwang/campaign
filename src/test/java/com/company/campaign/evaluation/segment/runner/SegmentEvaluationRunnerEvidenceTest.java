package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sections 1, 4 and 6: the report is byte-identical for identical frozen inputs, its replay identifiers are
 * derived only from frozen pins, its serialization carries no restricted fact, and its evidence class is
 * frozen at run start and can never be relabelled afterwards.
 */
class SegmentEvaluationRunnerEvidenceTest {

    private static Path oneCaseCorpus(Path root) {
        return T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-evidence-01", RiskLevel.HIGH, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
    }

    @Test
    void identicalFrozenInputsProduceByteIdenticalReports(@TempDir Path root) {
        oneCaseCorpus(root);

        EvaluationReport first = T13Scenarios.run(root, "t13-evidence-01", T13Scenarios.tierPort());
        EvaluationReport second = T13Scenarios.run(root, "t13-evidence-01", T13Scenarios.tierPort());

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.canonicalBytes()).isEqualTo(second.canonicalBytes());
        assertThat(first.cases().get(0).attempts()).extracting(EvaluationReport.AttemptOutcome::replayId)
                .isEqualTo(second.cases().get(0).attempts().stream()
                        .map(EvaluationReport.AttemptOutcome::replayId).toList());
    }

    @Test
    void replayIdentifiersAreDeterministicAndCarryNoRestrictedFact(@TempDir Path root) {
        oneCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(root, "t13-evidence-02", T13Scenarios.tierPort());

        List<String> replayIds = new ArrayList<>();
        report.cases().forEach(outcome -> outcome.attempts()
                .forEach(attempt -> replayIds.add(attempt.replayId())));
        assertThat(replayIds).hasSize(5);
        assertThat(replayIds).doesNotHaveDuplicates();
        assertThat(replayIds).allSatisfy(id -> assertThat(id).matches("^replay-[0-9a-f]{64}$"));
    }

    @Test
    void theSerializedReportCarriesNoUtteranceMemberIdentitySqlOrCredential(@TempDir Path root) {
        oneCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(root, "t13-evidence-03", T13Scenarios.tierPort());
        String serialized = new String(report.canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(serialized)
                .doesNotContain(T13Scenarios.TIER_UTTERANCE)
                .doesNotContain("\"m-001\"")
                .doesNotContain("m-001")
                .doesNotContain("tenant-001")
                .doesNotContain("user-001")
                .doesNotContain("scope-008")
                .doesNotContain("membership-1")
                .doesNotContain("SELECT")
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("Authorization")
                .doesNotContain("seg_member")
                .doesNotContain("raw model")
                .doesNotContain("\"Gold\"");
    }

    @Test
    void theReportContractHasNoGateVerdictComponent() {
        List<String> components = new ArrayList<>();
        for (java.lang.reflect.Field field : EvaluationReport.class.getDeclaredFields()) {
            components.add(field.getName());
        }
        assertThat(components)
                .doesNotContain("gateOutcome", "outcome", "passed", "failed", "verdict", "promoted");
        assertThat(EvaluationReport.ReportEvidenceClass.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("GATE_ELIGIBLE", "DEVELOPMENT_ONLY", "INVALID");
    }

    @Test
    void aPublishedEvidenceClassHasNoRelabelPathAndTheReviewPinsArePartOfTheHash(@TempDir Path root) {
        oneCaseCorpus(root);
        EvaluationReport pending = T13Scenarios.run(root, "t13-evidence-04", T13Scenarios.tierPort());
        byte[] publishedBytes = pending.canonicalBytes();
        String publishedClass = pending.evidenceClass().name();

        // No method on the report can change an evidence class: the type is final, the field is final and the
        // only factory derives the class from the frozen review classification or an invalid run.
        assertThat(EvaluationReport.class.getMethods())
                .noneMatch(method -> method.getName().startsWith("with"))
                .noneMatch(method -> method.getName().startsWith("set"));
        assertThat(EvaluationReport.class.getDeclaredFields())
                .allSatisfy(field -> assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).isTrue());

        // The frozen review-readiness pins are inside the report hash, so a relabel would change the hash and
        // therefore would not be the same evidence.
        assertThat(pending.contract().review().reviewSetHash()).isNotNull();
        assertThat(pending.contract().review().classification().name()).isEqualTo("INVALID");
        assertThat(pending.contentHash().value()).matches("^sha256:[0-9a-f]{64}$");

        assertThat(pending.canonicalBytes()).isEqualTo(publishedBytes);
        assertThat(pending.evidenceClass().name()).isEqualTo(publishedClass);
    }

    @Test
    void aDifferentHumanReviewStateIsADifferentFrozenRun(@TempDir Path pendingRoot, @TempDir Path approvedRoot) {
        oneCaseCorpus(pendingRoot);
        approveCorpus(approvedRoot);

        EvaluationReport pending = T13Scenarios.run(pendingRoot, "t13-evidence-06", T13Scenarios.tierPort());
        EvaluationReport approved = T13Scenarios.run(approvedRoot, "t13-evidence-06", T13Scenarios.tierPort());

        assertThat(approved.contract().review().reviewSetHash())
                .isNotEqualTo(pending.contract().review().reviewSetHash());
        assertThat(approved.contentHash()).isNotEqualTo(pending.contentHash());
    }

    /** Writes the same corpus with every ledger approved, exactly as a human owner would. */
    private static void approveCorpus(Path root) {
        com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus.in(root)
                .metadata(com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost.snapshot())
                .context(com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost.CONTEXT_FIXTURE,
                        com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost.context().snapshotRef())
                .fixture(com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost.TIER_FIXTURE,
                        com.company.campaign.evaluation.segment.contract.FixtureRole.PRIMARY,
                        com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets.TIER_UNIVERSE,
                        com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets.membersPayload())
                .reviewStatus(com.company.campaign.evaluation.segment.contract.ReviewStatus.APPROVED)
                .caseSpec(T13Scenarios.tierCase(
                        "case-evidence-01", RiskLevel.HIGH,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();
    }

    @Test
    void theReportNeverClaimsAGateOutcome(@TempDir Path root) {
        oneCaseCorpus(root);

        EvaluationReport report = T13Scenarios.run(root, "t13-evidence-05", T13Scenarios.tierPort());
        String serialized = new String(report.canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(serialized).doesNotContain("PASSED").doesNotContain("FAILED").doesNotContain("gateOutcome");
    }
}