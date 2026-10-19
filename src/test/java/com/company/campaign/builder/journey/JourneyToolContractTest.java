package com.company.campaign.builder.journey;

import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowCapabilityPacks;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.journey.graph.JourneyGraphErrorCodes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 3/4/5/6: the host-owned Journey authoring adapter drives the accepted T19 Flow packs and the
 * accepted T20 graph contract exactly, and reports only bounded facts.
 */
class JourneyToolContractTest {

    private static final JourneyGraphAdapter ADAPTER = new JourneyGraphAdapter();

    @Test
    void theNodeCatalogIsTheClosedSixNodeSetInDeclarationOrder() {
        List<JourneyNodeCapability> catalog = ADAPTER.nodeCatalog();
        assertThat(catalog).extracting(JourneyNodeCapability::capabilityCode).containsExactlyElementsOf(
                FlowPackVersions.CAPABILITY_CODES);
        assertThat(catalog).extracting(JourneyNodeCapability::nodeKind).containsExactly(
                "START", "MESSAGE", "WAIT", "CONDITION", "REWARD", "END");
        assertThat(catalog).extracting(JourneyNodeCapability::terminal)
                .containsExactly(false, false, false, false, false, true);
        assertThat(catalog).extracting(JourneyNodeCapability::contractVersion)
                .containsOnly(FlowPackVersions.CONTRACT_VERSION);
    }

    @Test
    void aMinimalStartToEndGraphValidatesAndComposesDeterministically() {
        JourneyGraphDraft draft = minimal();
        JourneyComposition composition = ADAPTER.compose(
                draft, catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        assertThat(composition.nodes()).hasSize(2);
        assertThat(composition.edgeCount()).isEqualTo(1);
        assertThat(ADAPTER.compose(draft, catalog(), BuilderFixtures.policy(), BuilderFixtures.context()))
                .isEqualTo(composition);

        JourneyValidationOutcome outcome = ADAPTER.validate(
                draft, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.nodeCount()).isEqualTo(2);
        assertThat(outcome.edgeCount()).isEqualTo(1);
        assertThat(outcome.entryNodeRef()).isEqualTo("n-start");
        assertThat(outcome.terminalNodeRefs()).containsExactly("n-end");
        assertThat(outcome.journeyContentHash()).isNotNull();
        assertThat(outcome.topologyHash()).isNotNull();
        assertThat(outcome.issueCodes()).isEmpty();
    }

    @Test
    void aWaitConditionAndMessageGraphIsAcceptedWhenTheWholeGraphProvesIt() {
        // The wait, condition and message shapes are all accepted when the complete graph proves them.
        JourneyGraphDraft conditionGraph = new JourneyGraphDraft(
                List.of(
                        node("n-start", FlowPackVersions.START, Map.of()),
                        node("n-message", FlowPackVersions.MESSAGE, Map.of("messageLogicalId", "message-1")),
                        node("n-wait", FlowPackVersions.WAIT,
                                Map.of(FlowWaitPack.DURATION_FIELD, "PT5M", FlowWaitPack.ANCHOR_FIELD,
                                        FlowWaitPack.ANCHOR_ENTRY)),
                        node("n-condition", FlowPackVersions.CONDITION, Map.of(
                                "factCode", "member.tier", "operator", "EQ", "valueType", "STRING",
                                "value", "GOLD", "onUnknown", "UNKNOWN")),
                        node("n-end", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END)),
                        node("n-exit", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_EXIT))),
                List.of(
                        edge("e1", "n-start", "n-message", "DEFAULT"),
                        edge("e2", "n-message", "n-wait", "DEFAULT"),
                        edge("e3", "n-wait", "n-condition", "DEFAULT"),
                        edge("e4", "n-condition", "n-end", "TRUE"),
                        edge("e5", "n-condition", "n-exit", "FALSE")),
                List.of(new JourneyReferenceDeclaration("message-1", "message.template")));

        JourneyValidationOutcome outcome = ADAPTER.validate(
                conditionGraph, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        assertThat(outcome.valid()).isTrue();
        assertThat(outcome.nodeCount()).isEqualTo(6);
        assertThat(outcome.terminalNodeRefs()).containsExactly("n-end", "n-exit");
    }

    @Test
    void anInvalidGraphIsReportedAsBoundedCodesAndNeverAsAPartialJourney() {
        // A Start with no terminal at all: the accepted graph contract refuses it.
        JourneyGraphDraft untoward = new JourneyGraphDraft(
                List.of(node("n-start", FlowPackVersions.START, Map.of())),
                List.of(),
                List.of());
        JourneyValidationOutcome outcome = ADAPTER.validate(
                untoward, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.nodes()).isEmpty();
        assertThat(outcome.journeyContentHash()).isNull();
        assertThat(outcome.topologyHash()).isNull();
        assertThat(outcome.issueCodes()).contains(JourneyGraphErrorCodes.TERMINAL_MISSING);
        assertThat(outcome.issueCodes()).contains(JourneyGraphErrorCodes.NODE_DEGREE);
    }

    @Test
    void aRefusedNodePayloadFailsClosedWithABoundedCode() {
        JourneyGraphDraft refused = new JourneyGraphDraft(
                List.of(
                        node("n-start", FlowPackVersions.START, Map.of()),
                        node("n-end", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END)),
                        node("n-extra", FlowPackVersions.START, Map.of("terminal", "END"))),
                List.of(edge("e1", "n-start", "n-end", "DEFAULT")),
                List.of());
        // The third node declares a field its closed payload contract does not carry.
        JourneyAuthoringException failure = catchThrowableOfType(
                () -> ADAPTER.compose(refused, catalog(), BuilderFixtures.policy(), BuilderFixtures.context()),
                JourneyAuthoringException.class);
        assertThat(failure.code()).isEqualTo(JourneyAuthoringErrorCodes.NODE_PAYLOAD_REJECTED);
    }

    @Test
    void theGraphBoundsAndEdgeDeclarationsAreClosed() {
        assertThatThrownBy(() -> new JourneyEdgeDeclaration("e1", "n-start", "n-start", "DEFAULT"))
                .isInstanceOf(JourneyAuthoringException.class);
        assertThatThrownBy(() -> new JourneyEdgeDeclaration("e1", "n-start", "n-end", "default"))
                .isInstanceOf(JourneyAuthoringException.class);
        assertThatThrownBy(() -> new JourneyGraphDraft(
                List.of(node("n-start", FlowPackVersions.START, Map.of())),
                List.of(edge("e1", "n-start", "n-missing", "DEFAULT")),
                List.of()))
                .isInstanceOf(JourneyAuthoringException.class);
        assertThatThrownBy(() -> new JourneyGraphDraft(List.of(), List.of(), List.of()))
                .isInstanceOf(JourneyAuthoringException.class);
        assertThatThrownBy(() -> new JourneyGraphDraft(
                List.of(node("n-start", FlowPackVersions.START, Map.of()),
                        node("n-start", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))),
                List.of(),
                List.of()))
                .isInstanceOf(JourneyAuthoringException.class);
    }

    @Test
    void simulationIsDeterministicPureAndNeverStageable() {
        JourneySimulationInput input = JourneySimulationInput.defaults(
                "2026-10-10T00:00:00Z", "Asia/Shanghai", List.of());
        JourneySimulationSummary first = ADAPTER.simulate(
                minimal(), input, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        JourneySimulationSummary second = ADAPTER.simulate(
                minimal(), input, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());

        assertThat(first).isEqualTo(second);
        assertThat(first.isValid()).isTrue();
        assertThat(first.environment()).isEqualTo(JourneySimulationSummary.ENVIRONMENT_SIMULATED);
        assertThat(first.pathCount()).isEqualTo(1);
        assertThat(first.pathStatuses()).containsExactly("COMPLETED");
        assertThat(first.simulationHash()).isNotNull();

        assertThat(com.company.campaign.builder.assembly.CampaignToolIds.isStageableArtifact(
                com.company.campaign.builder.assembly.CampaignSkillIds.JOURNEY_FLOW,
                com.company.campaign.builder.assembly.CampaignToolIds.JOURNEY_SIMULATE)).isFalse();
        assertThat(com.company.campaign.builder.assembly.CampaignToolIds.isStageableArtifact(
                com.company.campaign.builder.assembly.CampaignSkillIds.JOURNEY_FLOW,
                com.company.campaign.builder.assembly.CampaignToolIds.JOURNEY_VALIDATE)).isTrue();
    }

    @Test
    void anUndeclaredReferenceTargetIsRefusedRatherThanInvented() {
        JourneyGraphDraft missingReference = new JourneyGraphDraft(
                List.of(
                        node("n-start", FlowPackVersions.START, Map.of()),
                        node("n-message", FlowPackVersions.MESSAGE, Map.of("messageLogicalId", "nowhere")),
                        node("n-end", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))),
                List.of(edge("e1", "n-start", "n-message", "DEFAULT"),
                        edge("e2", "n-message", "n-end", "DEFAULT")),
                List.of());
        JourneyValidationOutcome outcome = ADAPTER.validate(
                missingReference, packIndex(), catalog(), BuilderFixtures.policy(), BuilderFixtures.context());
        assertThat(outcome.valid()).isFalse();
        assertThat(outcome.issueCodes())
                .contains(JourneyGraphErrorCodes.MESSAGE_REFERENCE_UNRESOLVED);
    }

    // ------------------------------------------------------------------ fixtures

    private static JourneyGraphDraft minimal() {
        return new JourneyGraphDraft(
                List.of(
                        node("n-start", FlowPackVersions.START, Map.of()),
                        node("n-end", FlowPackVersions.END,
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))),
                List.of(edge("e1", "n-start", "n-end", "DEFAULT")),
                List.of());
    }

    private static JourneyNodeDeclaration node(
            String ref, String capabilityCode, Map<String, Object> payload) {
        return new JourneyNodeDeclaration(ref, capabilityCode, CapabilityPayload.of(payload));
    }

    private static JourneyEdgeDeclaration edge(
            String edgeId, String from, String to, String branch) {
        return new JourneyEdgeDeclaration(edgeId, from, to, branch);
    }

    private static com.company.campaign.builder.compiler.FrozenCapabilityCatalog catalog() {
        return BuilderFixtures.catalog(FlowCapabilityPacks.all().toArray(new CapabilityPackContract[0]));
    }

    /**
     * The frozen index of this test: the six accepted Flow node packs plus the two capability facts a declared
     * cross-component reference may target.
     */
    private static T19PackIndex packIndex() {
        List<T19PackFacts> facts = new java.util.ArrayList<>();
        for (CapabilityPackContract pack : FlowCapabilityPacks.all()) {
            facts.add(T19PackFacts.of(pack));
        }
        facts.add(referenceFacts("message.template", "campaign.message"));
        facts.add(referenceFacts("promotion.reference", "campaign.promotion"));
        return T19PackIndex.of(FlowPackVersions.RUNTIME_SCHEMA_VERSION, List.copyOf(facts));
    }

    private static T19PackFacts referenceFacts(String capabilityCode, String slotCode) {
        return new T19PackFacts(
                capabilityCode,
                FlowPackVersions.CONTRACT_VERSION,
                new CompositionSlot(slotCode, false, false),
                true,
                new RuntimeCompatibility(FlowPackVersions.RUNTIME_SCHEMA_VERSION, true, false));
    }
}