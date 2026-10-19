package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.authoring.SegmentAuthoringIntegration;
import com.company.campaign.builder.authoring.SegmentAuthoringProof;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 7: an accepted Segment {@code READY_FOR_PREVIEW} result can be adapted, and every other Segment
 * terminal disposition cannot be staged while preserving its status and questions.
 */
class CampaignAssemblySegmentAdapterTest {

    private static CampaignSegmentAdapter adapter() {
        return new CampaignSegmentAdapter(
                new SegmentAuthoringIntegration(new CampaignCompiler(new RecordingAuthorizationService())),
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN,
                CampaignToolCatalog.standard());
    }

    @Test
    void aReadySegmentResultIsAdaptedIntoTheExactAcceptedArtifact() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAgentResult ready = SegmentAuthoringIntegrationFixtures.agentResult(proof);
        SegmentAuthoringSource source = SegmentAuthoringIntegrationFixtures.ai(proof);
        SegmentAuthoringRequest request = SegmentAuthoringIntegrationFixtures.createRequest(source);

        CampaignSegmentStaging staging = adapter().stageResult(ready, proof, request);
        assertThat(staging).isInstanceOf(CampaignSegmentStaging.Staged.class);
        CampaignSkillArtifact artifact = ((CampaignSegmentStaging.Staged) staging).artifact();
        assertThat(artifact.skillId()).isEqualTo("segment.rule-based");
        assertThat(artifact.capabilityCode()).isEqualTo("segment.rule-based");
        assertThat(artifact.operation().value()).isNotNull();
        assertThat(artifact.operation().value().isEmpty()).isFalse();

        CampaignAssemblyWorkspace workspace =
                new CampaignAssemblyWorkspace(CampaignAssemblyFixtures.createContext());
        assertThat(workspace.accept(artifact, 0).accepted()).isTrue();
        assertThat(workspace.patch().operations().get(0).capabilityCode()).isEqualTo("segment.rule-based");
        assertThat(workspace.snapshot().skill("segment.rule-based").state())
                .isEqualTo(CampaignComponentState.READY);
    }

    @Test
    void everySegmentTerminalDispositionIsNonAcceptableAndPreservesItsStatus() {
        for (Disposition disposition : new Disposition[]{
                Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED, Disposition.INVALID}) {
            SegmentAgentResult terminal = SegmentAuthoringIntegrationFixtures.terminalAgentResult(disposition);
            CampaignSegmentStaging staging = adapter().stageTerminalResult(terminal);

            assertThat(staging).as(disposition.name())
                    .isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
            CampaignSegmentStaging.NotAcceptable notAcceptable =
                    (CampaignSegmentStaging.NotAcceptable) staging;
            assertThat(notAcceptable.disposition()).as(disposition.name()).isEqualTo(disposition);
            assertThat(notAcceptable.code()).as(disposition.name()).isEqualTo("SEGMENT_" + disposition.name());
            if (disposition == Disposition.NEEDS_INPUT) {
                assertThat(notAcceptable.clarification()).isNotNull();
            } else {
                assertThat(notAcceptable.clarification()).as(disposition.name()).isNull();
            }
        }
    }

    @Test
    void aTerminalSegmentSourceIsRefusedByTheIntegrationBeforeAnyPayloadExists() {
        for (Disposition disposition : new Disposition[]{
                Disposition.NEEDS_INPUT, Disposition.UNSUPPORTED, Disposition.DENIED, Disposition.INVALID}) {
            SegmentAuthoringSource source = SegmentAuthoringIntegrationFixtures.terminalAi(disposition);
            SegmentAuthoringRequest request = SegmentAuthoringIntegrationFixtures.createRequest(source);
            CampaignSegmentStaging staging = adapter().stage(source, request);

            assertThat(staging).as(disposition.name())
                    .isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        }
    }

    @Test
    void aReadyResultWithoutItsProofIsRefusedRatherThanStaged() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAgentResult ready = SegmentAuthoringIntegrationFixtures.agentResult(proof);
        CampaignSegmentStaging staging = adapter().stageResult(ready, null, null);
        assertThat(staging).isInstanceOf(CampaignSegmentStaging.Refused.class);
    }

    @Test
    void theExplicitSourceIsBoundIntoTheAuthoringRequest() {
        SegmentAuthoringProof proof = SegmentAuthoringIntegrationFixtures.proof();
        SegmentAuthoringSource ready = SegmentAuthoringIntegrationFixtures.ai(proof);
        SegmentAuthoringRequest mismatched = SegmentAuthoringIntegrationFixtures.createRequest(ready);

        CampaignSegmentStaging staging = adapter().stage(
                SegmentAuthoringIntegrationFixtures.terminalAi(Disposition.DENIED),
                mismatched);

        assertThat(staging).isInstanceOf(CampaignSegmentStaging.NotAcceptable.class);
        assertThat(((CampaignSegmentStaging.NotAcceptable) staging).disposition())
                .isEqualTo(Disposition.DENIED);
    }
}
