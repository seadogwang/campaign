package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 8, 10 and 12: optimistic revisions reject stale calls, a failed acceptance is atomic, one
 * snapshot repeated at least 100 times emits byte-identical Proposal bytes, and dependency inspection reports
 * Reward-without-Governance and Flow references without duplicating final compiler validation.
 */
class CampaignAssemblyWorkspaceTest {

    private static CampaignSkillArtifact messageArtifact(String clientRef) {
        return CampaignAssemblyFixtures.adapter()
                .message(CampaignAssemblyFixtures.messagePreviewAdmission(),
                        ProposalIdentity.ofClientRef(clientRef))
                .orElseThrow();
    }

    private static CampaignSkillArtifact promotionArtifact(String clientRef) {
        return CampaignAssemblyFixtures.adapter()
                .promotion(CampaignAssemblyFixtures.rewardPreviewAdmission(),
                        ProposalIdentity.ofClientRef(clientRef))
                .orElseThrow();
    }

    @Test
    void aStaleExpectedRevisionIsRejectedWithoutChangingState() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(messageArtifact("message-new"), 0).accepted()).isTrue();

        CampaignAssemblySnapshot before = workspace.snapshot();
        CampaignAssemblyAcceptance stale = workspace.accept(promotionArtifact("promotion-new"), 0);

        assertThat(stale.accepted()).isFalse();
        assertThat(stale.code()).isEqualTo(CampaignAssemblyErrorCodes.STALE_REVISION);
        assertThat(workspace.revision()).isEqualTo(1);
        assertThat(workspace.snapshot().snapshotHash()).isEqualTo(before.snapshotHash());
        assertThat(workspace.snapshot().components()).hasSize(1);
    }

    @Test
    void aFailedAcceptanceIsAtomicAndChangesNeitherRevisionNorSnapshot() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(messageArtifact("message-new"), 0).accepted()).isTrue();
        CampaignAssemblySnapshot before = workspace.snapshot();

        // A conflicting identity staged by another Skill must not partially replace the staged component.
        CampaignSkillArtifact conflict = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.PROMOTION_REFERENCE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.REWARD_PREVIEW_SAFE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                ProposalOperation.upsertNew("promotion.reference", "message-new", CapabilityPayload.empty()),
                List.of());
        CampaignAssemblyAcceptance refused = workspace.accept(conflict, 1);

        assertThat(refused.accepted()).isFalse();
        assertThat(refused.code()).isEqualTo(CampaignAssemblyErrorCodes.IDENTITY_CONFLICT);
        assertThat(workspace.revision()).isEqualTo(1);
        assertThat(workspace.snapshot().snapshotHash()).isEqualTo(before.snapshotHash());
        assertThat(workspace.patch().operations()).hasSize(1);
    }

    @Test
    void aDeleteOrMoveOfAnUnstagedIdentityIsRefused() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.editContext(3L));
        CampaignSkillArtifact unresolved = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.MESSAGE_TEMPLATE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.MESSAGE_PREVIEW_SAFE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                ProposalOperation.delete("message-absent"), List.of());

        CampaignAssemblyAcceptance refusal = workspace.accept(unresolved, 0);
        assertThat(refusal.code()).isEqualTo(CampaignAssemblyErrorCodes.UNRESOLVED_TARGET);
        assertThat(workspace.revision()).isZero();
    }

    @Test
    void oneSnapshotRepeatedAtLeastOneHundredTimesEmitsByteIdenticalBytes() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        workspace.accept(messageArtifact("message-new"), 0);
        workspace.accept(promotionArtifact("promotion-new"), 1);

        CampaignProposalPatch first = workspace.patch();
        byte[] firstBytes = first.canonicalBytes();
        for (int index = 0; index < 100; index++) {
            CampaignProposalPatch repeated = workspace.patch();
            assertThat(repeated.canonicalBytes()).isEqualTo(firstBytes);
            assertThat(repeated.proposalHash()).isEqualTo(first.proposalHash());
        }
        assertThat(new String(firstBytes, StandardCharsets.UTF_8)).contains("message.template");
    }

    @Test
    void independentlyBuiltWorkspacesWithTheSameArtifactsEmitTheSameBytes() {
        CampaignAssemblyWorkspace reference =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        reference.accept(messageArtifact("message-new"), 0);
        CampaignProposalPatch expected = reference.patch();

        for (int index = 0; index < 100; index++) {
            CampaignAssemblyWorkspace workspace =
                    new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
            workspace.accept(messageArtifact("message-new"), 0);
            assertThat(workspace.patch().canonicalBytes()).isEqualTo(expected.canonicalBytes());
        }
    }

    @Test
    void aRewardWithoutGovernanceIsReportedAsAMissingDependency() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(promotionArtifact("promotion-new"), 0).accepted()).isTrue();

        assertThat(workspace.snapshot().missingDependencyCodes())
                .containsExactly(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT);
        assertThat(workspace.snapshot().skill(CampaignSkillIds.PROMOTION_REFERENCE).state())
                .isEqualTo(CampaignComponentState.READY);
        assertThat(workspace.snapshot().skill(CampaignSkillIds.JOURNEY_FLOW).state())
                .isEqualTo(CampaignComponentState.MISSING);
    }

    @Test
    void aFlowReferenceToAnUnstagedIdentityIsReportedWithoutCompilerValidation() {
        CampaignStagedComponent flowNode = new CampaignStagedComponent(
                "flow-message-1", CampaignSkillIds.JOURNEY_FLOW, "flow.message",
                ProposalOperation.upsertNew("flow.message", "flow-message-1",
                        CapabilityPayload.of(Map.of("messageLogicalId", "message-new"))),
                List.of(),
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "flow".getBytes(StandardCharsets.UTF_8)));
        CampaignStagedComponent message = new CampaignStagedComponent(
                "message-new", CampaignSkillIds.MESSAGE_TEMPLATE, "message.template",
                ProposalOperation.upsertNew("message.template", "message-new", CapabilityPayload.empty()),
                List.of(),
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "message".getBytes(StandardCharsets.UTF_8)));

        // The reference names a component identity the snapshot does not hold, so the bounded inspection
        // reports it without the compiler ever running; once that identity is staged the reference resolves.
        assertThat(CampaignDependencyInspector.inspect(List.of(flowNode)))
                .containsExactly(CampaignAssemblyErrorCodes.FLOW_REFERENCE_UNRESOLVED);
        assertThat(CampaignDependencyInspector.inspect(List.of(flowNode, message))).isEmpty();
        assertThat(CampaignDependencyInspector.inspect(List.of(message))).isEmpty();
    }

    @Test
    void aBoundedNonReadyObservationIsPreservedForPresentation() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        CampaignAssemblyAcceptance observed = workspace.observe(
                CampaignSkillIds.MESSAGE_TEMPLATE, CampaignComponentState.NEEDS_INPUT,
                "CAMPAIGN_NEEDS_INPUT", 0);

        assertThat(observed.accepted()).isTrue();
        CampaignSkillState state = workspace.snapshot().skill(CampaignSkillIds.MESSAGE_TEMPLATE);
        assertThat(state.state()).isEqualTo(CampaignComponentState.NEEDS_INPUT);
        assertThat(state.code()).isEqualTo("CAMPAIGN_NEEDS_INPUT");
        assertThat(workspace.observe(CampaignSkillIds.MESSAGE_TEMPLATE,
                CampaignComponentState.READY, null, 1).accepted()).isFalse();
    }

    @Test
    void theStagedComponentCountIsBounded() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        int index = 0;
        while (workspace.revision() < CampaignAssemblyWorkspace.MAX_COMPONENTS) {
            CampaignSkillArtifact artifact = CampaignSkillArtifact.of(
                    CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                    CampaignSkillIds.MESSAGE_TEMPLATE, CampaignSkillIds.CURRENT_VERSION,
                    CampaignToolIds.MESSAGE_PREVIEW_SAFE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                    ProposalOperation.upsertNew("message.template", "message-" + index,
                            CapabilityPayload.of(Map.of("value", index))),
                    List.of());
            assertThat(workspace.accept(artifact, workspace.revision()).accepted()).isTrue();
            index++;
        }
        CampaignSkillArtifact overflow = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignSkillIds.MESSAGE_TEMPLATE, CampaignSkillIds.CURRENT_VERSION,
                CampaignToolIds.MESSAGE_PREVIEW_SAFE, "1.0", CampaignToolCatalog.standard().catalogHash(),
                ProposalOperation.upsertNew("message.template", "message-overflow", CapabilityPayload.empty()),
                List.of());
        assertThat(workspace.accept(overflow, workspace.revision()).code())
                .isEqualTo(CampaignAssemblyErrorCodes.COMPONENT_LIMIT_EXCEEDED);
    }

    @Test
    void theInspectionViewCarriesOnlyStateCodesHashesAndMissingDependencyCodes() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        workspace.accept(messageArtifact("message-new"), 0);
        workspace.accept(promotionArtifact("promotion-new"), 1);

        CampaignAssemblyInspection inspection = workspace.inspection();
        assertThat(inspection.revision()).isEqualTo(2);
        assertThat(inspection.components()).hasSize(2);
        assertThat(inspection.components()).extracting(CampaignComponentView::capabilityCode)
                .containsExactly("message.template", "promotion.reference");
        assertThat(inspection.components()).allSatisfy(view -> {
            assertThat(view.artifactHash().value()).matches("sha256:[0-9a-f]{64}");
            assertThat(view.operationKind()).isEqualTo("UPSERT");
        });
        assertThat(inspection.skills()).hasSize(CampaignSkillIds.ordered().size());
        assertThat(inspection.missingDependencyCodes())
                .containsExactly(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT);
        assertThat(inspection.inspectionHash().value()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void theWorkspaceCarriesNoModelOutputAndOnlyBoundedSnapshotFacts() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        workspace.accept(messageArtifact("message-new"), 0);
        CampaignAssemblySnapshot snapshot = workspace.snapshot();

        assertThat(snapshot.components()).hasSize(1);
        assertThat(snapshot.skills()).hasSize(CampaignSkillIds.ordered().size());
        assertThat(snapshot.revision()).isEqualTo(1);
        assertThat(snapshot.snapshotHash().value()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void proposalEmissionAndInspectionAreSideEffectFree() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        workspace.accept(messageArtifact("message-new"), 0);
        long revision = workspace.revision();
        var firstPatch = workspace.patch().canonicalBytes();

        for (int index = 0; index < 50; index++) {
            workspace.patch();
            workspace.inspection();
            workspace.snapshot();
        }
        assertThat(workspace.revision()).isEqualTo(revision);
        assertThat(workspace.patch().canonicalBytes()).isEqualTo(firstPatch);
        assertThat(Disposition.RESOLVED.isResolved()).isTrue();
    }
}
