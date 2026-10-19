package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.proposal.JourneyTopologyMutation;
import com.company.campaign.builder.proposal.ProposalTopologyEdge;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.journey.graph.JourneyBranchKind;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 10: the Journey topology frozen by a saved Draft survives Apply and full publication, and a
 * later, unrelated topology can never be substituted for it.
 */
class FullPublishJourneyTopologyTest {

    @Test
    void theFrozenJourneyTopologySurvivesApplyIntoTheDraftAndFullPublication() {
        CampaignPlan plan = journeyPlan();
        assertThat(plan.journeyTopology()).isNotNull();
        assertThat(plan.manifest().hasJourneyTopology()).isTrue();

        FullPublishScenario scenario = FullPublishScenario.of(plan);
        // The accepted T17 Apply saved exactly this Manifest, so the Draft carries the same frozen topology.
        assertThat(scenario.draft().manifest().journeyTopology()).isEqualTo(plan.journeyTopology());
        assertThat(scenario.draft().manifestHash()).isEqualTo(plan.manifestHash());

        // The full publication re-proves the exact frozen graph and freezes it into the published definition.
        PrepareFullPublishResult prepared = scenario.prepare();
        FullPublishPreparation preparation = scenario.store
                .findFullPreparation(prepared.preparationId())
                .orElseThrow();
        assertThat(preparation.journey().topologyHash()).isEqualTo(plan.journeyTopology().topologyHash());
        assertThat(scenario.publish()).isNotNull();
    }

    @Test
    void anUnrelatedLaterTopologyIsRefusedRatherThanSubstituted() {
        CampaignPlan plan = journeyPlan();
        JourneyTopology substitute = JourneyTopology.of(
                JourneyTopology.TOPOLOGY_FORMAT_VERSION,
                new JourneyTopologyEdge(
                        "edge-substituted",
                        FullPublishTestFixtures.component(plan, FlowPackVersions.END).logicalId(),
                        FullPublishTestFixtures.component(plan, FlowPackVersions.START).logicalId(),
                        JourneyBranchKind.DEFAULT));
        assertThat(substitute).isNotEqualTo(plan.journeyTopology());

        FullPublishScenario scenario = FullPublishScenario.of(plan).withTopology(substitute);
        LifecycleException failure =
                catchThrowableOfType(scenario::prepare, LifecycleException.class);
        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_FULL_JOURNEY_MISMATCH);
    }

    /** A genuine Plan whose Manifest freezes one Start-to-End Journey topology. */
    private static CampaignPlan journeyPlan() {
        com.company.campaign.builder.proposal.CampaignProposalPatch patch = BuilderFixtures.createProposal()
                .pins(FullPublishTestFixtures.catalog(), FullPublishTestFixtures.policy())
                .upsertNew(com.company.campaign.builder.authoring.SegmentAuthoringVersions.CAPABILITY_CODE,
                        "ref-segment", Map.of("carrier", "segment"))
                .upsertNew(FlowPackVersions.START, "ref-start", Map.of())
                .upsertNew(FlowPackVersions.END, "ref-end",
                        Map.of(com.company.campaign.builder.capability.flow.FlowEndPack.TERMINAL_FIELD,
                                com.company.campaign.builder.capability.flow.FlowEndPack.TERMINAL_END))
                .upsertNew(com.company.campaign.builder.capability.schedule.ScheduleBasicVersions
                                .CAPABILITY_CODE, "ref-schedule",
                        Map.of(com.company.campaign.builder.capability.schedule.ScheduleBasicVersions
                                        .FIELD_FORM, "IMMEDIATE",
                                com.company.campaign.builder.capability.schedule.ScheduleBasicVersions
                                        .FIELD_ZONE_ID, "Asia/Shanghai"))
                .journeyTopology(JourneyTopologyMutation.replace(List.of(
                        ProposalTopologyEdge.ofDeclaredEndpoints(
                                "edge-start-end", "ref-start", "ref-end", "DEFAULT"))))
                .build();
        return new CampaignCompiler(SegmentValidationFixtures.allowAll())
                .compile(CompileRequest.create(
                        patch,
                        FullPublishTestFixtures.catalog(),
                        FullPublishTestFixtures.policy(),
                        BuilderFixtures.instants(),
                        BuilderFixtures.context(),
                        CapabilityCompileContext.empty(),
                        FullPublishTestFixtures.index()));
    }
}