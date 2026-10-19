package com.company.campaign.evaluation.segment.runner;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.FixtureRole;
import com.company.campaign.evaluation.segment.contract.RiskLevel;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.runner.testsupport.T13Scenarios;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestAssets;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestCorpus;
import com.company.campaign.evaluation.segment.runner.testsupport.T13TestHost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 1: the contract is frozen before the first Case starts and missing, stale, contradictory or changing
 * pins are refused rather than silently absorbed.
 */
class SegmentEvaluationRunnerFreezeTest {

    /** The same member dataset under a different, internally consistent time context. */
    private static String counterPayload() {
        return T13TestAssets.membersPayload()
                .replace("fixture-t13-members", "fixture-t13-counter")
                .replace("\"role\":\"PRIMARY\"", "\"role\":\"COUNTEREXAMPLE\"")
                .replace("2026-10-01T02:00:00Z", "2026-09-01T02:00:00Z")
                .replace("2026-10-01T01:55:00Z", "2026-09-01T01:55:00Z")
                .replace("2026-09-30T09:00:00Z", "2026-08-30T09:00:00Z")
                .replace("2026-09-30T10:00:00Z", "2026-08-30T10:00:00Z");
    }

    private static EvaluationRunRequest request(
            String reportId,
            EvaluationRunContract.ModelPin model,
            DecodingParameters decoding,
            String codeRevision,
            String environment) {
        return new EvaluationRunRequest(
                reportId, model, decoding, codeRevision, environment, T13TestCorpus.MANIFEST_REFERENCE);
    }

    @Test
    void identicalRequestsFreezeIdenticalContracts(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-01", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        SegmentEvaluationRunner runner = new SegmentEvaluationRunner(root);
        EvaluationRunContract first = runner.freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(
                T13Scenarios.tierPort())).contract();
        EvaluationRunContract second = runner.freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(
                T13Scenarios.tierPort())).contract();

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.requiredAttemptCount()).isEqualTo(1);
        assertThat(first.artifacts().artifact("system-prompt").isPresent()).isTrue();
        assertThat(first.artifacts().artifact("skill").isPresent()).isTrue();
        assertThat(first.artifacts().artifact("tool-schema").isPresent()).isTrue();
        assertThat(first.time().timezone()).isEqualTo("Asia/Shanghai");
        assertThat(first.review().classification().name()).isEqualTo("INVALID");
        assertThat(first.decoding().contentHash()).isNotNull();
    }

    @Test
    void changingAnyFrozenPinChangesTheContractHash(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-02", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        SegmentEvaluationRunner runner = new SegmentEvaluationRunner(root);
        T13TestHost host = new T13TestHost(T13Scenarios.tierPort());

        String baseline = runner.freeze(T13TestAssets.request("t13-freeze"), host).contract().contentHash().value();

        String otherReport = runner.freeze(
                        request("t13-freeze-other", T13TestAssets.modelPin(),
                                DecodingParameters.deterministicReplay(4096, 20261006L),
                                "code-revision-t13-test", "environment-t13-test"), host)
                .contract().contentHash().value();
        String otherModel = runner.freeze(
                        request("t13-freeze", new EvaluationRunContract.ModelPin(
                                        "other-provider", "other-model", "other-model/2.0"),
                                DecodingParameters.deterministicReplay(4096, 20261006L),
                                "code-revision-t13-test", "environment-t13-test"), host)
                .contract().contentHash().value();
        String otherDecoding = runner.freeze(
                        request("t13-freeze", T13TestAssets.modelPin(),
                                DecodingParameters.deterministicReplay(8192, 7L),
                                "code-revision-t13-test", "environment-t13-test"), host)
                .contract().contentHash().value();
        String otherCode = runner.freeze(
                        request("t13-freeze", T13TestAssets.modelPin(),
                                DecodingParameters.deterministicReplay(4096, 20261006L),
                                "code-revision-other", "environment-t13-test"), host)
                .contract().contentHash().value();
        String otherEnvironment = runner.freeze(
                        request("t13-freeze", T13TestAssets.modelPin(),
                                DecodingParameters.deterministicReplay(4096, 20261006L),
                                "code-revision-t13-test", "environment-other"), host)
                .contract().contentHash().value();

        assertThat(otherReport).isNotEqualTo(baseline);
        assertThat(otherModel).isNotEqualTo(baseline);
        assertThat(otherDecoding).isNotEqualTo(baseline);
        assertThat(otherCode).isNotEqualTo(baseline);
        assertThat(otherEnvironment).isNotEqualTo(baseline);
    }

    @Test
    void changingTheCommittedSkillPinChangesTheContractHash(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-10", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        SegmentEvaluationRunner runner = new SegmentEvaluationRunner(root);
        com.company.campaign.builder.skill.SegmentSkillDefinition committed =
                com.company.campaign.builder.agent.testsupport.AgentTestFixtures.skill();

        EvaluationRunContract baseline = runner.freeze(
                T13TestAssets.request("t13-freeze"),
                new T13TestHost(T13Scenarios.tierPort())).contract();
        EvaluationRunContract otherSkill = runner.freeze(
                T13TestAssets.request("t13-freeze"),
                new T13TestHost(T13Scenarios.tierPort()) {
                    @Override
                    public com.company.campaign.builder.skill.SegmentSkillDefinition skill() {
                        // A Skill published with different bounds is a different Skill with a different
                        // canonical hash, which is exactly what the artifact pin must notice.
                        return com.company.campaign.builder.agent.testsupport.AgentTestFixtures.skillWithLimits(
                                committed.limits().maxSteps(), committed.limits().maxToolCalls() - 1);
                    }
                }).contract();

        // The baseline pins the exact committed Skill and Tool schema; the mutated Skill moves both pins, so
        // the whole frozen contract — and therefore the report hash — is different.
        assertThat(baseline.artifacts().artifact("skill").contentHash()).isEqualTo(committed.canonicalHash());
        assertThat(baseline.artifacts().artifact("system-prompt").contentHash())
                .isEqualTo(com.company.campaign.builder.segment.SegmentPromptContract.HASH);
        assertThat(otherSkill.artifacts().artifact("skill").contentHash())
                .isNotEqualTo(baseline.artifacts().artifact("skill").contentHash());
        assertThat(otherSkill.contentHash()).isNotEqualTo(baseline.contentHash());
    }

    @Test
    void freezingExecutesNoCase(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-03", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13Scenarios.CountingDelegate port = new T13Scenarios.CountingDelegate(T13Scenarios.tierPort());

        new SegmentEvaluationRunner(root).freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(port));

        assertThat(port.calls()).isZero();
    }

    @Test
    void aTamperedCaseAssetIsRefusedByTheLoader(@TempDir Path root) throws IOException {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-04", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        Path caseFile = root.resolve("evaluation/segment/t13/cases/case-freeze-04.json");
        Files.writeString(caseFile,
                Files.readString(caseFile, StandardCharsets.UTF_8).replace("MEDIUM", "LOW"), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(T13Scenarios.tierPort())))
                .isInstanceOf(EvaluationException.class);
    }

    @Test
    void aMissingFixtureServiceIsRefused(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-05", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));
        T13TestHost host = new T13TestHost(T13Scenarios.tierPort());
        host.services(T13TestHost.TIER_FIXTURE, null);

        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"), host))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.HOST_SERVICE_MISSING);
    }

    @Test
    void aMetadataPayloadThatIsNotAT04SnapshotIsRefusedAsStale(@TempDir Path root) {
        T13TestCorpus.in(root)
                .rawMetadata(T13TestHost.SNAPSHOT_ID, "{\"not\":\"a metadata snapshot\"}")
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .caseSpec(T13Scenarios.tierCase(
                        "case-freeze-06", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(T13Scenarios.tierPort())))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_STALE);
    }

    @Test
    void aContradictoryFixtureTimeContextIsRefused(@TempDir Path root) {
        T13TestCorpus.in(root)
                .metadata(T13TestHost.snapshot())
                .context(T13TestHost.CONTEXT_FIXTURE, T13TestHost.context().snapshotRef())
                .fixture(T13TestHost.TIER_FIXTURE, FixtureRole.PRIMARY, T13TestAssets.TIER_UNIVERSE,
                        T13TestAssets.membersPayload())
                .fixture("fixture-t13-counter", FixtureRole.COUNTEREXAMPLE, T13TestAssets.TIER_UNIVERSE,
                        counterPayload(), Instant.parse("2026-09-01T02:00:00Z"), ZoneId.of("Asia/Shanghai"),
                        Instant.parse("2026-09-01T01:55:00Z"))
                .caseSpec(T13Scenarios.tierCase(
                        "case-freeze-07", RiskLevel.MEDIUM,
                        SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS))
                .write();

        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(T13Scenarios.tierPort())))
                .isInstanceOf(EvaluationRunException.class)
                .hasFieldOrPropertyWithValue("code", EvaluationRunErrorCodes.PIN_CONTRADICTORY);
    }

    @Test
    void aFixtureServiceThatChangesDuringExecutionFailsTheRunClosed(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-08", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        T13TestHost host = new T13TestHost(T13Scenarios.tierPort()) {
            private int calls;

            @Override
            public FixtureExecutionServices fixtureServices(String fixtureId) {
                calls++;
                if (calls <= 1) {
                    return super.fixtureServices(fixtureId);
                }
                return new FixtureExecutionServices(
                        com.company.campaign.query.fixture.FixtureMapping.empty("t13-fixture-mapping-2"),
                        com.company.campaign.query.fixture.FixtureScopeBinding.publish(
                                "t13-fixture-scope-2", java.util.List.of()),
                        super.fixtureServices(fixtureId).fxRateSnapshot());
            }
        };

        EvaluationReport report = T13Scenarios.run(root, "t13-freeze-08", host);

        assertThat(report.summary().runInvalid()).isTrue();
        assertThat(report.cases().get(0).attempts().get(0).taxonomy())
                .isEqualTo(FailureTaxonomy.PROVIDER_OR_INFRASTRUCTURE_FAILURE);
        assertThat(report.cases().get(0).attempts().get(0).disposition()).isEqualTo(Disposition.INVALID.name());
    }

    @Test
    void aDecodingContractMustBeClosedAndTyped() {
        assertThatThrownBy(() -> new DecodingParameters(
                "deterministic-replay", null, null, null, 4096, java.util.List.of(), 1L))
                .isInstanceOf(EvaluationRunException.class);
        assertThatThrownBy(() -> new DecodingParameters(
                "deterministic-replay", new java.math.BigDecimal("2"), null, null, 4096, java.util.List.of(), 1L))
                .isInstanceOf(EvaluationRunException.class);
        assertThatThrownBy(() -> new DecodingParameters(
                "deterministic-replay", java.math.BigDecimal.ZERO, null, null, 0, java.util.List.of(), 1L))
                .isInstanceOf(EvaluationRunException.class);
        assertThatThrownBy(() -> new DecodingParameters(
                "not a code", java.math.BigDecimal.ZERO, null, null, 4096, java.util.List.of(), 1L))
                .isInstanceOf(EvaluationRunException.class);
        assertThat(DecodingParameters.deterministicReplay(4096, 1L).isDeterministic()).isTrue();
    }

    @Test
    void anArtifactPinMustBeEitherPresentOrHonestlyAbsent() {
        assertThatThrownBy(() -> new EvaluationRunContract.ArtifactPin("skill", null, null))
                .isInstanceOf(EvaluationRunException.class);
        assertThatThrownBy(() -> new EvaluationRunContract.ArtifactPin(
                "skill", com.company.campaign.shared.canonical.ContentHash.sha256(new byte[] {1}), "also absent"))
                .isInstanceOf(EvaluationRunException.class);
        assertThat(EvaluationRunContract.ArtifactPin.absent("skill", "no committed Skill").isPresent()).isFalse();
        assertThatThrownBy(() -> new EvaluationRunContract.ArtifactPins(java.util.List.of(
                EvaluationRunContract.ArtifactPin.absent("skill", "absent"))))
                .isInstanceOf(EvaluationRunException.class);
    }

    @Test
    void noWallClockRandomOrAmbientStateIsReadWhileFreezing(@TempDir Path root) {
        T13Scenarios.corpusWith(root, T13Scenarios.tierCase(
                "case-freeze-09", RiskLevel.MEDIUM, SegmentCategory.ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS));

        // The frozen time context is exactly the reviewed fixture context, and the code revision and
        // environment are the explicit request fields: they cannot drift between two runs on the same inputs.
        EvaluationRunContract contract = new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"),
                        new T13TestHost(T13Scenarios.tierPort())).contract();
        assertThat(contract.time().asOf()).isEqualTo(T13TestHost.context().snapshotRef().asOf());
        assertThat(contract.time().dataWatermark())
                .isEqualTo(T13TestHost.context().snapshotRef().dataWatermark());
        assertThat(contract.code().codeRevision()).isEqualTo("code-revision-t13-test");
        assertThat(contract.code().environment()).isEqualTo("environment-t13-test");
    }

    @Test
    void anUnreadableRepositoryRootIsReportedRatherThanSilentlyIgnored(@TempDir Path root) {
        assertThatThrownBy(() -> new SegmentEvaluationRunner(root)
                .freeze(T13TestAssets.request("t13-freeze"), new T13TestHost(T13Scenarios.tierPort())))
                .isInstanceOf(RuntimeException.class);
    }

}