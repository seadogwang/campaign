package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowCapabilityPacks;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.message.MessageTemplatePack;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.capability.promotion.PromotionReferencePack;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirements 1, 2 and 10: the Campaign compiler resolves, validates and freezes the Journey topology, and it
 * binds a same-proposal {@code clientRef} reference to the final identity the merge produced.
 */
class CampaignCompilerJourneyTopologyTest {

    private static final String MESSAGE_CLIENT_REF = "message-new";
    private static final String REWARD_CLIENT_REF = "reward-new";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    private final MessageTemplatePack messagePack = new MessageTemplatePack(MessageTemplateCatalog.of(
            "tpl-catalog-1", List.of(MessageTemplateVersion.of("tpl-welcome", "tpl-welcome", "tpl-welcome-v1",
                    "EMAIL", List.of(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16)),
                    true, true, MessageClassification.PUBLIC))));
    private final PromotionReferencePack promotionPack = new PromotionReferencePack(RewardCatalog.of(
            "reward-catalog-1", List.of(RewardVersion.of("reward-welcome", "reward-welcome", "reward-welcome-v1",
                    "GRANT", "CNY", new BigDecimal("10.00"), new BigDecimal("100.00"), true, true))));

    private FrozenCapabilityCatalog catalog() {
        CapabilityPackContract[] flow = new CapabilityPackContract[] {
                new com.company.campaign.builder.capability.flow.FlowStartPack(),
                new com.company.campaign.builder.capability.flow.FlowMessagePack(),
                new com.company.campaign.builder.capability.flow.FlowWaitPack(),
                new com.company.campaign.builder.capability.flow.FlowConditionPack(),
                new com.company.campaign.builder.capability.flow.FlowRewardPack(),
                new com.company.campaign.builder.capability.flow.FlowEndPack()};
        CapabilityPackContract[] all = new CapabilityPackContract[flow.length + 2];
        System.arraycopy(flow, 0, all, 0, flow.length);
        all[flow.length] = messagePack;
        all[flow.length + 1] = promotionPack;
        return BuilderFixtures.catalog(all);
    }

    private T19PackIndex packIndex() {
        return T19PackIndex.ofPacks(
                FlowPackVersions.RUNTIME_SCHEMA_VERSION, FlowCapabilityPacks.all().toArray(new CapabilityPackContract[0]));
    }

    // ------------------------------------------------------------------ topology resolution

    @Test
    void aReplacementTopologyResolvesClientRefsToFinalIdentitiesAndReachesThePlan() {
        CampaignPlan plan = compiled(createWithMinimalTopology());
        JourneyTopology topology = plan.journeyTopology();
        assertThat(topology).isNotNull();
        assertThat(topology.topologyFormatVersion()).isEqualTo(JourneyTopology.TOPOLOGY_FORMAT_VERSION);
        assertThat(topology.edges()).hasSize(1);

        String start = componentId(plan, FlowPackVersions.START);
        String end = componentId(plan, FlowPackVersions.END);
        assertThat(start).isNotEqualTo("n-start");
        assertThat(topology.edges().get(0).fromNodeLogicalId()).isEqualTo(start);
        assertThat(topology.edges().get(0).toNodeLogicalId()).isEqualTo(end);
        assertThat(topology.edges().get(0).branch()).isEqualTo(JourneyBranchKind.DEFAULT);

        // The frozen topology is a fact of the Manifest, and the Plan material records its hash explicitly.
        assertThat(plan.manifest().journeyTopology()).isEqualTo(topology);
        assertThat(plan.manifest().hasJourneyTopology()).isTrue();
        assertThat(plan.clientRefBindings()).extracting(ClientRefBinding::clientRef)
                .containsExactly("n-start", "n-end");
    }

    @Test
    void theTopologyChangesTheManifestAndPlanHashes() {
        CampaignPlan withTopology = compiled(createWithMinimalTopology());
        CampaignPlan withoutTopology = compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                .upsertNew(FlowPackVersions.END, "n-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .build());

        assertThat(withTopology.manifestHash()).isNotEqualTo(withoutTopology.manifestHash());
        assertThat(withTopology.planHash()).isNotEqualTo(withoutTopology.planHash());
        assertThat(withoutTopology.journeyTopology()).isNull();
    }

    @Test
    void aReplacementWithoutTheFrozenIndexOrWithAnUnknownEndpointFailsClosed() {
        CompilerException noIndex = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        createWithMinimalTopology(), catalog(), policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                CompilerException.class);
        assertThat(noIndex.code()).isEqualTo(CompilerErrorCodes.COMPILER_TOPOLOGY_INPUT_MISSING);

        JourneyTopologyMutation unknownEndpoint = JourneyTopologyMutation.replace(List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-nowhere", "DEFAULT")));
        CompilerException unresolved = catchThrowableOfType(
                () -> compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                        .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                        .upsertNew(FlowPackVersions.END, "n-end",
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                        .journeyTopology(unknownEndpoint)
                        .build()),
                CompilerException.class);
        assertThat(unresolved.code())
                .isEqualTo(CompilerErrorCodes.COMPILER_TOPOLOGY_ENDPOINT_UNRESOLVED);
    }

    @Test
    void anInvalidGraphOrARemovalWithFlowNodesFailsClosed() {
        // A node the declared edges never reach is not a Journey, so the whole replacement fails closed.
        CompilerException unreachable = catchThrowableOfType(
                () -> compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                        .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                        .upsertNew(FlowPackVersions.END, "n-end",
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                        .upsertNew(FlowPackVersions.END, "n-orphan",
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_EXIT))
                        .journeyTopology(JourneyTopologyMutation.replace(List.of(
                                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-end", "DEFAULT"))))
                        .build()),
                CompilerException.class);
        assertThat(unreachable.code()).isEqualTo(CompilerErrorCodes.COMPILER_TOPOLOGY_GRAPH_INVALID);

        CompilerException removal = catchThrowableOfType(
                () -> compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                        .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                        .upsertNew(FlowPackVersions.END, "n-end",
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                        .journeyTopology(JourneyTopologyMutation.remove())
                        .build()),
                CompilerException.class);
        assertThat(removal.code()).isEqualTo(CompilerErrorCodes.COMPILER_TOPOLOGY_REMOVE_INVALID);
    }

    @Test
    void anOmissionInheritsTheTrustedCurrentTopology() {
        CampaignPlan first = compiled(createWithMinimalTopology());
        JourneyTopology frozen = first.journeyTopology();

        TrustedCurrentCampaign current = new TrustedCurrentCampaign(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, "campaign-001", 12L,
                new DraftIdentity("draft-001", 4L), null, first.manifest().components(), frozen);
        CampaignPlan second = compiler.compile(CompileRequest.edit(
                BuilderFixtures.editProposal().pins(catalog(), policy)
                        .campaignRevision(12L)
                        .upsert(FlowPackVersions.END, componentId(first, FlowPackVersions.END),
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_EXIT))
                        .build(),
                current, catalog(), policy, BuilderFixtures.instants(), BuilderFixtures.context(),
                CapabilityCompileContext.empty(), packIndex()));

        assertThat(second.journeyTopology()).isEqualTo(frozen);
        assertThat(second.manifestHash()).isNotEqualTo(first.manifestHash());
    }

    // ------------------------------------------------------------------ same-proposal references

    @Test
    void aFlowMessageNodeBindsASameProposalMessageReferenceToItsFinalIdentity() {
        CampaignPlan plan = compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                .upsertNew("message.template", MESSAGE_CLIENT_REF,
                        Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Ada")))
                .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                .upsertNew(FlowPackVersions.MESSAGE, "n-message",
                        Map.of("messageLogicalId", MESSAGE_CLIENT_REF))
                .upsertNew(FlowPackVersions.END, "n-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .journeyTopology(JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-message", "DEFAULT"),
                        ProposalTopologyEdge.ofDeclaredEndpoints("e2", "n-message", "n-end", "DEFAULT"))))
                .build());

        String messageLogicalId = componentId(plan, "message.template");
        String flowLogicalId = componentId(plan, FlowPackVersions.MESSAGE);
        ResolvedComponent flow = plan.manifest().component(flowLogicalId);
        assertThat(flow.value().fields().get("messageLogicalId"))
                .isEqualTo(messageLogicalId)
                .isNotEqualTo(MESSAGE_CLIENT_REF);
        assertThat(plan.manifest().journeyTopology()).isNotNull();
    }

    @Test
    void aFlowRewardNodeBindsASameProposalPromotionReferenceToItsFinalIdentity() {
        CampaignPlan plan = compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                .upsertNew("promotion.reference", REWARD_CLIENT_REF,
                        Map.of("referenceCode", "reward-welcome", "quantity", 1))
                .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                .upsertNew(FlowPackVersions.REWARD, "n-reward",
                        Map.of("promotionLogicalId", REWARD_CLIENT_REF))
                .upsertNew(FlowPackVersions.END, "n-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .journeyTopology(JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-reward", "DEFAULT"),
                        ProposalTopologyEdge.ofDeclaredEndpoints("e2", "n-reward", "n-end", "DEFAULT"))))
                .build());

        String promotionLogicalId = componentId(plan, "promotion.reference");
        ResolvedComponent flow = plan.manifest().component(componentId(plan, FlowPackVersions.REWARD));
        assertThat(flow.value().fields().get("promotionLogicalId")).isEqualTo(promotionLogicalId);
    }

    @Test
    void anUnresolvedOrWrongCapabilityReferenceFailsClosedAndTheTargetHashChangesWhenRebound() {
        // A Flow Message node naming a reference the proposal never declares stays unresolved, so the graph
        // contract refuses the whole compile rather than keeping a clientRef in the normalized payload.
        CompilerException unresolved = catchThrowableOfType(
                () -> compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                        .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                        .upsertNew(FlowPackVersions.MESSAGE, "n-message",
                                Map.of("messageLogicalId", "nowhere"))
                        .upsertNew(FlowPackVersions.END, "n-end",
                                Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                        .journeyTopology(JourneyTopologyMutation.replace(List.of(
                                ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-message", "DEFAULT"),
                                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "n-message", "n-end", "DEFAULT"))))
                        .build()),
                CompilerException.class);
        assertThat(unresolved.code()).isEqualTo(CompilerErrorCodes.COMPILER_TOPOLOGY_GRAPH_INVALID);

        // Changing which declared component the reference binds to changes the normalized payload hash of the
        // node, so the binding is genuinely covered by the Manifest and Plan hashes.
        CampaignPlan first = messageBoundPlan("message-one");
        CampaignPlan second = messageBoundPlan("message-two");
        assertThat(planHashOfNode(first, FlowPackVersions.MESSAGE))
                .isNotEqualTo(planHashOfNode(second, FlowPackVersions.MESSAGE));
        assertThat(first.manifestHash()).isNotEqualTo(second.manifestHash());
    }

    // ------------------------------------------------------------------ helpers

    private CampaignPlan messageBoundPlan(String messageClientRef) {
        return compiled(BuilderFixtures.createProposal().pins(catalog(), policy)
                .upsertNew("message.template", "message-one",
                        Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Ada")))
                .upsertNew("message.template", "message-two",
                        Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Grace")))
                .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                .upsertNew(FlowPackVersions.MESSAGE, "n-message",
                        Map.of("messageLogicalId", messageClientRef))
                .upsertNew(FlowPackVersions.END, "n-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .journeyTopology(JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-message", "DEFAULT"),
                        ProposalTopologyEdge.ofDeclaredEndpoints("e2", "n-message", "n-end", "DEFAULT"))))
                .build());
    }

    private static String planHashOfNode(CampaignPlan plan, String capabilityCode) {
        return plan.manifest().component(componentId(plan, capabilityCode)).value().contentHash().value();
    }

    private static String componentId(CampaignPlan plan, String capabilityCode) {
        return plan.manifest().components().stream()
                .filter(component -> component.capabilityCode().equals(capabilityCode))
                .findFirst()
                .orElseThrow()
                .logicalId();
    }

    private CampaignPlan compiled(com.company.campaign.builder.proposal.CampaignProposalPatch patch) {
        return compiler.compile(CompileRequest.create(
                patch, catalog(), policy, BuilderFixtures.instants(), BuilderFixtures.context(),
                CapabilityCompileContext.empty(), packIndex()));
    }

    private com.company.campaign.builder.proposal.CampaignProposalPatch createWithMinimalTopology() {
        return BuilderFixtures.createProposal()
                .pins(catalog(), policy)
                .upsertNew(FlowPackVersions.START, "n-start", Map.of())
                .upsertNew(FlowPackVersions.END, "n-end",
                        Map.of(FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END))
                .journeyTopology(JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints("e1", "n-start", "n-end", "DEFAULT"))))
                .build();
    }
}