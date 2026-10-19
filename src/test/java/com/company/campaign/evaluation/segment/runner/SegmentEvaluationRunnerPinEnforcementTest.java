package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Requirement 6 of T13-R1: high-risk Cases always run exactly five times, duplicates are refused, and the
 * host trusted context is bound at freeze time to the exact reviewed execution-context pins.
 */
class SegmentEvaluationRunnerPinEnforcementTest {

    private static ContentHash hash(String tag) {
        return ContentHash.sha256(tag.getBytes(StandardCharsets.UTF_8));
    }

    private static EvaluationCasePins.FixturePin fixturePin(String fixtureId) {
        return new EvaluationCasePins.FixturePin(
                fixtureId, "1", hash("payload"), "mapping-1", hash("mapping"),
                "scope-1", hash("scope"), "fx-1", hash("fx"));
    }

    private static EvaluationCasePins casePins(String timezone, List<EvaluationCasePins.FixturePin> fixtures) {
        return new EvaluationCasePins(
                "case-pins-01", "CODE", "MEDIUM", 1, false, hash("case"), hash("expected"), hash("review"),
                "metadata-snapshot", "1", hash("metadata"), "fixture-context", hash("context"), "zh-CN",
                Instant.parse("2026-10-01T02:00:00Z"), timezone, Instant.parse("2026-10-01T01:55:00Z"), fixtures);
    }

    private static void writeCorpus(Path root, int repeats, T13TestCorpus.CaseHolder... cases) {
        T13TestCorpus corpus = T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .requiredHighRiskRepeats(repeats);
        for (T13TestCorpus.CaseHolder holder : cases) {
            corpus.caseSpec(holder);
        }
        corpus.write();
    }

    @Test
    void aHighRiskCaseRunsFiveTimesWhenTheManifestAsksForOne(@TempDir Path root) {
        writeCorpus(root, 1, T13Scenarios.tierCase(
                "case-pins-repeats-1", RiskLevel.HIGH,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(root, "t13-pins-repeats-1", T13Scenarios.tierPort());

        assertThat(report.cases().get(0).attempts()).hasSize(5);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(5);
        assertThat(report.summary().notRunAttemptCount()).isZero();
    }

    @Test
    void aHighRiskCaseRunsFiveTimesWhenTheManifestAsksForSeven(@TempDir Path root) {
        writeCorpus(root, 7,
                T13Scenarios.tierCase("case-pins-repeats-7-high", RiskLevel.HIGH,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS),
                T13Scenarios.tierCase("case-pins-repeats-7-medium", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        EvaluationReport report = T13Scenarios.run(root, "t13-pins-repeats-7", T13Scenarios.tierPort());

        assertThat(report.cases().get(0).attempts()).hasSize(5);
        assertThat(report.cases().get(1).attempts()).hasSize(1);
        assertThat(report.summary().requiredAttemptCount()).isEqualTo(6);
    }

    @Test
    void duplicateCaseFixturePinsAreRefused() {
        assertThatThrownBy(() -> casePins("Asia/Shanghai", List.of(
                fixturePin("fixture-a"), fixturePin("fixture-a"))))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_CONTRADICTORY);
    }

    @Test
    void aCaseTimezoneThatIsNotAValidZoneIdIsRefused() {
        assertThatThrownBy(() -> casePins("Not/AZone", List.of(fixturePin("fixture-a"))))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_MISSING);
    }

    @Test
    void duplicateRequiredArtifactIdsAreRefused() {
        assertThatThrownBy(() -> new EvaluationRunContract.ArtifactPins(List.of(
                EvaluationRunContract.ArtifactPin.present("system-prompt", hash("prompt")),
                EvaluationRunContract.ArtifactPin.present("skill", hash("skill")),
                EvaluationRunContract.ArtifactPin.present("tool-schema", hash("tools")),
                EvaluationRunContract.ArtifactPin.present("skill", hash("skill-again")))))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_CONTRADICTORY);
    }

    @Test
    void duplicateCaseIdsAreRefused(@TempDir Path root) {
        T13TestCorpus.CaseHolder holder = T13Scenarios.tierCase(
                "case-pins-duplicate", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS);
        writeCorpus(root, 5, holder, holder);

        assertThatThrownBy(() -> T13Scenarios.run(root, "t13-pins-duplicate", T13Scenarios.tierPort()))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void aHostTrustedContextSubstitutionIsInputInvalidity(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-pins-context", RiskLevel.MEDIUM,
                SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13TestHost host = new T13TestHost(T13Scenarios.tierPort())
                .context(T13TestHost.CONTEXT_FIXTURE, T13Scenarios.foreignContext());

        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-pins-context"), host))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_STALE);
    }
}