package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.journey.graph.JourneyBranchKind;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T20 section-5 tests: the pure virtual-clock simulator, UNKNOWN forks, ERROR and hard limits. */
class JourneySimulatorTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    private final JourneySimulator simulator = new JourneySimulator();

    private static ResolvedComponent condition(
            String id, String factCode, String operator, String valueType, String value, String onUnknown) {
        return SimulationTestFixture.condition(id, factCode, operator, valueType, value, onUnknown);
    }

    // ------------------------------------------------------------------ linear, actions, clock

    @Test
    void aLinearJourneyReportsOneCompletedPathAndBindsEveryHash() {
        SimulationTestFixture.Linear bound = SimulationTestFixture.linear();
        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        assertThat(result.environment()).isEqualTo(SimulationVersions.ENVIRONMENT_SIMULATED);
        assertThat(result.isValid()).isTrue();
        assertThat(result.journeyContentHash()).isEqualTo(bound.journey().contentHash());
        assertThat(result.topologyHash()).isEqualTo(bound.journey().topologyHash());
        assertThat(result.sourceManifestHash()).isEqualTo(bound.sourceManifest().manifestHash());
        assertThat(result.scenarioHash()).isEqualTo(ScenarioFacts.empty().scenarioHash());
        assertThat(result.virtualStartInstant()).isEqualTo(START);
        assertThat(result.virtualTimezone()).hasToString("UTC");
        assertThat(result.simulatorVersion()).isEqualTo(SimulationVersions.SIMULATOR_VERSION);

        List<SimulatedPath> paths = result.requirePaths();
        assertThat(paths).hasSize(1);
        SimulatedPath path = paths.get(0);
        assertThat(path.status()).isEqualTo(SimulatedPathStatus.COMPLETED);
        assertThat(path.visitedNodeLogicalIds()).containsExactly("s", "m", "e");
        assertThat(path.actions()).hasSize(1);
        assertThat(path.actions().get(0).kind()).isEqualTo(SimulatedActionKind.MESSAGE);
        assertThat(path.actions().get(0).virtualInstant()).isEqualTo(START);
        assertThat(path.entryVirtualInstants()).containsExactly(START, START, START);
        assertThat(path.assumptions()).isEmpty();
    }

    @Test
    void aRewardRecordsOnlyAVirtualRewardActionCode() {
        List<ResolvedComponent> components = List.of(
                SimulationTestFixture.start("s"),
                SimulationTestFixture.reward("r"),
                SimulationTestFixture.end("e", "EXIT"),
                SimulationTestFixture.promotion(SimulationTestFixture.PROMOTION));
        var bound = SimulationTestFixture.compileBound(components, SimulationTestFixture.topology(
                SimulationTestFixture.edge("s-r", "s", "r", "DEFAULT"),
                SimulationTestFixture.edge("r-e", "r", "e", "DEFAULT")));

        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        SimulatedPath path = result.requirePaths().get(0);
        assertThat(path.status()).isEqualTo(SimulatedPathStatus.EXITED);
        assertThat(path.actions()).extracting(SimulatedAction::kind)
                .containsExactly(SimulatedActionKind.REWARD);
        assertThat(path.actions().get(0).kind().code()).isEqualTo("REWARD");
    }

    @Test
    void aWaitAdvancesTheVirtualClockFromItsEntryAnchor() {
        var bound = waitJourney("PT15M", "ENTRY");
        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        SimulatedPath path = result.requirePaths().get(0);
        assertThat(path.visitedNodeLogicalIds()).containsExactly("s", "w", "e");
        assertThat(path.entryVirtualInstants())
                .containsExactly(START, START, START.plus(Duration.ofMinutes(15)));
    }

    @Test
    void aWaitAdvancesFromThePreviousAcceptedActionWhenAnchoredThere() {
        List<ResolvedComponent> components = List.of(
                SimulationTestFixture.start("s"),
                SimulationTestFixture.message("m"),
                SimulationTestFixture.wait("w", "PT1H", "PREVIOUS_ACCEPTED_ACTION"),
                SimulationTestFixture.end("e", "END"),
                SimulationTestFixture.messageTemplate(SimulationTestFixture.MESSAGE_TEMPLATE));
        var bound = SimulationTestFixture.compileBound(components, SimulationTestFixture.topology(
                SimulationTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                SimulationTestFixture.edge("m-w", "m", "w", "DEFAULT"),
                SimulationTestFixture.edge("w-e", "w", "e", "DEFAULT")));

        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        SimulatedPath path = result.requirePaths().get(0);
        assertThat(path.entryVirtualInstants().get(2)).isEqualTo(START);
        assertThat(path.entryVirtualInstants().get(3)).isEqualTo(START.plus(Duration.ofHours(1)));
    }

    // ------------------------------------------------------------------ condition selection

    @Test
    void aKnownConditionSelectsTheDeterministicArm() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN);

        SimulationResult trues = simulator.simulate(SimulationTestFixture.request(bound,
                ScenarioFacts.of(ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD")),
                SimulationPolicy.defaults()));
        assertThat(trues.requirePaths()).hasSize(1);
        assertThat(trues.requirePaths().get(0).terminalNodeLogicalId()).isEqualTo("t");

        SimulationResult falses = simulator.simulate(SimulationTestFixture.request(bound,
                ScenarioFacts.of(ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "SILVER")),
                SimulationPolicy.defaults()));
        assertThat(falses.requirePaths()).hasSize(1);
        assertThat(falses.requirePaths().get(0).terminalNodeLogicalId()).isEqualTo("f");
    }

    @Test
    void anUnknownFactForksBothArmsAndRecordsBoundedAssumptions() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN);
        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        List<SimulatedPath> paths = result.requirePaths();
        assertThat(paths).hasSize(2);
        assertThat(paths).extracting(SimulatedPath::terminalNodeLogicalId)
                .containsExactlyInAnyOrder("t", "f");
        for (SimulatedPath path : paths) {
            assertThat(path.assumptions()).hasSize(1);
            SimulatedAssumption assumption = path.assumptions().get(0);
            assertThat(assumption.factCode()).isEqualTo("member.tier");
            assertThat(assumption.nodeLogicalId()).isEqualTo("c");
            assertThat(assumption.assumedBranch()).isIn(JourneyBranchKind.TRUE, JourneyBranchKind.FALSE);
            assertThat(assumption.code()).isEqualTo(SimulationErrorCodes.UNKNOWN_FACT_ASSUMPTION);
        }
        assertThat(paths).extracting(path -> path.assumptions().get(0).assumedBranch())
                .containsExactlyInAnyOrder(JourneyBranchKind.TRUE, JourneyBranchKind.FALSE);
        assertThat(paths).noneMatch(SimulatedPath::isErrored);
    }

    @Test
    void anUnknownFactWithTheFailClosedPolicyTerminatesRatherThanBranching() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN_ERROR);
        SimulationResult result = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        List<SimulatedPath> paths = result.requirePaths();
        assertThat(paths).hasSize(1);
        SimulatedPath path = paths.get(0);
        assertThat(path.status()).isEqualTo(SimulatedPathStatus.ERRORED);
        assertThat(path.errorReasonCode())
                .isEqualTo(SimulationErrorCodes.CONDITION_FACT_UNKNOWN_FAIL_CLOSED);
        assertThat(path.terminalNodeLogicalId()).isEqualTo("c");
        assertThat(path.visitedNodeLogicalIds()).containsExactly("s", "c");
    }

    @Test
    void anExplicitFactErrorTerminatesWithItsOwnBoundedReason() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN);
        SimulationResult result = simulator.simulate(SimulationTestFixture.request(bound,
                ScenarioFacts.of(ScenarioFact.error("member.tier", "FACT_SOURCE_UNAVAILABLE")),
                SimulationPolicy.defaults()));

        SimulatedPath path = result.requirePaths().get(0);
        assertThat(path.status()).isEqualTo(SimulatedPathStatus.ERRORED);
        assertThat(path.errorReasonCode()).isEqualTo("FACT_SOURCE_UNAVAILABLE");
    }

    // ------------------------------------------------------------------ determinism and immutability

    @Test
    void identicalInputsProduceByteIdenticalReports() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN);
        SimulationRequest request = SimulationTestFixture.request(
                bound, ScenarioFacts.empty(), SimulationPolicy.defaults());

        SimulationResult first = simulator.simulate(request);
        for (int attempt = 0; attempt < 25; attempt++) {
            SimulationResult candidate = simulator.simulate(request);
            assertThat(candidate.canonicalBytes()).isEqualTo(first.canonicalBytes());
            assertThat(candidate.contentHash()).isEqualTo(first.contentHash());
        }
    }

    @Test
    void aScenarioOrPolicyChangeChangesTheReportHash() {
        var bound = SimulationTestFixture.linear();
        SimulationResult base = simulator.simulate(
                SimulationTestFixture.request(bound, ScenarioFacts.empty(), SimulationPolicy.defaults()));

        SimulationResult withFact = simulator.simulate(SimulationTestFixture.request(bound,
                ScenarioFacts.of(ScenarioFact.known("member.tier", FlowConditionPack.TYPE_STRING, "GOLD")),
                SimulationPolicy.defaults()));
        SimulationResult tighter = simulator.simulate(SimulationTestFixture.request(
                bound, ScenarioFacts.empty(), new SimulationPolicy(2, 2, 2)));

        assertThat(withFact.contentHash()).isNotEqualTo(base.contentHash());
        assertThat(tighter.contentHash()).isNotEqualTo(base.contentHash());
    }

    @Test
    void thePathListIsDefensivelyImmutable() {
        SimulationResult result = simulator.simulate(SimulationTestFixture.request(
                SimulationTestFixture.linear(), ScenarioFacts.empty(), SimulationPolicy.defaults()));

        assertThatThrownBy(() -> result.paths().add(result.paths().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> result.requirePaths().get(0).visitedNodeLogicalIds().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ hard limits fail closed

    @Test
    void aPathLimitIsExhaustedRatherThanSilentlyTruncated() {
        var bound = conditionJourney(FlowConditionPack.ON_UNKNOWN);
        SimulationResult result = simulator.simulate(SimulationTestFixture.request(
                bound, ScenarioFacts.empty(), new SimulationPolicy(1, 256, 256)));

        assertThat(result.isFailedClosed()).isTrue();
        assertThat(result.paths()).isEmpty();
        assertThat(result.issues()).extracting("code")
                .containsExactly(SimulationErrorCodes.PATH_LIMIT_EXCEEDED);
        assertThatThrownBy(result::requirePaths).isInstanceOf(SimulationException.class);
    }

    @Test
    void aStepLimitIsExhaustedRatherThanSilentlyTruncated() {
        SimulationResult result = simulator.simulate(SimulationTestFixture.request(
                SimulationTestFixture.linear(), ScenarioFacts.empty(), new SimulationPolicy(64, 2, 256)));

        assertThat(result.isFailedClosed()).isTrue();
        assertThat(result.issues()).extracting("code")
                .containsExactly(SimulationErrorCodes.STEP_LIMIT_EXCEEDED);
    }

    @Test
    void anActionLimitIsExhaustedRatherThanSilentlyTruncated() {
        List<ResolvedComponent> components = List.of(
                SimulationTestFixture.start("s"),
                SimulationTestFixture.message("m1"),
                SimulationTestFixture.message("m2"),
                SimulationTestFixture.end("e", "END"),
                SimulationTestFixture.messageTemplate(SimulationTestFixture.MESSAGE_TEMPLATE));
        var bound = SimulationTestFixture.compileBound(components, SimulationTestFixture.topology(
                SimulationTestFixture.edge("s-m1", "s", "m1", "DEFAULT"),
                SimulationTestFixture.edge("m1-m2", "m1", "m2", "DEFAULT"),
                SimulationTestFixture.edge("m2-e", "m2", "e", "DEFAULT")));

        SimulationResult result = simulator.simulate(SimulationTestFixture.request(
                bound, ScenarioFacts.empty(), new SimulationPolicy(64, 256, 1)));

        assertThat(result.isFailedClosed()).isTrue();
        assertThat(result.issues()).extracting("code")
                .containsExactly(SimulationErrorCodes.ACTION_LIMIT_EXCEEDED);
    }

    // ------------------------------------------------------------------ input rejection

    @Test
    void aPolicyBeyondTheHardBoundsIsRefused() {
        assertThatThrownBy(() -> new SimulationPolicy(SimulationPolicy.HARD_MAX_PATHS + 1, 256, 256))
                .isInstanceOf(SimulationException.class)
                .hasFieldOrPropertyWithValue("code", SimulationErrorCodes.POLICY_INVALID);
        assertThatThrownBy(() -> new SimulationPolicy(0, 256, 256))
                .isInstanceOf(SimulationException.class);
    }

    @Test
    void aMismatchedSourceManifestIsRefused() {
        SimulationTestFixture.Linear first = SimulationTestFixture.linear();
        SimulationTestFixture.Linear other = SimulationTestFixture.compileBound(
                List.of(SimulationTestFixture.start("s"), SimulationTestFixture.end("e", "END")),
                SimulationTestFixture.topology(
                        SimulationTestFixture.edge("s-e", "s", "e", "DEFAULT")));

        assertThatThrownBy(() -> SimulationTestFixture.request(
                first.journey(), other.sourceManifest(), ScenarioFacts.empty()))
                .isInstanceOf(SimulationException.class)
                .hasFieldOrPropertyWithValue("code", SimulationErrorCodes.SOURCE_MANIFEST_MISMATCH);
    }

    @Test
    void anUnsupportedSimulatorVersionIsRefused() {
        SimulationTestFixture.Linear bound = SimulationTestFixture.linear();
        assertThatThrownBy(() -> new SimulationRequest(
                bound.journey(), bound.sourceManifest(), START, ZoneId.of("UTC"),
                ScenarioFacts.empty(), SimulationPolicy.defaults(), "other-simulator"))
                .isInstanceOf(SimulationException.class)
                .hasFieldOrPropertyWithValue("code", SimulationErrorCodes.SIMULATOR_VERSION_UNSUPPORTED);
    }

    @Test
    void theReportDeclaresTheSimulatedEnvironmentAndNoExecutionPort() {
        SimulationResult result = simulator.simulate(SimulationTestFixture.request(
                SimulationTestFixture.linear(), ScenarioFacts.empty(), SimulationPolicy.defaults()));

        assertThat(result.environment()).isEqualTo("SIMULATED");
        // The report exposes only already-safe machine facts; there is no provider, task or execution field.
        assertThat(result.toString()).contains("SIMULATED");
    }

    // ------------------------------------------------------------------ helpers

    private static SimulationTestFixture.Linear waitJourney(String duration, String anchor) {
        List<ResolvedComponent> components = List.of(
                SimulationTestFixture.start("s"),
                SimulationTestFixture.wait("w", duration, anchor),
                SimulationTestFixture.end("e", "END"));
        return SimulationTestFixture.compileBound(components, SimulationTestFixture.topology(
                SimulationTestFixture.edge("s-w", "s", "w", "DEFAULT"),
                SimulationTestFixture.edge("w-e", "w", "e", "DEFAULT")));
    }

    private static SimulationTestFixture.Linear conditionJourney(String onUnknown) {
        List<ResolvedComponent> components = List.of(
                SimulationTestFixture.start("s"),
                condition("c", "member.tier", FlowConditionPack.OPERATOR_EQUALS,
                        FlowConditionPack.TYPE_STRING, "GOLD", onUnknown),
                SimulationTestFixture.end("t", "END"),
                SimulationTestFixture.end("f", "EXIT"));
        return SimulationTestFixture.compileBound(components, SimulationTestFixture.topology(
                SimulationTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                SimulationTestFixture.edge("c-t", "c", "t", "TRUE"),
                SimulationTestFixture.edge("c-f", "c", "f", "FALSE")));
    }
}
