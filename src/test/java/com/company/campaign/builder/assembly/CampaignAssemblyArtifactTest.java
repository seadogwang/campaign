package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.proposal.ProposalIdentity;
import com.company.campaign.builder.proposal.ProposalOperation;
import com.company.campaign.builder.proposal.ProposalOperationKind;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 6, 9 and 11: a READY Message or Promotion artifact stages the exact accepted capability and
 * payload, a partial result cannot be accepted, replacement/delete/move reuse the accepted Proposal semantics
 * with deterministic ordering, and a changed Skill, Tool, catalog, artifact or base revision changes the
 * covered hash or is refused.
 */
class CampaignAssemblyArtifactTest {

    private static CampaignSkillArtifact messageArtifact(ProposalIdentity target) {
        return CampaignAssemblyFixtures.adapter()
                .message(CampaignAssemblyFixtures.messagePreviewAdmission(), target)
                .orElseThrow();
    }

    private static CampaignSkillArtifact promotionArtifact(ProposalIdentity target) {
        return CampaignAssemblyFixtures.adapter()
                .promotion(CampaignAssemblyFixtures.rewardPreviewAdmission(), target)
                .orElseThrow();
    }

    private static CampaignSkillArtifact rawArtifact(
            String skillId, String toolId, ProposalOperation operation) {
        return CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                skillId, CampaignSkillIds.CURRENT_VERSION, toolId, "1.0",
                CampaignToolCatalog.standard().catalogHash(), operation, List.of());
    }

    @Test
    void aReadyMessageArtifactStagesTheExactAcceptedCapabilityAndPayload() {
        CampaignSkillArtifact artifact = messageArtifact(ProposalIdentity.ofClientRef(
                CampaignAssemblyFixtures.CLIENT_REF));

        assertThat(artifact.skillId()).isEqualTo("message.template");
        assertThat(artifact.capabilityCode()).isEqualTo("message.template");
        assertThat(artifact.operationKind()).isEqualTo(ProposalOperationKind.UPSERT);
        assertThat(artifact.operation().target().isLogicalId()).isFalse();
        assertThat(artifact.operation().value()).isNotNull();
        assertThat(artifact.references()).hasSize(1);
        assertThat(artifact.references().get(0).referenceCode())
                .isEqualTo(CampaignAssemblyFixtures.TEMPLATE_REFERENCE);

        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        CampaignAssemblyAcceptance acceptance = workspace.accept(artifact, 0);
        assertThat(acceptance.accepted()).isTrue();
        assertThat(workspace.patch().operations()).containsExactly(artifact.operation());
        assertThat(workspace.patch().operations().get(0).capabilityCode()).isEqualTo("message.template");
    }

    @Test
    void aReadyPromotionArtifactStagesTheExactAcceptedCapabilityAndPayload() {
        CampaignSkillArtifact artifact = promotionArtifact(
                ProposalIdentity.ofClientRef(CampaignAssemblyFixtures.PROMOTION_CLIENT_REF));

        assertThat(artifact.capabilityCode()).isEqualTo("promotion.reference");
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(artifact, 0).accepted()).isTrue();
        assertThat(workspace.patch().operations().get(0).capabilityCode()).isEqualTo("promotion.reference");
    }

    @Test
    void aPartialDiscoveryOrResolutionResultCannotBeAccepted() {
        CampaignArtifactAdapter adapter = CampaignAssemblyFixtures.adapter();
        CampaignToolInvoker invoker = CampaignAssemblyFixtures.messageInvoker();
        CampaignToolAdmission discovery = invoker.invoke(CampaignAssemblyFixtures.call(
                "call-1", new CampaignToolRequest.MessageDiscover(null, 8)));

        assertThat(discovery.resolved()).isTrue();
        assertThat(adapter.message(discovery, ProposalIdentity.ofClientRef("message-new"))).isEmpty();

        CampaignToolInvoker second = CampaignAssemblyFixtures.messageInvoker();
        second.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 8)));
        CampaignToolAdmission resolution = second.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        assertThat(adapter.message(resolution, ProposalIdentity.ofClientRef("message-new"))).isEmpty();

        CampaignSkillArtifact forgedPartial = rawArtifact(
                CampaignSkillIds.MESSAGE_TEMPLATE,
                CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE,
                ProposalOperation.upsertNew(
                        CampaignSkillIds.MESSAGE_TEMPLATE,
                        "message-partial",
                        CapabilityPayload.empty()));
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(forgedPartial, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_TOOL_NOT_FINAL);
    }

    @Test
    void replacementDeleteAndMoveReuseTheAcceptedProposalSemantics() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.editContext(7L));

        CampaignSkillArtifact message =
                messageArtifact(ProposalIdentity.ofLogicalId("message-existing"));
        assertThat(workspace.accept(message, 0).accepted()).isTrue();
        CampaignSkillArtifact promotion =
                promotionArtifact(ProposalIdentity.ofLogicalId("promotion-existing"));
        assertThat(workspace.accept(promotion, 1).accepted()).isTrue();

        // Replacement is another upsert of the same identity, never a delete plus a re-create.
        CapabilityPayload replacementPayload = CapabilityPayload.of(Map.of("value", "replaced"));
        CampaignSkillArtifact replacement = rawArtifact("message.template",
                CampaignToolIds.MESSAGE_PREVIEW_SAFE,
                ProposalOperation.upsertExisting("message.template", "message-existing", replacementPayload));
        assertThat(workspace.accept(replacement, 2).accepted()).isTrue();
        assertThat(workspace.patch().operations())
                .filteredOn(operation -> operation.target().value().equals("message-existing"))
                .singleElement()
                .satisfies(operation -> assertThat(operation.value()).isEqualTo(replacementPayload));

        // Operations are emitted in component-identity order, independent of the acceptance order.
        assertThat(workspace.patch().operations())
                .extracting(operation -> operation.target().value())
                .containsExactly("message-existing", "promotion-existing");

        // A MOVE of an existing stable identity carries exactly one relative anchor and no payload.
        CampaignSkillArtifact move = rawArtifact("message.template", CampaignToolIds.MESSAGE_PREVIEW_SAFE,
                ProposalOperation.move(ProposalIdentity.ofLogicalId("message-existing"),
                        MoveAnchorKind.AFTER, "promotion-existing"));
        assertThat(workspace.accept(move, 3).accepted()).isTrue();
        assertThat(workspace.patch().operations())
                .filteredOn(operation -> operation.kind() == ProposalOperationKind.MOVE)
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.value()).isNull();
                    assertThat(operation.capabilityCode()).isNull();
                    assertThat(operation.anchor().target()).isEqualTo("promotion-existing");
                });

        // A DELETE of an existing stable identity carries no payload and no capability.
        CampaignSkillArtifact delete = rawArtifact("promotion.reference",
                CampaignToolIds.REWARD_PREVIEW_SAFE, ProposalOperation.delete("promotion-existing"));
        assertThat(workspace.accept(delete, 4).accepted()).isTrue();
        assertThat(workspace.patch().operations())
                .filteredOn(operation -> operation.kind() == ProposalOperationKind.DELETE)
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.value()).isNull();
                    assertThat(operation.capabilityCode()).isNull();
                });
    }

    @Test
    void anArtifactHashChangesWhenItsBindingChanges() {
        CampaignSkillArtifact base = messageArtifact(ProposalIdentity.ofClientRef("message-new"));
        CampaignSkillArtifact differentRun = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, "run-other",
                base.skillId(), base.skillVersion(), base.toolId(), base.toolVersion(),
                base.toolCatalogHash(), base.operation(), base.references());
        CampaignSkillArtifact differentTool = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                base.skillId(), base.skillVersion(), CampaignToolIds.MESSAGE_TEMPLATE_RESOLVE, "1.0",
                base.toolCatalogHash(), base.operation(), base.references());
        CampaignSkillArtifact differentCatalog = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                base.skillId(), base.skillVersion(), base.toolId(), base.toolVersion(),
                ContentHash.sha256("other-catalog".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                base.operation(), base.references());

        assertThat(base.artifactHash()).isNotEqualTo(differentRun.artifactHash());
        assertThat(base.artifactHash()).isNotEqualTo(differentTool.artifactHash());
        assertThat(base.artifactHash()).isNotEqualTo(differentCatalog.artifactHash());
    }

    @Test
    void aChangedArtifactRunCatalogSkillOrRevisionIsRefused() {
        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        CampaignSkillArtifact base = messageArtifact(ProposalIdentity.ofClientRef("message-new"));

        CampaignSkillArtifact wrongRun = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, "run-other",
                base.skillId(), base.skillVersion(), base.toolId(), base.toolVersion(),
                base.toolCatalogHash(), base.operation(), base.references());
        assertThat(workspace.accept(wrongRun, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_WRONG_RUN);

        CampaignSkillArtifact wrongCatalog = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                base.skillId(), base.skillVersion(), base.toolId(), base.toolVersion(),
                ContentHash.sha256("other-catalog".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                base.operation(), base.references());
        assertThat(workspace.accept(wrongCatalog, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_CATALOG_MISMATCH);

        CampaignSkillArtifact wrongSkill = CampaignSkillArtifact.of(
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                "message.template.renamed", base.skillVersion(), base.toolId(), base.toolVersion(),
                base.toolCatalogHash(), base.operation(), base.references());
        assertThat(workspace.accept(wrongSkill, 0).code())
                .isEqualTo(CampaignAssemblyErrorCodes.ARTIFACT_WRONG_SKILL);

        // The base revision is covered by the emitted Proposal, so a changed base revision changes its hash.
        CampaignAssemblyWorkspace baseSeven =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.editContext(7L));
        CampaignAssemblyWorkspace baseEight =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.editContext(8L));
        assertThat(baseSeven.accept(base, 0).accepted()).isTrue();
        assertThat(baseEight.accept(base, 0).accepted()).isTrue();
        assertThat(baseSeven.patch().proposalHash()).isNotEqualTo(baseEight.patch().proposalHash());
    }
}
