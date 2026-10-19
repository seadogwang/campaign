package com.company.campaign.builder.authoring;

import com.company.campaign.builder.authoring.testsupport.SegmentAuthoringFixtures;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CapabilityCompileContext;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.PlanPreviewCard;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T16 tests for the production {@code segment.rule-based} contract through the accepted T15
 * compiler: a resolved rule-based Segment becomes one Manifest component and exactly one bound
 * {@code SEGMENT_PREVIEW} plan card, unchanged components gain none, and a recompile is byte-identical.
 */
class SegmentRuleBasedCapabilityCompilerTest {

    private final SegmentRuleBasedCapability contract = SegmentAuthoringFixtures.contract();
    private final CampaignCompiler compiler =
            new CampaignCompiler(SegmentValidationFixtures.allowAll());
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract);

    @Test
    void compilingAResolvedSegmentProducesOneComponentAndOneBoundCard() {
        CampaignPlan plan = compiler.compile(createRequest(SegmentAuthoringFixtures.compileContext()));

        String logicalId = plan.clientRefBindings().get(0).logicalId();
        List<ResolvedComponent> components = plan.manifest().components();
        assertThat(components).hasSize(1);
        ResolvedComponent component = components.get(0);
        assertThat(component.logicalId()).isEqualTo(logicalId);
        assertThat(component.capabilityCode()).isEqualTo(SegmentAuthoringVersions.CAPABILITY_CODE);
        assertThat(component.contractVersion()).isEqualTo(SegmentAuthoringVersions.CONTRACT_VERSION);
        assertThat(component.slot().singleton()).isTrue();
        assertThat(component.references()).hasSize(3);

        assertThat(plan.previewCards()).hasSize(1);
        PlanPreviewCard card = plan.previewCards().get(0);
        assertThat(card.cardKind()).isEqualTo(PlanPreviewCard.SEGMENT_PREVIEW);
        assertThat(card.componentLogicalId()).isEqualTo(logicalId);
        assertThat(card.previewHash()).isEqualTo(SegmentAuthoringFixtures.PREVIEW_HASH);
        assertThat(card.readinessState()).isEqualTo("EXPERIMENTAL");
        assertThat(card.gateInputsHash()).isEqualTo(SegmentAuthoringFixtures.GATE_INPUTS_HASH);

        // The Plan hash covers the card: a changed card fact changes the covered hash.
        assertThat(plan.planHash()).isNotNull();
        assertThat(plan.capabilityPins()).extracting(pin -> pin.capabilityCode())
                .containsExactly(SegmentAuthoringVersions.CAPABILITY_CODE);
    }

    @Test
    void recompilingTheSameResolvedSegmentIsByteIdentical() {
        CompileRequest request = createRequest(SegmentAuthoringFixtures.compileContext());
        CampaignPlan first = compiler.compile(request);
        byte[] bytes = first.canonicalMaterialBytes();
        for (int attempt = 0; attempt < 15; attempt++) {
            CampaignPlan candidate = compiler.compile(request);
            assertThat(candidate.planHash()).isEqualTo(first.planHash());
            assertThat(candidate.manifestHash()).isEqualTo(first.manifestHash());
            assertThat(candidate.canonicalMaterialBytes()).isEqualTo(bytes);
            assertThat(candidate.previewCards()).isEqualTo(first.previewCards());
        }
    }

    @Test
    void aDeletedSegmentContributesNoCard() {
        // A create-then-delete compile leaves no component and therefore no card.
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, SegmentAuthoringFixtures.CLIENT_REF, SegmentAuthoringFixtures.envelope())
                .build();
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.compileContext()));
        assertThat(plan.previewCards()).hasSize(1);

        CampaignPlan empty = compiler.compile(CompileRequest.create(
                BuilderFixtures.createProposal().pins(catalog, policy).build(),
                catalog, policy, BuilderFixtures.instants(), SegmentAuthoringFixtures.context(),
                SegmentAuthoringFixtures.compileContext()));
        assertThat(empty.manifest().components()).isEmpty();
        assertThat(empty.previewCards()).isEmpty();
    }

    @Test
    void aCompileWithNoPinnedReadinessOrInputsFailsClosedThroughTheCompiler() {
        // The compiler reports one bounded contract-invalid code; it never leaks the rejected payload.
        assertThatThrownBy(() -> compiler.compile(createRequest(SegmentAuthoringFixtures.withoutReadiness())))
                .isInstanceOf(CompilerException.class)
                .hasMessageNotContaining("member.tier");
        assertThatThrownBy(() -> compiler.compile(createRequest(SegmentAuthoringFixtures.withoutInputs())))
                .isInstanceOf(CompilerException.class);
        assertThatThrownBy(() -> compiler.compile(createRequest(
                SegmentAuthoringFixtures.compileContext(
                        SegmentAuthoringFixtures.experimental(),
                        SegmentAuthoringFixtures.inputs(
                                SegmentAuthoringFixtures.snapshot(),
                                SegmentAuthoringFixtures.cardBuilder().previewHash(
                                        com.company.campaign.shared.canonical.ContentHash.sha256(
                                                "other".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                                        .build(),
                                SegmentAuthoringFixtures.SKILL_HASH)))))
                .isInstanceOf(CompilerException.class);
    }

    private CompileRequest createRequest(CapabilityCompileContext compileContext) {
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, SegmentAuthoringFixtures.CLIENT_REF, SegmentAuthoringFixtures.envelope())
                .build();
        return CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(),
                SegmentAuthoringFixtures.context(), compileContext);
    }
}