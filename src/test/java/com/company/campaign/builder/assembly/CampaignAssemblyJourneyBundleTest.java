package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 4, 8 and 9: the atomic Journey bundle, its replacement semantics and its Proposal projection.
 */
class CampaignAssemblyJourneyBundleTest {

    private static final String START = "journey-start";
    private static final String END = "journey-end";

    /** One accepted Message artifact, so a Journey reference can be satisfied by a sibling Skill. */
    private static CampaignSkillArtifact messageArtifact(String clientRef) {
        return CampaignAssemblyFixtures.adapter()
                .message(CampaignAssemblyFixtures.messagePreviewAdmission(),
                        ProposalIdentity.ofClientRef(clientRef))
                .orElseThrow();
    }

    @Test
    void oneAtomicBundleStagesEveryFlowNodePlusItsTopology() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        CampaignAssemblyAcceptance acceptance = workspace.acceptJourney(bundle(START, END), 0);

        assertThat(acceptance.accepted()).isTrue();
        assertThat(acceptance.revision()).isEqualTo(1);
        assertThat(workspace.journeyTopology()).isNotNull();
        assertThat(workspace.snapshot().skill(CampaignSkillIds.JOURNEY_FLOW).state())
                .isEqualTo(CampaignComponentState.READY);
        assertThat(workspace.inspection().components())
                .extracting(CampaignComponentView::componentId).containsExactly(END, START);
        assertThat(workspace.inspection().components())
                .extracting(CampaignComponentView::capabilityCode)
                .containsExactly("flow.end", "flow.start");

        // The emitted Proposal carries the real Flow operations and the one topology mutation — never a
        // synthetic capability named after the Skill.
        assertThat(workspace.patch().journeyTopology()).isNotNull();
        assertThat(workspace.patch().journeyTopology().replaces()).isTrue();
        assertThat(workspace.patch().operations()).extracting(ProposalOperation::capabilityCode)
                .containsExactly("flow.end", "flow.start");
        assertThat(workspace.patch().canonicalText()).doesNotContain("journey.flow");
    }

    @Test
    void replacingAJourneyRemovesTheObsoleteNodesAndThePreviousTopologyAtomically() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.acceptJourney(bundle(START, END), 0).accepted()).isTrue();
        assertThat(workspace.accept(messageArtifact("message-new"), 1).accepted())
                .isTrue();

        CampaignAssemblyAcceptance replaced = workspace.acceptJourney(bundle("journey-start-2", END), 2);
        assertThat(replaced.accepted()).isTrue();
        assertThat(workspace.inspection().components()).extracting(CampaignComponentView::componentId)
                .containsExactly(END, "journey-start-2", "message-new");
        assertThat(workspace.inspection().components())
                .extracting(CampaignComponentView::skillId)
                .contains(CampaignSkillIds.MESSAGE_TEMPLATE);

        // Repeated identical inputs produce byte-identical Proposal bytes.
        CampaignAssemblyWorkspace twin =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        twin.acceptJourney(bundle(START, END), 0);
        twin.accept(messageArtifact("message-new"), 1);
        twin.acceptJourney(bundle("journey-start-2", END), 2);
        assertThat(twin.patch().canonicalBytes()).isEqualTo(workspace.patch().canonicalBytes());
    }

    @Test
    void staleWrongRunWrongCatalogAndWrongToolBundlesChangeNothing() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.acceptJourney(bundle(START, END), 0).accepted()).isTrue();
        long revision = workspace.revision();
        String snapshotHash = workspace.snapshot().snapshotHash().value();

        CampaignJourneyArtifact stale = bundle(START, END);
        assertThat(workspace.acceptJourney(stale, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.STALE_REVISION);

        CampaignJourneyArtifact wrongRun = CampaignJourneyArtifact.of(
                "conversation-other", CampaignAssemblyFixtures.RUN, CampaignSkillIds.JOURNEY_FLOW,
                CampaignSkillIds.CURRENT_VERSION, CampaignToolIds.JOURNEY_VALIDATE, "1.0",
                CampaignToolCatalog.standard().catalogHash(), stale.operations(), stale.topologyMutation(),
                List.of());
        assertThat(workspace.acceptJourney(wrongRun, revision).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_WRONG_RUN);

        CampaignJourneyArtifact wrongTool = CampaignJourneyArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.JOURNEY_FLOW, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.JOURNEY_COMPOSE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                stale.operations(), stale.topologyMutation(), List.of());
        assertThat(workspace.acceptJourney(wrongTool, revision).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL);

        assertThat(workspace.revision()).isEqualTo(revision);
        assertThat(workspace.snapshot().snapshotHash().value()).isEqualTo(snapshotHash);
    }

    @Test
    void aJourneyBundleNeverOverwritesAnotherSkillsComponent() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(messageArtifact("message-new"), 0).accepted())
                .isTrue();

        CampaignAssemblyAcceptance conflict = workspace.acceptJourney(bundle("message-new", END), 1);
        assertThat(conflict.accepted()).isFalse();
        assertThat(conflict.code()).isEqualTo(CampaignAssemblyErrorCodes.IDENTITY_CONFLICT);
        assertThat(workspace.revision()).isEqualTo(1);
    }

    @Test
    void aSingleComponentJourneyArtifactIsRefused() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        CampaignJourneyArtifact bundle = bundle(START, END);
        CampaignSkillArtifact single = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.JOURNEY_FLOW, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.JOURNEY_VALIDATE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                bundle.operations().get(0), List.of());
        assertThat(workspace.accept(single, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL);
        assertThat(workspace.revision()).isZero();
    }

    @Test
    void aFlowMessageNodeWhoseReferenceIsStagedResolvesTheDependency() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        JourneyTopologyMutation mutation = JourneyTopologyMutation.replace(List.of(
                ProposalTopologyEdge.ofDeclaredEndpoints("e1", START, "journey-message", "DEFAULT"),
                ProposalTopologyEdge.ofDeclaredEndpoints("e2", "journey-message", END, "DEFAULT")));
        CampaignJourneyArtifact bundle = CampaignJourneyArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.JOURNEY_FLOW, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.JOURNEY_VALIDATE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                List.of(
                        ProposalOperation.upsertNew("flow.start", START, CapabilityPayload.empty()),
                        ProposalOperation.upsertNew("flow.message", "journey-message",
                                CapabilityPayload.of(Map.of("messageLogicalId", "message-new"))),
                        ProposalOperation.upsertNew("flow.end", END,
                                CapabilityPayload.of(Map.of("terminal", "END")))),
                mutation, List.of());

        assertThat(workspace.acceptJourney(bundle, 0).accepted()).isTrue();
        assertThat(workspace.snapshot().missingDependencyCodes())
                .containsExactly(CampaignAssemblyErrorCodes.FLOW_REFERENCE_UNRESOLVED);

        assertThat(workspace.accept(messageArtifact("message-new"), 1).accepted())
                .isTrue();
        assertThat(workspace.snapshot().missingDependencyCodes()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    /** One accepted minimal Start-to-End Journey bundle of two real Flow node operations. */
    private static CampaignJourneyArtifact bundle(String startRef, String endRef) {
        return CampaignJourneyArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.JOURNEY_FLOW, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.JOURNEY_VALIDATE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                List.of(
                        ProposalOperation.upsertNew("flow.start", startRef, CapabilityPayload.empty()),
                        ProposalOperation.upsertNew("flow.end", endRef,
                                CapabilityPayload.of(Map.of("terminal", "END")))),
                JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints("e1", startRef, endRef, "DEFAULT"))),
                List.of());
    }
}