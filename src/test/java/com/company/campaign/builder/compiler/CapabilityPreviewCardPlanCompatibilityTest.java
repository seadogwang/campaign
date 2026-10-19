package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.CapabilityPreviewCardFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 section 2: the capability preview card is <em>additive</em>.
 *
 * <p>A Plan that carries no T19 card must keep the accepted T15/T16/T18 material bytes, plan hash and equality
 * exactly: the empty card list is omitted from the canonical material entirely rather than serialized as an
 * empty array, so the accepted no-card golden bytes and hash still hold. A Plan that does carry cards covers
 * them in the canonical material and hash, and the accepted Segment {@code PlanPreviewCard} path keeps its own
 * bytes and behavior untouched.
 */
class CapabilityPreviewCardPlanCompatibilityTest {

    private static final String VERSION = "segment-rule-based-1.0";
    private static final CompositionSlot SLOT = FixtureCapability.ordered("segment");
    private static final RuntimeCompatibility RUNTIME = FixtureCapability.runtime();

    /**
     * The canonical UTF-8 material bytes a CREATE compile with one plain component produced before the
     * capability preview card path existed. A no-card plan must still serialize to exactly these bytes.
     */
    private static final String ACCEPTED_NO_CARD_MATERIAL = """
            {"baseCampaignRevision":null,"baseDraftId":null,"baseDraftRevision":null,"campaignId":null,"capabilityPins":[{"capabilityCode":"segment.rule-based","contractVersion":"segment-rule-based-1.0"}],"catalogHash":"sha256:4994c858df457f47c5a569bcbebbce21820d526af1dac871cc371f804a239abd","catalogVersion":"catalog-31","changeSetFormatVersion":"1.0","changeSetNodes":[{"capabilityCode":"segment.rule-based","contractVersion":"segment-rule-based-1.0","logicalId":"f4b0de333742d7508b13b9be029fc752c3ddbd8cce88cf9fc944566d655bf13f","references":[],"slot":{"code":"segment","ordered":true,"singleton":false},"value":{"spec":"a"}}],"clientRefBindings":[{"clientRef":"segment-new","logicalId":"f4b0de333742d7508b13b9be029fc752c3ddbd8cce88cf9fc944566d655bf13f"}],"compiledAt":"2026-10-03T00:00:00Z","compilerVersion":"campaign-proposal-compiler/1.0","diffEntries":[{"capabilityCode":"segment.rule-based","fieldPaths":["component"],"kind":"ADD","logicalId":"f4b0de333742d7508b13b9be029fc752c3ddbd8cce88cf9fc944566d655bf13f"}],"expiresAt":"2026-10-03T00:15:00Z","forkBase":null,"manifestHash":"sha256:5dfeaea610499cf0791e2b19df82afb583c8346c2a123b2b12bdd8202918d02d","planFormatVersion":"1.0","policyHash":"sha256:b8972c76ca9bc336de9ac82bcf394e57e16de24612f714628334bddbd7edb58f","policyVersion":"policy-8","previewCards":[],"programId":"program-001","proposalHash":"sha256:ab1c74248831602f12506ea036b2a8fda246f3c65f75e66eb6449ff98d753e09","riskSummary":{"riskCodes":[]},"tenantId":"tenant-001","userId":"user-001","validationIssues":[]}
            """.strip();

    /** The accepted plan hash of the same no-card compile. */
    private static final String ACCEPTED_NO_CARD_HASH =
            "sha256:9a84569a52884a64fc7660c0cff959f91198d6debc63ddba10c21aeeb8b5f692";

    private final CampaignCompiler compiler = new CampaignCompiler(new RecordingAuthorizationService());
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    // ------------------------------------------------------------------ the accepted no-card bytes

    @Test
    void aPlanWithoutCapabilityPreviewCardsKeepsTheAcceptedMaterialBytesAndHash() {
        CampaignPlan plan = compile(BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT)));

        assertThat(new String(plan.canonicalMaterialBytes(), StandardCharsets.UTF_8))
                .isEqualTo(ACCEPTED_NO_CARD_MATERIAL);
        assertThat(plan.planHash().value()).isEqualTo(ACCEPTED_NO_CARD_HASH);
        assertThat(plan.capabilityPreviewCards()).isEmpty();
    }

    @Test
    void theEmptyCapabilityPreviewCardListIsOmittedFromTheCanonicalMaterial() {
        CampaignPlan plan = compile(BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT)));

        // The optional slot is genuinely absent, exactly like the accepted optional preview evidence slot:
        // an empty T19 card list must never appear as an empty array in the plan material.
        assertThat(new String(plan.canonicalMaterialBytes(), StandardCharsets.UTF_8))
                .doesNotContain("capabilityPreviewCards")
                .contains("\"previewCards\":[]");
    }

    @Test
    void aPlanWithAndWithoutCapabilityPreviewCardsStaysDeterministic() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT));

        CampaignPlan first = compile(catalog);
        CampaignPlan second = compile(catalog);

        assertThat(second.canonicalMaterialBytes()).isEqualTo(first.canonicalMaterialBytes());
        assertThat(second.planHash()).isEqualTo(first.planHash());
        assertThat(second).isEqualTo(first).hasSameHashCodeAs(first);
    }

    // ------------------------------------------------------------------ the additive non-empty case

    @Test
    void aPlanCarryingACapabilityPreviewCardCoversItInTheCanonicalMaterialAndHash() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honest());
        CampaignPlan plan = compile(catalog);

        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        assertThat(new String(plan.canonicalMaterialBytes(), StandardCharsets.UTF_8))
                .contains("\"capabilityPreviewCards\":[")
                .contains(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW)
                // The evaluation asset hash is a card-only field, so its presence proves the card itself,
                // and not merely the component, reached the material.
                .contains("\"evaluationAssetHash\"");
        // The card is genuinely part of the material, so it changes the plan hash.
        assertThat(plan.planHash().value()).isNotEqualTo(ACCEPTED_NO_CARD_HASH);

        CampaignPlan again = compile(catalog);
        assertThat(again.canonicalMaterialBytes()).isEqualTo(plan.canonicalMaterialBytes());
        assertThat(again).isEqualTo(plan).hasSameHashCodeAs(plan);
    }

    @Test
    void twoPlansThatDifferOnlyByTheirCapabilityPreviewCardsAreNotEqual() {
        CampaignPlan plain = compile(BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT)));
        CampaignPlan carded = compile(BuilderFixtures.catalog(honest()));

        assertThat(carded.planHash()).isNotEqualTo(plain.planHash());
        assertThat(carded).isNotEqualTo(plain);
        assertThat(carded.manifestHash()).isEqualTo(plain.manifestHash());
    }

    // ------------------------------------------------------------------ the accepted Segment card path

    @Test
    void theAcceptedSegmentPreviewCardPathKeepsItsOwnBytesAndBehaviour() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(new FixtureCapability(
                SEGMENT, VERSION, SLOT, true, RUNTIME, request -> new CapabilityResolution.Resolved(
                        request.payload(), List.of(), List.of(acceptedSegmentCard(request.logicalId())), List.of())));
        CampaignPlan plan = compile(catalog);

        assertThat(plan.previewCards()).hasSize(1);
        assertThat(plan.previewCards().get(0).cardKind()).isEqualTo(PlanPreviewCard.SEGMENT_PREVIEW);
        assertThat(plan.previewCards().get(0).componentLogicalId())
                .isEqualTo(plan.manifest().components().get(0).logicalId());
        // A plan that contributes only the accepted card gains no T19 card and no new material key.
        assertThat(plan.capabilityPreviewCards()).isEmpty();
        assertThat(new String(plan.canonicalMaterialBytes(), StandardCharsets.UTF_8))
                .doesNotContain("capabilityPreviewCards");
    }

    // ------------------------------------------------------------------ helpers

    private static FixtureCapability honest() {
        return new FixtureCapability(SEGMENT, VERSION, SLOT, true, RUNTIME, request -> {
            ResolvedComponent component = new ResolvedComponent(
                    request.logicalId(), SEGMENT, VERSION, SLOT, request.payload(), List.of());
            return new CapabilityResolution.Resolved(request.payload(), List.of(), List.of(),
                    List.of(CapabilityPreviewCardFixtures.bound(component, RUNTIME)));
        });
    }

    /** The accepted Segment projection, built through the accepted reference-free constructor. */
    private static PlanPreviewCard acceptedSegmentCard(String componentLogicalId) {
        return new PlanPreviewCard(
                PlanPreviewCard.SEGMENT_PREVIEW,
                componentLogicalId,
                0,
                List.of(),
                null,
                "snapshot-001",
                BuilderFixtures.COMPILED_AT,
                "Asia/Shanghai",
                BuilderFixtures.COMPILED_AT.minusSeconds(60),
                hash("dsl"), hash("ast"), hash("semantic"), hash("preview"), hash("metadata"), hash("skill"),
                CapabilityReadiness.EVALUATION_REQUIRED,
                hash("gate"),
                null);
    }

    private CampaignPlan compile(FrozenCapabilityCatalog catalog) {
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();
        return compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private static ContentHash hash(String value) {
        return ContentHash.sha256(value.getBytes(StandardCharsets.UTF_8));
    }
}