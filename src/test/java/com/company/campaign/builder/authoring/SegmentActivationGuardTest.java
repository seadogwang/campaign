package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringIntegrationFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.evaluation.segment.gate.CapabilityState;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T16 requirement: the fail-closed production activation guard.
 *
 * <p>It exists only as the narrow port a later T17/T21/T22 Apply/Publish/Runtime path asks before it consumes
 * a compiled Segment Plan. It performs no Apply, no Publish, no Save and no Runtime work, and it accepts
 * exactly one thing: an exactly {@code ACTIVE}, {@code aiReady} T14 projection whose approved decision and
 * current Gate inputs match the compiled segment Plan's own card.
 */
class SegmentActivationGuardTest {

    private static final ContentHash OTHER_INPUTS_HASH =
            ContentHash.sha256("other-gate-inputs".getBytes(StandardCharsets.UTF_8));
    private static final ContentHash OTHER_DECISION_HASH =
            ContentHash.sha256("other-approved-decision".getBytes(StandardCharsets.UTF_8));

    private final SegmentAuthoringIntegration integration = new SegmentAuthoringIntegration(
            new CampaignCompiler(SegmentValidationFixtures.allowAll()));

    // ------------------------------------------------------------------ the one accepted case

    @Test
    void anExactActiveReadinessAndItsOwnCompiledPlanPassTheGuard() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());

        assertThatCode(() -> SegmentActivationGuard.requireActivatable(activeReadiness(), plan))
                .doesNotThrowAnyException();
        assertThat(SegmentActivationGuard.isActivatable(activeReadiness(), plan)).isTrue();
    }

    @Test
    void thePlanCardHonestlyRecordsTheActiveReadinessItWasCompiledUnder() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());
        PlanPreviewCard card = plan.previewCards().get(0);

        assertThat(card.readinessState()).isEqualTo(CapabilityReadiness.ACTIVE);
        assertThat(card.gateInputsHash()).isEqualTo(SegmentAuthoringFixtures.GATE_INPUTS_HASH);
        assertThat(card.approvedDecisionHash()).isEqualTo(SegmentAuthoringFixtures.APPROVED_DECISION_HASH);
        assertThat(card.activeReadiness()).isTrue();
    }

    // ------------------------------------------------------------------ every rejection

    @Test
    void everyNonActiveProjectionIsRejected() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());

        SegmentReadinessProjection experimental = SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.EXPERIMENTAL, false,
                List.of("APPROVAL_MISSING"), null, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
        SegmentReadinessProjection evaluationRequired = SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.EVALUATION_REQUIRED, false,
                List.of("GATED_INPUT_CHANGED"), SegmentAuthoringFixtures.APPROVED_DECISION_HASH,
                SegmentAuthoringFixtures.GATE_INPUTS_HASH);

        assertRejected(experimental, plan);
        assertRejected(evaluationRequired, plan);
        assertThat(SegmentActivationGuard.isActivatable(experimental, plan)).isFalse();
        assertThat(SegmentActivationGuard.isActivatable(evaluationRequired, plan)).isFalse();
    }

    @Test
    void developmentEvidenceIsNeverRelabelledProductionReady() {
        // The plan was compiled honestly under EXPERIMENTAL readiness: its card says so, and an ACTIVE
        // projection arriving later must not turn that development artifact into a production one.
        CampaignPlan developmentPlan = compiledUnder(SegmentAuthoringFixtures.experimental());
        assertThat(developmentPlan.previewCards().get(0).readinessState())
                .isEqualTo(CapabilityReadiness.EXPERIMENTAL);

        assertRejected(activeReadiness(), developmentPlan);
    }

    @Test
    void staleGateInputsAndADifferentApprovedDecisionAreRejected() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());

        SegmentReadinessProjection staleInputs = SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                SegmentAuthoringFixtures.APPROVED_DECISION_HASH, OTHER_INPUTS_HASH);
        assertRejected(staleInputs, plan);

        SegmentReadinessProjection otherDecision = SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                OTHER_DECISION_HASH, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
        assertRejected(otherDecision, plan);
    }

    @Test
    void aProjectionForAnotherCapabilityIsRejected() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());
        SegmentReadinessProjection foreign = SegmentReadinessProjection.of(
                "message.template", CapabilityState.EXPERIMENTAL, false, List.of("CAPABILITY_NOT_GATED"),
                null, SegmentAuthoringFixtures.GATE_INPUTS_HASH);

        assertRejected(foreign, plan);
    }

    @Test
    void aPlanThatCarriesNoSegmentCardIsRejected() {
        // Exactly one bound segment card is required: a plan that carries none never activates a Segment.
        CampaignPlan withoutSegment = nonSegmentPlan();
        assertThat(withoutSegment.previewCards()).isEmpty();

        assertRejected(activeReadiness(), withoutSegment);
        assertThat(SegmentActivationGuard.isActivatable(activeReadiness(), withoutSegment)).isFalse();
    }

    @Test
    void aForeignCapabilityThatForgesASegmentPreviewCardIsRejected() {
        // Any generic CapabilityContract can return a public PlanPreviewCard of kind SEGMENT_PREVIEW with
        // matching readiness and Gate pins, so the card kind alone proves nothing. The guard resolves the
        // card's component in the Manifest and refuses a foreign capability's forged card.
        CampaignPlan forged = forgedForeignSegmentCardPlan();
        assertThat(forged.previewCards()).hasSize(1);
        assertThat(forged.previewCards().get(0).cardKind()).isEqualTo(PlanPreviewCard.SEGMENT_PREVIEW);
        assertThat(forged.previewCards().get(0).readinessState()).isEqualTo(CapabilityReadiness.ACTIVE);
        assertThat(forged.previewCards().get(0).gateInputsHash())
                .isEqualTo(SegmentAuthoringFixtures.GATE_INPUTS_HASH);

        assertRejected(activeReadiness(), forged);
        assertThat(SegmentActivationGuard.isActivatable(activeReadiness(), forged)).isFalse();
    }

    @Test
    void malformedOrMissingEvidenceIsRejected() {
        CampaignPlan plan = compiledUnder(SegmentAuthoringFixtures.active());

        assertThatThrownBy(() -> SegmentActivationGuard.requireActivatable(null, plan))
                .isInstanceOf(SegmentAuthoringException.class)
                .satisfies(failure -> assertThat(((SegmentAuthoringException) failure).code())
                        .isEqualTo(SegmentAuthoringErrorCodes.ACTIVATION_REJECTED));
        assertThatThrownBy(() -> SegmentActivationGuard.requireActivatable(activeReadiness(), null))
                .isInstanceOf(SegmentAuthoringException.class);
        assertThat(SegmentActivationGuard.isActivatable(null, plan)).isFalse();
        assertThat(SegmentActivationGuard.isActivatable(activeReadiness(), null)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private CampaignPlan compiledUnder(CapabilityReadiness readiness) {
        return ((SegmentAuthoringResult.Compiled) integration.author(
                SegmentAuthoringIntegrationFixtures.createRequest(
                        SegmentAuthoringIntegrationFixtures.visual(
                                SegmentAuthoringIntegrationFixtures.proof()),
                        readiness))).plan();
    }

    /** A genuinely compiled plan that carries no rule-based Segment at all. */
    private CampaignPlan nonSegmentPlan() {
        CampaignCompiler compiler = new CampaignCompiler(SegmentValidationFixtures.allowAll());
        return compiler.compile(com.company.campaign.builder.compiler.CompileRequest.create(
                com.company.campaign.builder.testsupport.BuilderFixtures.createProposal()
                        .pins(com.company.campaign.builder.testsupport.BuilderFixtures.catalog(),
                                com.company.campaign.builder.testsupport.BuilderFixtures.policy())
                        .build(),
                com.company.campaign.builder.testsupport.BuilderFixtures.catalog(),
                com.company.campaign.builder.testsupport.BuilderFixtures.policy(),
                com.company.campaign.builder.testsupport.BuilderFixtures.instants(),
                SegmentAuthoringFixtures.context()));
    }

    /**
     * A genuinely compiled plan of a <em>foreign</em> capability that returns a forged {@code SEGMENT_PREVIEW}
     * card bound to its own component, with readiness and Gate pins identical to the exact ACTIVE projection.
     */
    private CampaignPlan forgedForeignSegmentCardPlan() {
        String capability = "message.template";
        var reference = SegmentAuthoringFixtures.context().snapshotRef();
        FixtureCapability foreign = new FixtureCapability(
                capability, "1.0", FixtureCapability.unorderedMulti("slot-message"), true,
                FixtureCapability.runtime(),
                request -> new CapabilityResolution.Resolved(
                        request.payload(), List.of(), List.of(new PlanPreviewCard(
                                PlanPreviewCard.SEGMENT_PREVIEW,
                                request.logicalId(),
                                7,
                                List.of(),
                                null,
                                reference.metadataSnapshotId(),
                                reference.asOf(),
                                reference.timezone().getId(),
                                reference.dataWatermark(),
                                SegmentAuthoringFixtures.PREVIEW_HASH,
                                SegmentAuthoringFixtures.PREVIEW_HASH,
                                SegmentAuthoringFixtures.PREVIEW_HASH,
                                SegmentAuthoringFixtures.PREVIEW_HASH,
                                SegmentAuthoringFixtures.snapshot().contentHash(),
                                SegmentAuthoringFixtures.SKILL_HASH,
                                CapabilityReadiness.ACTIVE,
                                SegmentAuthoringFixtures.GATE_INPUTS_HASH,
                                SegmentAuthoringFixtures.APPROVED_DECISION_HASH))));
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(foreign);
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, BuilderFixtures.policy())
                .upsertNew(capability, "message-client-ref-1", BuilderFixtures.value("label", "welcome"))
                .build();
        return new CampaignCompiler(SegmentValidationFixtures.allowAll()).compile(
                com.company.campaign.builder.compiler.CompileRequest.create(
                        patch, catalog, BuilderFixtures.policy(), BuilderFixtures.instants(),
                        SegmentAuthoringFixtures.context()));
    }

    private static SegmentReadinessProjection activeReadiness() {
        return SegmentReadinessProjection.of(
                SegmentAuthoringVersions.CAPABILITY_CODE, CapabilityState.ACTIVE, true, List.of(),
                SegmentAuthoringFixtures.APPROVED_DECISION_HASH, SegmentAuthoringFixtures.GATE_INPUTS_HASH);
    }

    private static void assertRejected(SegmentReadinessProjection readiness, CampaignPlan plan) {
        assertThatThrownBy(() -> SegmentActivationGuard.requireActivatable(readiness, plan))
                .isInstanceOf(SegmentAuthoringException.class)
                .satisfies(failure -> assertThat(((SegmentAuthoringException) failure).code())
                        .isEqualTo(SegmentAuthoringErrorCodes.ACTIVATION_REJECTED));
    }
}