package com.company.campaign.builder.compiler;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.proposal.MoveAnchorKind;
import com.company.campaign.builder.proposal.ProposalMode;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.CapabilityPreviewCardFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T19 section 2: the generic capability preview card path through the accepted compiler.
 *
 * <p>A contract may contribute safe capability preview cards alongside its normalized component. The compiler
 * aggregates them in manifest order, re-proves that every card binds exactly the component it resolved — stable
 * identity, capability code, exact contract version, normalized payload hash, canonical resolved-reference hash
 * and frozen runtime compatibility — and fails the whole compile closed on a null, repeated, forged, stale,
 * deleted or replaced card rather than publishing a partially consistent Plan.
 */
class CapabilityPreviewCardCompilerTest {

    private static final String VERSION = "segment-rule-based-1.0";
    private static final CompositionSlot SLOT = FixtureCapability.ordered("segment");
    private static final RuntimeCompatibility RUNTIME = FixtureCapability.runtime();
    private static final String MESSAGE = "message.template";
    private static final String MESSAGE_VERSION = "message-template-1.0";
    private static final CompositionSlot MESSAGE_SLOT = FixtureCapability.unorderedMulti("message");

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    // ------------------------------------------------------------------ the accepted happy path

    @Test
    void aContributedCapabilityPreviewCardReachesThePlanBoundToItsComponent() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honestSegment(List.of()));
        CampaignPlan plan = compile(catalog, createPatch(catalog, Map.of("spec", "a")));

        ResolvedComponent component = plan.manifest().components().get(0);
        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        CapabilityPlanPreviewCard card = plan.capabilityPreviewCards().get(0);
        assertThat(card.componentLogicalId()).isEqualTo(component.logicalId());
        assertThat(card.capabilityCode()).isEqualTo(SEGMENT);
        assertThat(card.contractVersion()).isEqualTo(VERSION);
        assertThat(card.normalizedPayloadHash()).isEqualTo(component.value().contentHash());
        assertThat(card.resolvedCatalogReferenceHash()).isNull();
        assertThat(card.runtimeCompatibility()).isEqualTo(RUNTIME);
        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.cardContractVersion()).isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        // The card is exactly the honest projection of the component the manifest holds.
        assertThat(card).isEqualTo(CapabilityPreviewCardFixtures.bound(component, RUNTIME));
        // The accepted Segment card path is untouched: nothing here contributes one.
        assertThat(plan.previewCards()).isEmpty();
    }

    @Test
    void aCardBindsTheCanonicalResolvedReferenceHashOfAReferenceResolvingComponent() {
        ResolvedCatalogReference reference = BuilderFixtures.reference("tpl-1", "TEMPLATE", "tpl-a", "tpl-a-v1");
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honestSegment(List.of(reference)));
        CampaignPlan plan = compile(catalog, createPatch(catalog, Map.of("spec", "a")));

        ResolvedComponent component = plan.manifest().components().get(0);
        assertThat(component.references()).containsExactly(reference);
        CapabilityPlanPreviewCard card = plan.capabilityPreviewCards().get(0);
        assertThat(card.resolvedCatalogReferenceHash())
                .isEqualTo(CapabilityPreviewCards.canonicalReferenceHash(List.of(reference)))
                .isNotNull();
        assertThat(card.hasResolvedCatalogReference()).isTrue();
    }

    @Test
    void capabilityPreviewCardsFollowTheFinalManifestOrder() {
        CompositionSlot slot = FixtureCapability.unorderedMulti("segment");
        FixtureCapability contract = honest(SEGMENT, VERSION, slot, List.of());
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract);
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "one", Map.of("spec", "a"))
                .upsertNew(SEGMENT, "two", Map.of("spec", "b"))
                .upsertNew(SEGMENT, "three", Map.of("spec", "c"))
                .moveRef("one", MoveAnchorKind.AFTER, "three")
                .build();
        CampaignPlan plan = compile(catalog, patch);

        List<String> componentOrder = plan.manifest().components().stream()
                .map(ResolvedComponent::logicalId).toList();
        assertThat(componentOrder).hasSize(3);
        assertThat(plan.capabilityPreviewCards().stream()
                .map(CapabilityPlanPreviewCard::componentLogicalId).toList())
                .isEqualTo(componentOrder);
    }

    @Test
    void thePlanCarriesAnImmutableCapabilityPreviewCardList() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honestSegment(List.of()));
        CampaignPlan plan = compile(catalog, createPatch(catalog, Map.of("spec", "a")));

        List<CapabilityPlanPreviewCard> cards = plan.capabilityPreviewCards();
        assertThat(cards).hasSize(1);
        assertThatThrownBy(() -> cards.add(cards.get(0))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(cards::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anOrdinaryContractStillContributesNoCapabilityPreviewCard() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT));
        CampaignPlan plan = compile(catalog, createPatch(catalog, Map.of("spec", "a")));

        assertThat(plan.capabilityPreviewCards()).isEmpty();
        assertThat(plan.manifest().components()).hasSize(1);
    }

    // ------------------------------------------------------------------ fail-closed: forged cards

    @Test
    void aCardThatDoesNotBindItsResolvedComponentFailsClosed() {
        ContentHash other = CapabilityPreviewCardFixtures.hash("other");
        RuntimeCompatibility otherRuntime = new RuntimeCompatibility("campaign-runtime/2.0", true, true);

        List<Forge> forgeries = List.of(
                new Forge("capabilityCardBindingComponent",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .componentLogicalId("component-other").build()),
                new Forge("capabilityCardBindingCapabilityCode",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .capabilityCode(MESSAGE).build()),
                new Forge("capabilityCardBindingContractVersion",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .contractVersion("segment-rule-based-2.0").build()),
                new Forge("capabilityCardBindingPayloadHash",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .normalizedPayloadHash(other).build()),
                new Forge("capabilityCardBindingReferenceHash",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .resolvedCatalogReferenceHash(other).build()),
                new Forge("capabilityCardBindingRuntime",
                        component -> CapabilityPreviewCardFixtures.builder().bind(component)
                                .runtime(otherRuntime).build()));

        for (Forge forge : forgeries) {
            FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract(
                    SEGMENT, VERSION, SLOT, List.of(),
                    request -> List.of(forge.card().apply(
                            resolved(request, SEGMENT, VERSION, SLOT, List.of())))));
            ContractException failure = catchThrowableOfType(
                    () -> compile(catalog, createPatch(catalog, Map.of("spec", "a"))), ContractException.class);
            assertThat(failure).as("forgery %s", forge.invariant()).isNotNull();
            assertThat(failure.code())
                    .as("forgery %s", forge.invariant())
                    .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
            assertThat(failure.details()).containsEntry("invariant", forge.invariant());
        }
    }

    @Test
    void aCardThatClaimsAReferenceHashForAReferenceFreeComponentFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract(SEGMENT, VERSION, SLOT, List.of(),
                request -> List.of(CapabilityPreviewCardFixtures.builder()
                        .bind(resolved(request, SEGMENT, VERSION, SLOT, List.of()))
                        .resolvedCatalogReferenceHash(CapabilityPreviewCardFixtures.hash("invented"))
                        .build())));

        assertThat(failure(catalog).details())
                .containsEntry("invariant", "capabilityCardBindingReferenceHash");
        assertThat(failure(catalog).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    @Test
    void aCardThatOmitsTheReferenceHashOfAReferenceResolvingComponentFailsClosed() {
        ResolvedCatalogReference reference = BuilderFixtures.reference("tpl-1", "TEMPLATE", "tpl-a", "tpl-a-v1");
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract(SEGMENT, VERSION, SLOT, List.of(reference),
                request -> List.of(CapabilityPreviewCardFixtures.builder()
                        .bind(resolved(request, SEGMENT, VERSION, SLOT, List.of(reference)))
                        .resolvedCatalogReferenceHash(null)
                        .build())));

        assertThat(failure(catalog).details())
                .containsEntry("invariant", "capabilityCardBindingReferenceHash");
    }

    @Test
    void aDuplicatedCapabilityPreviewCardFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(contract(SEGMENT, VERSION, SLOT, List.of(),
                request -> {
                    CapabilityPlanPreviewCard card = CapabilityPreviewCardFixtures.bound(
                            resolved(request, SEGMENT, VERSION, SLOT, List.of()), RUNTIME);
                    return List.of(card, card);
                }));

        assertThat(failure(catalog).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
        assertThat(failure(catalog).details()).containsEntry("invariant", "capabilityCardDuplicate");
    }

    @Test
    void aResolutionCarryingANullCapabilityPreviewCardFailsClosed() {
        List<CapabilityPlanPreviewCard> withNull = new ArrayList<>();
        withNull.add(null);

        assertThatThrownBy(() -> new CapabilityResolution.Resolved(
                CapabilityPayload.empty(), List.of(), List.of(), withNull))
                .isInstanceOf(CompilerException.class)
                .satisfies(throwable -> assertThat(((CompilerException) throwable).code())
                        .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID));
    }

    @Test
    void aContractThatReturnsANullCapabilityPreviewCardListFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(new FixtureCapability(
                SEGMENT, VERSION, SLOT, true, RUNTIME, request -> new CapabilityResolution.Resolved(
                        request.payload(), List.of(), List.of(), null)));

        assertThat(failure(catalog).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    // ------------------------------------------------------------------ fail-closed: stale and replaced

    @Test
    void aReResolvedComponentReplacesItsCapabilityPreviewCardsOutright() {
        FrozenCapabilityCatalog cardingCatalog = BuilderFixtures.catalog(honestSegment(List.of()));
        FrozenCapabilityCatalog plainCatalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, SLOT));
        // Only the resolver differs, so both catalogs pin the same version and the same catalog hash.
        assertThat(plainCatalog.catalogHash()).isEqualTo(cardingCatalog.catalogHash());

        CampaignPlan first = compile(cardingCatalog, createPatch(cardingCatalog, Map.of("spec", "a")));
        assertThat(first.capabilityPreviewCards()).hasSize(1);
        String logicalId = first.manifest().components().get(0).logicalId();

        CampaignProposalPatch second = BuilderFixtures.editProposal().pins(plainCatalog, policy)
                .upsert(SEGMENT, logicalId, Map.of("spec", "a")).build();
        CampaignPlan plan = edit(second, first, plainCatalog);

        // The re-resolution contributed no card, so no stale card survives the replacement.
        assertThat(plan.capabilityPreviewCards()).isEmpty();
    }

    @Test
    void aReplacedComponentNeverKeepsTheCardOfItsPreviousPayload() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honestSegment(List.of()));
        CampaignPlan first = compile(catalog, createPatch(catalog, Map.of("spec", "a")));
        String logicalId = first.manifest().components().get(0).logicalId();
        ContentHash previousPayload = first.capabilityPreviewCards().get(0).normalizedPayloadHash();

        CampaignProposalPatch second = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, logicalId, Map.of("spec", "changed")).build();
        CampaignPlan plan = edit(second, first, catalog);

        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        ResolvedComponent component = plan.manifest().component(logicalId);
        assertThat(plan.capabilityPreviewCards().get(0).normalizedPayloadHash())
                .isEqualTo(component.value().contentHash())
                .isNotEqualTo(previousPayload);
    }

    @Test
    void anInheritedComponentContributesNoCapabilityPreviewCard() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                honestSegment(List.of()),
                honest(MESSAGE, MESSAGE_VERSION, MESSAGE_SLOT, List.of()));
        ResolvedComponent carried = BuilderFixtures.component("segment-main", SEGMENT, VERSION, SLOT, Map.of("s", "x"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(MESSAGE, "message-new", Map.of("text", "hi")).build();
        CampaignPlan plan = edit(patch, BuilderFixtures.current(carried), catalog);

        assertThat(plan.manifest().components()).hasSize(2);
        // Only the component the proposal actually re-resolved contributes a card; the inherited one never does.
        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        assertThat(plan.capabilityPreviewCards().get(0).capabilityCode()).isEqualTo(MESSAGE);
        assertThat(plan.capabilityPreviewCards().get(0).componentLogicalId())
                .isNotEqualTo("segment-main");
    }

    @Test
    void aDeletedComponentLeavesNoCapabilityPreviewCardBehind() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(honestSegment(List.of()));
        ResolvedComponent carried = BuilderFixtures.component("segment-main", SEGMENT, VERSION, SLOT, Map.of("s", "x"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a"))
                .delete("segment-main").build();
        CampaignPlan plan = edit(patch, BuilderFixtures.current(carried), catalog);

        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(plan.manifest().component("segment-main")).isNull();
        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        assertThat(plan.capabilityPreviewCards().get(0).componentLogicalId())
                .isEqualTo(plan.manifest().components().get(0).logicalId());
    }

    // ------------------------------------------------------------------ fail-closed: the Plan boundary

    @Test
    void thePlanBoundaryRejectsACardThatDoesNotBindItsManifestComponent() {
        // The card names a component the manifest really holds, but declares a capability it does not have.
        CapabilityPlanPreviewCard unbound = CapabilityPreviewCardFixtures.builder()
                .bind(manifestComponent()).capabilityCode(MESSAGE).build();

        assertPlanInconsistent(List.of(unbound), "capabilityPreviewCardBinding");
    }

    @Test
    void thePlanBoundaryRejectsACardCarryingAStalePayloadHash() {
        CapabilityPlanPreviewCard stale = CapabilityPreviewCardFixtures.builder()
                .bind(manifestComponent())
                .normalizedPayloadHash(CapabilityPreviewCardFixtures.hash("previous")).build();

        assertPlanInconsistent(List.of(stale), "capabilityPreviewCardBinding");
    }

    @Test
    void thePlanBoundaryRejectsADuplicatedCard() {
        ResolvedComponent component = manifestComponent();
        CapabilityPlanPreviewCard card = CapabilityPreviewCardFixtures.bound(component, RUNTIME);

        assertPlanInconsistent(List.of(card, card), "capabilityPreviewCards");
    }

    @Test
    void thePlanBoundaryRejectsANullCard() {
        List<CapabilityPlanPreviewCard> withNull = new ArrayList<>();
        withNull.add(null);

        assertPlanInconsistent(withNull, "capabilityPreviewCards");
    }

    @Test
    void thePlanBoundaryRejectsACardForAComponentTheManifestDoesNotHold() {
        // The card is internally honest, but the component it names was deleted or never merged.
        CapabilityPlanPreviewCard orphan = CapabilityPreviewCardFixtures.builder()
                .bind(manifestComponent()).componentLogicalId("component-deleted").build();

        assertPlanInconsistent(List.of(orphan), "capabilityPreviewCards");
    }

    // ------------------------------------------------------------------ helpers

    /** One forged card, built from the honest component facts the compiler actually resolved. */
    private record Forge(String invariant, Function<ResolvedComponent, CapabilityPlanPreviewCard> card) {
    }

    /** The honest Segment contract: resolves its payload unchanged and contributes one bound card. */
    private static FixtureCapability honestSegment(List<ResolvedCatalogReference> references) {
        return honest(SEGMENT, VERSION, SLOT, references);
    }

    /** The honest contract under an explicit capability: one card, bound to the component it resolved. */
    private static FixtureCapability honest(
            String code, String version, CompositionSlot slot, List<ResolvedCatalogReference> references) {
        return contract(code, version, slot, references, request -> List.of(
                CapabilityPreviewCardFixtures.bound(resolved(request, code, version, slot, references), RUNTIME)));
    }

    /** A contract that contributes exactly the cards the function builds. */
    private static FixtureCapability contract(
            String code, String version, CompositionSlot slot, List<ResolvedCatalogReference> references,
            Function<CapabilityRequest, List<CapabilityPlanPreviewCard>> cards) {
        return new FixtureCapability(code, version, slot, true, RUNTIME, request ->
                new CapabilityResolution.Resolved(request.payload(), references, List.of(), cards.apply(request)));
    }

    /** The component the compiler will hold if the contract normalizes nothing and resolves these references. */
    private static ResolvedComponent resolved(
            CapabilityRequest request, String code, String version, CompositionSlot slot,
            List<ResolvedCatalogReference> references) {
        return new ResolvedComponent(request.logicalId(), code, version, slot, request.payload(), references);
    }

    private CampaignProposalPatch createPatch(FrozenCapabilityCatalog catalog, Map<String, Object> value) {
        return BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", value).build();
    }

    private CampaignPlan compile(FrozenCapabilityCatalog catalog, CampaignProposalPatch patch) {
        return compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private CampaignPlan edit(CampaignProposalPatch patch, CampaignPlan base, FrozenCapabilityCatalog catalog) {
        return edit(patch, BuilderFixtures.current(base.manifest().components().toArray(new ResolvedComponent[0])),
                catalog);
    }

    private CampaignPlan edit(
            CampaignProposalPatch patch, TrustedCurrentCampaign current, FrozenCapabilityCatalog catalog) {
        return compiler.compile(CompileRequest.edit(
                patch, current, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private ContractException failure(FrozenCapabilityCatalog catalog) {
        return catchThrowableOfType(
                () -> compile(catalog, createPatch(catalog, Map.of("spec", "a"))), ContractException.class);
    }

    private static ResolvedComponent manifestComponent() {
        return BuilderFixtures.component("component-a", SEGMENT, VERSION, SLOT, Map.of("spec", "a"));
    }

    private static void assertPlanInconsistent(List<CapabilityPlanPreviewCard> cards, String invariant) {
        ResolvedComponent component = manifestComponent();
        ResolvedCampaignManifest manifest = ResolvedCampaignManifest.create(
                CompilerVersions.MANIFEST_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION, ProposalMode.EDIT,
                BuilderFixtures.CAMPAIGN, BuilderFixtures.CAMPAIGN_REVISION, BuilderFixtures.DRAFT,
                BuilderFixtures.DRAFT_REVISION, null, List.of(component),
                List.of(new CapabilityPin(SEGMENT, VERSION)), BuilderFixtures.CATALOG_VERSION,
                CapabilityPreviewCardFixtures.hash("catalog"), BuilderFixtures.POLICY_VERSION,
                CapabilityPreviewCardFixtures.hash("policy"));

        ContractException failure = catchThrowableOfType(() -> CampaignPlan.create(
                CompilerVersions.PLAN_FORMAT_VERSION, CompilerVersions.COMPILER_VERSION,
                BuilderFixtures.COMPILED_AT, BuilderFixtures.EXPIRES_AT,
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, BuilderFixtures.USER,
                manifest.campaignId(), manifest.baseCampaignRevision(), manifest.baseDraftId(),
                manifest.baseDraftRevision(), manifest.forkBase(),
                CapabilityPreviewCardFixtures.hash("proposal"), manifest.manifestHash(),
                manifest.capabilityPins(), manifest.catalogVersion(), manifest.catalogHash(),
                manifest.policyVersion(), manifest.policyHash(), manifest, List.of(),
                new SemanticDiff(List.of()),
                new ChangeSet(CompilerVersions.CHANGESET_FORMAT_VERSION, List.of()),
                List.of(), List.of(), cards, RiskSummary.empty()), ContractException.class);

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT);
        assertThat(failure.details()).containsEntry("invariant", invariant);
    }
}