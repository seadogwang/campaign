package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirements 9 and 10: unknown, disabled, version-mismatched and failing capabilities and unresolved or
 * ambiguous catalog references fail closed; composition and policy rules are deterministic and bounded.
 */
class CapabilityResolutionTest {

    private static final String VERSION = "segment-rule-based-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    // ------------------------------------------------------------------ requirement 9

    @Test
    void anUnknownCapabilityFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough("message.template", "message-template-1.0",
                        FixtureCapability.unorderedMulti("message")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        assertThat(failure(catalog, patch).code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_UNKNOWN);
    }

    @Test
    void aDisabledCapabilityFailsClosed() {
        CapabilityContract disabled = new FixtureCapability(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), false, FixtureCapability.runtime(),
                request -> FixtureCapability.resolve(request.payload()));
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(disabled);
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        assertThat(failure(catalog, patch).code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_DISABLED);
    }

    @Test
    void aCarriedComponentPinnedToAVersionTheCatalogNoLongerDeclaresFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")),
                FixtureCapability.passThrough("message.template", "message-template-1.0",
                        FixtureCapability.unorderedMulti("message")));
        // The carried component is untouched by the proposal, so its pin is re-verified as-is.
        ResolvedComponent carried = BuilderFixtures.component(
                "segment-main", SEGMENT, "segment-rule-based-2.0",
                FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew("message.template", "message-new", Map.of("text", "hi")).build();

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(carried), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_VERSION_MISMATCH);
    }

    @Test
    void aCarriedComponentWhoseCapabilityIsDisabledFailsClosed() {
        CapabilityContract disabled = new FixtureCapability(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), false, FixtureCapability.runtime(),
                request -> FixtureCapability.resolve(request.payload()));
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                disabled,
                FixtureCapability.passThrough("message.template", "message-template-1.0",
                        FixtureCapability.unorderedMulti("message")));
        // The carried component is untouched: the proposal edits a different component, so only the
        // composition re-verification can notice that the inherited capability is now disabled.
        ResolvedComponent carried = BuilderFixtures.component(
                "segment-main", SEGMENT, VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "a"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew("message.template", "message-new", Map.of("text", "hi")).build();

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(carried), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_DISABLED);
        assertThat(failure.details().get("invariant")).isEqualTo("carriedCapabilityEnabled");
        assertThat(failure.getMessage()).doesNotContain(SEGMENT, "segment-main", "message-new");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void aCarriedComponentWhoseCapabilityLeftTheCatalogFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")),
                FixtureCapability.passThrough("message.template", "message-template-1.0",
                        FixtureCapability.unorderedMulti("message")));
        ResolvedComponent carried = BuilderFixtures.component(
                "retired-main", "promotion.reference", "promotion-reference-1.0",
                FixtureCapability.unorderedMulti("promotion"), Map.of("code", "p"));
        CampaignProposalPatch patch = BuilderFixtures.editProposal().pins(catalog, policy)
                .upsertNew("message.template", "message-new", Map.of("text", "hi")).build();

        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.edit(
                        patch, BuilderFixtures.current(carried), catalog, policy, BuilderFixtures.instants(),
                        BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_UNKNOWN);
    }

    @Test
    void aContractThatReportsUnresolvedForeignOrAmbiguousReferencesFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(FixtureCapability.failing(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), "CATALOG_REFERENCE_UNRESOLVED"));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        assertThat(failure(catalog, patch).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    @Test
    void aContractThatFloodsItsIssueListFailsClosed() {
        CapabilityContract flooding = new FixtureCapability(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), true, FixtureCapability.runtime(),
                request -> new CapabilityResolution.Failed(new java.util.ArrayList<>(java.util.Collections.nCopies(
                        CampaignCompiler.MAX_CONTRACT_ISSUES + 1, Issue.error("FIXTURE_MANY", "advisory")))));
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(flooding);
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        assertThat(failure(catalog, patch).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    @Test
    void aContractThatThrowsOrReturnsNullFailsClosed() {
        CapabilityContract throwing = new FixtureCapability(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), true, FixtureCapability.runtime(),
                request -> {
                    throw new IllegalStateException("provider unavailable");
                });
        CapabilityContract nullReturning = new FixtureCapability(
                SEGMENT, VERSION, FixtureCapability.ordered("segment"), true, FixtureCapability.runtime(),
                request -> null);
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(
                BuilderFixtures.catalog(throwing), policy).upsertNew(SEGMENT, "segment-new", Map.of()).build();

        assertThat(failure(BuilderFixtures.catalog(throwing), patch).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
        assertThat(failure(BuilderFixtures.catalog(nullReturning), patch).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
    }

    @Test
    void oneReferenceCodeResolvingToTwoEntityVersionsFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.referencing(SEGMENT, VERSION, FixtureCapability.ordered("segment"),
                        BuilderFixtures.reference("tpl-1", "TEMPLATE", "template-a", "template-a-v1")),
                FixtureCapability.referencing("message.template", "message-template-1.0",
                        FixtureCapability.unorderedMulti("message"),
                        BuilderFixtures.reference("tpl-1", "TEMPLATE", "template-b", "template-b-v1")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of())
                .upsertNew("message.template", "message-new", Map.of())
                .build();

        assertThat(failure(catalog, patch).code()).isEqualTo(CompilerErrorCodes.COMPILER_REFERENCE_UNRESOLVED);
    }

    @Test
    void resolvedReferencesArePinnedIntoTheManifest() {
        ResolvedCatalogReference reference =
                BuilderFixtures.reference("tpl-1", "TEMPLATE", "template-a", "template-a-v1");
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.referencing(SEGMENT, VERSION, FixtureCapability.ordered("segment"), reference));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
        assertThat(plan.manifest().components().get(0).references()).containsExactly(reference);
        assertThat(plan.manifest().canonicalBytes()).isNotEmpty();
    }

    // ------------------------------------------------------------------ requirement 10

    @Test
    void aSingletonSlotHoldingTwoComponentsFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.singleton("segment")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-one", Map.of())
                .upsertNew(SEGMENT, "segment-two", Map.of())
                .build();

        assertThat(failure(catalog, patch).code()).isEqualTo(CompilerErrorCodes.COMPILER_COMPOSITION_SLOT_VIOLATION);
    }

    @Test
    void aSingletonSlotHoldingOneComponentSucceeds() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.singleton("segment")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-one", Map.of())
                .build();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
        assertThat(plan.manifest().components()).hasSize(1);
    }

    @Test
    void aMultiInstanceSlotAcceptsSeveralComponents() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.unorderedMulti("segment")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-one", Map.of())
                .upsertNew(SEGMENT, "segment-two", Map.of())
                .build();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
        assertThat(plan.manifest().components()).hasSize(2);
    }

    @Test
    void policyAdvisoriesAreRecordedAndBlockingIssuesFailClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        FrozenAuthoringPolicy advisory = BuilderFixtures.policyWithWarnings();
        CampaignProposalPatch advisoryPatch = BuilderFixtures.createProposal().pins(catalog, advisory)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                advisoryPatch, catalog, advisory, BuilderFixtures.instants(), BuilderFixtures.context()));
        assertThat(plan.validationIssues()).hasSize(1);
        assertThat(plan.validationIssues().get(0).code()).isEqualTo("FIXTURE_ADVISORY");

        FrozenAuthoringPolicy blocking = BuilderFixtures.blockingPolicy();
        CampaignProposalPatch blockingPatch = BuilderFixtures.createProposal().pins(catalog, blocking)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();
        ContractException failure = catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        blockingPatch, catalog, blocking, BuilderFixtures.instants(), BuilderFixtures.context())),
                ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_POLICY_VIOLATION);
    }

    @Test
    void aPolicyValidatorThatThrowsOrOverflowsItsBoundFailsClosed() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));

        FrozenAuthoringPolicy throwing = BuilderFixtures.policy(request -> {
            throw new IllegalStateException("policy backend unavailable");
        });
        FrozenAuthoringPolicy overflowing = BuilderFixtures.policy(request -> {
            List<Issue> issues = new java.util.ArrayList<>();
            for (int index = 0; index <= FrozenAuthoringPolicy.MAX_ISSUES; index++) {
                issues.add(Issue.error("FIXTURE_" + index, "advisory"));
            }
            return issues;
        });

        for (FrozenAuthoringPolicy candidate : List.of(throwing, overflowing)) {
            CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, candidate)
                    .upsertNew(SEGMENT, "segment-new", Map.of()).build();
            ContractException failure = catchThrowableOfType(
                    () -> compiler.compile(CompileRequest.create(
                            patch, catalog, candidate, BuilderFixtures.instants(), BuilderFixtures.context())),
                    ContractException.class);
            assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_CAPABILITY_CONTRACT_INVALID);
        }
    }

    @Test
    void policyEvaluationIsDeterministicAndBounded() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));
        FrozenAuthoringPolicy stable = BuilderFixtures.policy(request -> List.of(
                BuilderFixtures.warning("FIXTURE_ONE"), BuilderFixtures.warning("FIXTURE_TWO")));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, stable)
                .upsertNew(SEGMENT, "segment-new", Map.of()).build();

        CampaignPlan first = compiler.compile(CompileRequest.create(
                patch, catalog, stable, BuilderFixtures.instants(), BuilderFixtures.context()));
        CampaignPlan second = compiler.compile(CompileRequest.create(
                patch, catalog, stable, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(first.validationIssues().stream().map(Issue::code).toList())
                .containsExactly("FIXTURE_ONE", "FIXTURE_TWO");
        assertThat(second.validationIssues()).isEqualTo(first.validationIssues());
        assertThat(first.planId()).isEqualTo(second.planId());
    }

    @Test
    void aNormalizingContractDeterministicallyNormalizesThePayload() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                FixtureCapability.normalizing(SEGMENT, VERSION, FixtureCapability.ordered("segment")));
        CampaignProposalPatch first = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();
        CampaignProposalPatch second = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();

        CampaignPlan firstPlan = compiler.compile(CompileRequest.create(
                first, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));
        CampaignPlan secondPlan = compiler.compile(CompileRequest.create(
                second, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(firstPlan.manifest().components().get(0).value().fields())
                .containsEntry("normalized", true)
                .containsEntry("spec", "a");
        assertThat(secondPlan.manifestHash()).isEqualTo(firstPlan.manifestHash());
    }

    @Test
    void aCapabilityContractReceivesTheExactTrustedFactsAndNoMutableState() {
        java.util.concurrent.atomic.AtomicReference<CapabilityRequest> observed = new java.util.concurrent.atomic.AtomicReference<>();
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
                new FixtureCapability(SEGMENT, VERSION, FixtureCapability.ordered("segment"), true,
                        FixtureCapability.runtime(), request -> {
                            observed.set(request);
                            return FixtureCapability.resolve(request.payload());
                        }));
        CampaignProposalPatch patch = BuilderFixtures.createProposal().pins(catalog, policy)
                .upsertNew(SEGMENT, "segment-new", Map.of("spec", "a")).build();

        CampaignPlan plan = compiler.compile(CompileRequest.create(
                patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.manifest().components()).hasSize(1);
        assertThat(observed.get().catalog()).isSameAs(catalog);
        assertThat(observed.get().policy()).isSameAs(policy);
        assertThat(observed.get().context().tenantId()).isEqualTo(BuilderFixtures.TENANT);
        assertThat(observed.get().payload().fields()).containsEntry("spec", "a");
        assertThat(observed.get().payload().fields()).isUnmodifiable();
    }

    @Test
    void aCapabilityPayloadIsDefensivelyImmutableAndHashBound() {
        CapabilityPayload payload = CapabilityPayload.of(Map.of("spec", "a"));
        assertThatThrownBy(() -> payload.fields().put("spec", "b")).isInstanceOf(UnsupportedOperationException.class);
        assertThat(payload.contentHash()).isEqualTo(ContentHash.sha256(payload.canonicalBytes()));
        assertThat(payload.canonicalText()).doesNotContain(" ");
        byte[] copy = payload.canonicalBytes();
        copy[0] = 'X';
        assertThat(payload.canonicalBytes()).isNotEqualTo(copy);
    }

    @Test
    void aCapabilityPayloadRejectsAReservedFieldAtAnyNestingLevel() {
        assertThatThrownBy(() -> CapabilityPayload.of(Map.of("outer", Map.of("tenant_id", "t"))))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).details().get("invariant"))
                .isEqualTo("payloadReservedField");
    }

    // ------------------------------------------------------------------ helpers

    private ContractException failure(FrozenCapabilityCatalog catalog, CampaignProposalPatch patch) {
        return catchThrowableOfType(
                () -> compiler.compile(CompileRequest.create(
                        patch, catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context())),
                ContractException.class);
    }
}