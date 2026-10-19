package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.CAMPAIGN;
import static com.company.campaign.builder.testsupport.BuilderFixtures.FLOW;
import static com.company.campaign.builder.testsupport.BuilderFixtures.MESSAGE;
import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirements 2-6: CREATE/EDIT rules, non-disclosing stale and foreign Current failures, omission as
 * inherit, explicit deletion, in-place update of a stable identity and determinism of a new identity.
 */
class CampaignCompilerMergeTest {

    private static final String MESSAGE_VERSION = "message-template-1.0";
    private static final String SEGMENT_VERSION = "segment-rule-based-1.0";
    private static final String FLOW_VERSION = "flow-message-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")),
            FixtureCapability.passThrough(SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment")),
            FixtureCapability.passThrough(FLOW, FLOW_VERSION, FixtureCapability.ordered("flow")));

    // ------------------------------------------------------------------ requirement 2

    @Test
    void createRequiresNoCurrentAndCarriesNoCampaignIdentity() {
        CampaignProposalPatch patch = BuilderFixtures.createProposal()
                .pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a"))
                .build();
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.campaignId()).isNull();
        assertThat(plan.manifest().mode()).isEqualTo(com.company.campaign.builder.proposal.ProposalMode.CREATE);
        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(plan.baseDraftId()).isNull();
        assertThat(plan.semanticDiff().count(ChangeKind.ADD)).isEqualTo(1);
    }

    @Test
    void createWithATrustedCurrentFailsClosed() {
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
        assertThatThrownBy(() -> compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context())))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_TARGET_UNAVAILABLE);
    }

    @Test
    void editWithoutATrustedCurrentFailsClosed() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of()).build();
        assertThatThrownBy(() -> compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context())))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_TARGET_UNAVAILABLE);
    }

    @Test
    void foreignStaleAndContradictoryCurrentAreIndistinguishable() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of()).build();
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of());

        CampaignProposalPatch otherCampaign = BuilderFixtures.editProposal().pins(catalog, policy)
                .campaignId("campaign-002")
                .upsert(SEGMENT, "segment-main", Map.of()).build();

        ContractException missingCurrent = failure(patch, null, BuilderFixtures.context());
        ContractException wrongCampaign = failure(
                otherCampaign, BuilderFixtures.current(segment), BuilderFixtures.context());
        ContractException staleRevision = failure(
                patch, BuilderFixtures.current(99L, segment), BuilderFixtures.context());
        ContractException foreignTenant = failure(
                patch, BuilderFixtures.current(segment), BuilderFixtures.context("tenant-999", BuilderFixtures.PROGRAM));
        ContractException foreignProgram = failure(
                patch, BuilderFixtures.current(segment), BuilderFixtures.context(BuilderFixtures.TENANT, "program-999"));

        for (ContractException failure : List.of(
                missingCurrent, wrongCampaign, staleRevision, foreignTenant, foreignProgram)) {
            assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_TARGET_UNAVAILABLE);
            assertThat(failure.getMessage()).isEqualTo(missingCurrent.getMessage());
            assertThat(failure.details()).isEqualTo(missingCurrent.details());
        }
        assertThat(missingCurrent.getMessage()).doesNotContain(CAMPAIGN, BuilderFixtures.TENANT);
        assertThat(missingCurrent.details().toString()).doesNotContain(CAMPAIGN, BuilderFixtures.TENANT);
    }

    @Test
    void aStaleFrozenCatalogOrPolicyPinFailsClosed() {
        FrozenCapabilityCatalog otherCatalog = BuilderFixtures.catalogWithHash(
                "sha256:" + "c".repeat(64),
                FixtureCapability.passThrough(MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message")));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of()).build();
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of());

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(segment), otherCatalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_BASE_REVISIONS_STALE);
    }

    @Test
    void aForkedDraftRecordsItsPublishedForkBase() {
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        PublishedIdentity forkBase = new PublishedIdentity(
                BuilderFixtures.PUBLISHED, 4L, com.company.campaign.shared.canonical.ContentHash.sha256(
                        "published".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "b")).build();

        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.forkedCurrent(forkBase, segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.forkBase()).isEqualTo(forkBase);
        assertThat(plan.manifest().forkBase()).isEqualTo(forkBase);
        assertThat(plan.baseDraftId()).isEqualTo(BuilderFixtures.DRAFT);
        assertThat(plan.baseDraftRevision()).isEqualTo(BuilderFixtures.DRAFT_REVISION);
    }

    @Test
    void aPublishedIdentityIsNeverAnEditingTarget() {
        PublishedIdentity published = new PublishedIdentity(
                BuilderFixtures.DRAFT, 1L, com.company.campaign.shared.canonical.ContentHash.sha256(
                        "x".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> new TrustedCurrentCampaign(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, CAMPAIGN, BuilderFixtures.CAMPAIGN_REVISION,
                new DraftIdentity(BuilderFixtures.DRAFT, BuilderFixtures.DRAFT_REVISION), published, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aForkBaseWithADifferentIdentityAndAnEqualRevisionIsAValidFork() {
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        // Different identities; the revision counters coincide. Equal revisions are not identity, so this is
        // a legitimate fork and must be recorded as one.
        PublishedIdentity forkBase = new PublishedIdentity(
                BuilderFixtures.PUBLISHED, BuilderFixtures.DRAFT_REVISION,
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "published".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "b")).build();

        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.forkedCurrent(forkBase, segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(forkBase.revision()).isEqualTo(BuilderFixtures.DRAFT_REVISION);
        assertThat(forkBase.publishedId()).isNotEqualTo(BuilderFixtures.DRAFT);
        assertThat(plan.forkBase()).isEqualTo(forkBase);
        assertThat(plan.manifest().forkBase()).isEqualTo(forkBase);
    }

    @Test
    void aForkBaseThatNamesTheDraftItselfIsRejectedWhateverItsRevision() {
        PublishedIdentity sameIdentity = new PublishedIdentity(
                BuilderFixtures.DRAFT, BuilderFixtures.DRAFT_REVISION + 5L,
                com.company.campaign.shared.canonical.ContentHash.sha256(
                        "y".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> new TrustedCurrentCampaign(
                BuilderFixtures.TENANT, BuilderFixtures.PROGRAM, CAMPAIGN, BuilderFixtures.CAMPAIGN_REVISION,
                new DraftIdentity(BuilderFixtures.DRAFT, BuilderFixtures.DRAFT_REVISION), sameIdentity, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ requirement 3

    @Test
    void omissionInheritsEveryUntouchedComponentWithoutDeletingOrClearingIt() {
        ResolvedComponent message = BuilderFixtures.component(
                "message-main", MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message"),
                Map.of("text", "hello"));
        ResolvedComponent flow = BuilderFixtures.component(
                "flow-main", FLOW, FLOW_VERSION, FixtureCapability.ordered("flow"), Map.of("node", "wait"));
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));

        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "b")).build();
        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(message, flow, segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        List<String> ids = plan.manifest().components().stream().map(ResolvedComponent::logicalId).toList();
        assertThat(ids).containsExactly("message-main", "flow-main", "segment-main");
        assertThat(plan.manifest().component("message-main")).isEqualTo(message);
        assertThat(plan.manifest().component("flow-main")).isEqualTo(flow);
        assertThat(plan.manifest().component("segment-main")).isNotEqualTo(segment);
        assertThat(plan.semanticDiff().count(ChangeKind.UPDATE)).isEqualTo(1);
        assertThat(plan.changeSet().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ requirement 4

    @Test
    void deleteIsTheOnlyDeletionPath() {
        ResolvedComponent message = BuilderFixtures.component(
                "message-main", MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message"), Map.of("t", "1"));
        ResolvedComponent flow = BuilderFixtures.component(
                "flow-main", FLOW, FLOW_VERSION, FixtureCapability.ordered("flow"), Map.of("n", "w"));

        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .delete("flow-main").build();
        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(message, flow), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(plan.manifest().component("flow-main")).isNull();
        assertThat(plan.semanticDiff().count(ChangeKind.DELETE)).isEqualTo(1);
        assertThat(plan.changeSet().nodes()).hasSize(1);
    }

    @Test
    void deletingAnUnknownIdentityFailsClosed() {
        ResolvedComponent message = BuilderFixtures.component(
                "message-main", MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message"), Map.of());
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .delete("does-not-exist").build();

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(message), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_LOGICAL_ID_UNKNOWN);
    }

    @Test
    void deleteAndRecreateNeverMasqueradesAsAnUpdate() {
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .delete("segment-main")
                .upsertNew(SEGMENT, "segment-replacement", Map.of("spec", "a"))
                .build();
        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.semanticDiff().count(ChangeKind.DELETE)).isEqualTo(1);
        assertThat(plan.semanticDiff().count(ChangeKind.ADD)).isEqualTo(1);
        assertThat(plan.semanticDiff().count(ChangeKind.UPDATE)).isZero();
        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(plan.manifest().components().get(0).logicalId()).isNotEqualTo("segment-main");
    }

    // ------------------------------------------------------------------ requirement 5

    @Test
    void anExistingLogicalIdIsUpdatedInPlace() {
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "b")).build();
        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        ResolvedComponent updated = plan.manifest().component("segment-main");
        assertThat(updated).isNotNull();
        assertThat(updated.value().fields()).containsEntry("spec", "b");
        assertThat(plan.changeSet().nodes()).singleElement().isInstanceOf(ChangeSetNode.Update.class);
    }

    @Test
    void aNewClientReferenceGetsOneDeterministicCompilerOwnedIdentity() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();

        CampaignPlan first = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context()));
        CampaignPlan second = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context()));

        assertThat(first.clientRefBindings()).hasSize(1);
        ClientRefBinding binding = first.clientRefBindings().get(0);
        assertThat(binding.clientRef()).isEqualTo("segment-new");
        assertThat(binding.logicalId()).matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
        assertThat(binding.logicalId()).hasSize(64);
        assertThat(first.manifest().components().get(0).logicalId()).isEqualTo(binding.logicalId());
        assertThat(second.clientRefBindings()).isEqualTo(first.clientRefBindings());
        assertThat(second.planId()).isEqualTo(first.planId());
    }

    @Test
    void aDerivedIdentityThatCollidesWithAnExistingOneFailsClosed() {
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();
        CampaignPlan probe = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context()));
        String derived = probe.clientRefBindings().get(0).logicalId();

        ResolvedComponent colliding = BuilderFixtures.component(
                derived, SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "z"));
        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(colliding), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_LOGICAL_ID_COLLISION);
    }

    @Test
    void aClientReferenceThatLooksLikeAnExistingIdentityFailsClosed() {
        ResolvedComponent segment = BuilderFixtures.component(
                "taken", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of());
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "taken", Map.of()).build();

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(segment), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_LOGICAL_ID_COLLISION);
    }

    // ------------------------------------------------------------------ requirement 6

    @Test
    void reUpsertingAnIdenticalComponentIsIdempotentAndProducesAnEmptyChangeSet() {
        ResolvedComponent segment = BuilderFixtures.component(
                "segment-main", SEGMENT, SEGMENT_VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsert(SEGMENT, "segment-main", Map.of("spec", "a")).build();

        CampaignPlan plan = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(segment), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.manifest().components()).isEqualTo(List.of(segment));
        assertThat(plan.semanticDiff().isEmpty()).isTrue();
        assertThat(plan.changeSet().isEmpty()).isTrue();
    }

    @Test
    void repeatedCompilationOfIdenticalInputsIsByteIdentical() {
        ResolvedComponent message = BuilderFixtures.component(
                "message-main", MESSAGE, MESSAGE_VERSION, FixtureCapability.unorderedMulti("message"), Map.of("t", "1"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();

        CampaignPlan first = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(message), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context()));
        CampaignPlan second = compiler.compile(CompileRequest.edit(
                patch, BuilderFixtures.current(message), catalog, policy, BuilderFixtures.instants(),
                BuilderFixtures.context()));

        assertThat(second.planId()).isEqualTo(first.planId());
        assertThat(second.planHash()).isEqualTo(first.planHash());
        assertThat(second.manifestHash()).isEqualTo(first.manifestHash());
        assertThat(second.canonicalMaterialBytes()).isEqualTo(first.canonicalMaterialBytes());
        assertThat(second.changeSet()).isEqualTo(first.changeSet());
        assertThat(second.semanticDiff()).isEqualTo(first.semanticDiff());
    }

    // ------------------------------------------------------------------ helpers

    private ContractException failure(
            CampaignProposalPatch patch,
            TrustedCurrentCampaign current,
            com.company.campaign.security.context.TrustedExecutionContext context) {
        return catchThrowableOfType(
                () -> compiler.compile(new CompileRequest(
                        patch, current, catalog, policy, BuilderFixtures.instants(), context)),
                ContractException.class);
    }
}