package com.company.campaign.journey.graph;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.compiler.JourneyManifestFixtures;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.contracts.Issue;
import com.company.campaign.journey.compiler.JourneyTestFixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Focused T20 section-2 tests: node derivation and the deterministic graph invariants. */
class JourneyGraphValidatorTest {

    private final JourneyGraphValidator validator = new JourneyGraphValidator();

    private static final String MESSAGE_TEMPLATE = "message-template-1";
    private static final String PROMOTION = "promotion-1";

    private JourneyGraphResult validate(
            List<ResolvedComponent> components, JourneyTopology topology) {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(components);
        return validator.validate(manifest, topology, JourneyTestFixture.index());
    }

    private JourneyGraphResult validate(
            List<ResolvedComponent> components, JourneyTopology topology, T19PackIndex index) {
        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(components);
        return validator.validate(manifest, topology, index);
    }

    private static List<String> codes(JourneyGraphResult result) {
        List<String> codes = new ArrayList<>();
        for (Issue issue : result.issues()) {
            codes.add(issue.code());
        }
        return codes;
    }

    // ------------------------------------------------------------------ valid shapes

    @Test
    void aLinearStartMessageEndGraphIsValid() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "DEFAULT")));

        assertThat(result.isValid()).as(codes(result).toString()).isTrue();
        assertThat(result.startNodeLogicalId()).isEqualTo("s");
        assertThat(result.terminalNodeLogicalIds()).containsExactly("e");
        assertThat(result.nodes()).extracting(JourneyNode::logicalId).containsExactly("e", "m", "s");
        assertThat(result.nodes().get(2).kind()).isEqualTo(JourneyNodeKind.START);
    }

    @Test
    void aConditionSplitAndConvergingDagIsValid() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.condition("c"),
                JourneyTestFixture.message("m1", MESSAGE_TEMPLATE),
                JourneyTestFixture.message("m2", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-t", "c", "m1", "TRUE"),
                JourneyTestFixture.edge("c-f", "c", "m2", "FALSE"),
                JourneyTestFixture.edge("m1-e", "m1", "e", "DEFAULT"),
                JourneyTestFixture.edge("m2-e", "m2", "e", "DEFAULT")));

        assertThat(result.isValid()).as(codes(result).toString()).isTrue();
    }

    @Test
    void endAndExitTerminalsAreDistinguished() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.condition("c"),
                JourneyTestFixture.end("done", "END"),
                JourneyTestFixture.end("left", "EXIT"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-t", "c", "done", "TRUE"),
                JourneyTestFixture.edge("c-f", "c", "left", "FALSE")));

        assertThat(result.isValid()).as(codes(result).toString()).isTrue();
        assertThat(result.node("done").kind()).isEqualTo(JourneyNodeKind.END);
        assertThat(result.node("left").kind()).isEqualTo(JourneyNodeKind.EXIT);
        assertThat(result.terminalNodeLogicalIds()).containsExactly("done", "left");
    }

    @Test
    void waitAnchorsAreAccepted() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.wait("w1", "PT1H", "ENTRY"),
                JourneyTestFixture.wait("w2", "P1D", "PREVIOUS_ACCEPTED_ACTION"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-w1", "s", "w1", "DEFAULT"),
                JourneyTestFixture.edge("w1-w2", "w1", "w2", "DEFAULT"),
                JourneyTestFixture.edge("w2-e", "w2", "e", "DEFAULT")));

        assertThat(result.isValid()).as(codes(result).toString()).isTrue();
    }

    // ------------------------------------------------------------------ fail-closed shapes

    @Test
    void missingStartAndMissingTerminalAreReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology());

        assertThat(codes(result)).contains(
                JourneyGraphErrorCodes.START_CARDINALITY, JourneyGraphErrorCodes.TERMINAL_MISSING);
    }

    @Test
    void duplicateStartIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s1"),
                JourneyTestFixture.start("s2"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s1-e", "s1", "e", "DEFAULT"),
                JourneyTestFixture.edge("s2-e", "s2", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.START_CARDINALITY);
    }

    @Test
    void unknownEdgeEndpointIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-ghost", "s", "ghost", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.EDGE_ENDPOINT_UNKNOWN);
    }

    @Test
    void duplicateBranchFromOneSourceIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e1", "END"),
                JourneyTestFixture.end("e2", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e1", "m", "e1", "DEFAULT"),
                JourneyTestFixture.edge("m-e2", "m", "e2", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.DUPLICATE_BRANCH);
    }

    @Test
    void illegalStartDegreeIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.end("e1", "END"),
                JourneyTestFixture.end("e2", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e1", "s", "e1", "DEFAULT"),
                JourneyTestFixture.edge("s-e2", "s", "e2", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.NODE_DEGREE);
    }

    @Test
    void conditionWithoutTwoBranchesIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.condition("c"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-e", "c", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CONDITION_BRANCHES);
    }

    @Test
    void nonConditionNodeWithAConditionArmIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "TRUE")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.BRANCH_KIND_INVALID);
    }

    @Test
    void unreachableNodeIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.end("orphan", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e", "s", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.UNREACHABLE_NODE);
    }

    @Test
    void aCycleIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", MESSAGE_TEMPLATE),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-s", "m", "s", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CYCLE);
    }

    @Test
    void aNonTerminatingShapeIsReported() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.condition("c"),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.message("a", MESSAGE_TEMPLATE),
                JourneyTestFixture.message("b", MESSAGE_TEMPLATE),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-c", "s", "c", "DEFAULT"),
                JourneyTestFixture.edge("c-e", "c", "e", "TRUE"),
                JourneyTestFixture.edge("c-a", "c", "a", "FALSE"),
                JourneyTestFixture.edge("a-b", "a", "b", "DEFAULT"),
                JourneyTestFixture.edge("b-a", "b", "a", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.NON_TERMINATING);
    }

    // ------------------------------------------------------------------ capability and reference fail-closed

    @Test
    void aDisabledCapabilityFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"), JourneyTestFixture.end("e", "END"));
        T19PackIndex index = JourneyTestFixture.indexWith(JourneyTestFixture.facts(
                FlowPackVersions.START, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT,
                false, FlowPackVersions.RUNTIME_COMPATIBILITY));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e", "s", "e", "DEFAULT")), index);

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CAPABILITY_DISABLED);
    }

    @Test
    void aContractVersionMismatchFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.flowAt("s", FlowPackVersions.START, "2.0", FlowPackVersions.SLOT,
                        java.util.Map.of()),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e", "s", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CAPABILITY_CONTRACT_MISMATCH);
    }

    @Test
    void aSlotMismatchFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.flowAt("s", FlowPackVersions.START, FlowPackVersions.CONTRACT_VERSION,
                        new com.company.campaign.builder.compiler.CompositionSlot("campaign.message", true, false),
                        java.util.Map.of()),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e", "s", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CAPABILITY_SLOT_MISMATCH);
    }

    @Test
    void aRuntimeIncompatiblePackFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"), JourneyTestFixture.end("e", "END"));
        T19PackIndex index = JourneyTestFixture.indexWith(JourneyTestFixture.facts(
                FlowPackVersions.START, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT,
                true, new RuntimeCompatibility("campaign-runtime/9.9", true, false)));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-e", "s", "e", "DEFAULT")), index);

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.CAPABILITY_RUNTIME_INCOMPATIBLE);
    }

    @Test
    void anUnresolvedMessageReferenceFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.message("m", "absent-template"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.MESSAGE_REFERENCE_UNRESOLVED);
    }

    @Test
    void anUnresolvedRewardReferenceFailsClosed() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.reward("r", "absent-promotion"),
                JourneyTestFixture.end("e", "END"));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-r", "s", "r", "DEFAULT"),
                JourneyTestFixture.edge("r-e", "r", "e", "DEFAULT")));

        assertThat(codes(result)).contains(JourneyGraphErrorCodes.REWARD_REFERENCE_UNRESOLVED);
    }

    @Test
    void aRewardWithAnExistingPromotionIsValidAndCardinalityIsBounded() {
        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"),
                JourneyTestFixture.reward("r", PROMOTION),
                JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.promotion(PROMOTION));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-r", "s", "r", "DEFAULT"),
                JourneyTestFixture.edge("r-e", "r", "e", "DEFAULT")));

        assertThat(result.isValid()).as(codes(result).toString()).isTrue();
    }

    @Test
    void nodeHashesPreserveThePayloadAndExactCatalogReferences() {
        ResolvedCatalogReference reference = new ResolvedCatalogReference(
                "template", "MESSAGE_TEMPLATE", MESSAGE_TEMPLATE, "message-welcome-01");
        ResolvedComponent message = JourneyTestFixture.message("m", MESSAGE_TEMPLATE, List.of(reference));

        ResolvedComponent withoutReference = JourneyTestFixture.message("m", MESSAGE_TEMPLATE);

        List<ResolvedComponent> components = List.of(
                JourneyTestFixture.start("s"), message, JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE));
        JourneyGraphResult result = validate(components, JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "DEFAULT")));

        JourneyGraphResult plainResult = validate(List.of(
                JourneyTestFixture.start("s"), withoutReference, JourneyTestFixture.end("e", "END"),
                JourneyTestFixture.messageTemplate(MESSAGE_TEMPLATE)), JourneyTestFixture.topology(
                JourneyTestFixture.edge("s-m", "s", "m", "DEFAULT"),
                JourneyTestFixture.edge("m-e", "m", "e", "DEFAULT")));

        JourneyNode node = result.node("m");
        assertThat(node.payloadHash()).isEqualTo(message.value().contentHash());
        assertThat(node.payloadHash()).isEqualTo(withoutReference.value().contentHash());
        assertThat(node.catalogReferenceHash()).isNotEqualTo(plainResult.node("m").catalogReferenceHash());
    }
}
