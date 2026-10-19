package com.company.campaign.builder.compiler;

import com.company.campaign.builder.proposal.CampaignProposalPatch;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.contracts.PlanRef;
import com.company.campaign.shared.error.ContractException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Requirement 15: the Plan store is idempotent for an identical Plan, rejects a conflicting reuse of one
 * identifier, and can only ever hold a genuine compiler-produced Plan.
 */
class CampaignPlanStoreTest {

    private static final String VERSION = "segment-rule-based-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));
    private final InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();

    @Test
    void compileAndStoreReturnsOnlyAPlanReference() {
        PlanRef reference = compiler.compileAndStore(request("one"), store);

        assertThat(reference).isNotNull();
        assertThat(reference.planId()).isEqualTo(reference.planHash().value().substring("sha256:".length()));
        assertThat(reference.expiresAt()).isEqualTo(BuilderFixtures.EXPIRES_AT);
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.find(reference.planId())).isPresent();
    }

    @Test
    void storingTheSamePlanAgainIsIdempotent() {
        CampaignPlan plan = compile("one");
        PlanRef first = store.store(plan);
        PlanRef second = store.store(plan);

        assertThat(second).isEqualTo(first);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void compilingTheSameInputsTwiceYieldsOneStoredPlan() {
        PlanRef first = compiler.compileAndStore(request("one"), store);
        PlanRef second = compiler.compileAndStore(request("one"), store);

        assertThat(second).isEqualTo(first);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void twoDifferentPlansAreStoredIndependently() {
        store.store(compile("one"));
        store.store(compile("two"));
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void reusingOneIdentifierForDifferentPlanMaterialIsAConflict() {
        CampaignPlan first = compile("one");
        CampaignPlan second = compile("two");
        assertThat(first.planId()).isNotEqualTo(second.planId());

        ContractException failure = catchThrowableOfType(
                () -> InMemoryCampaignPlanStore.requireCompatible(first, second), ContractException.class);
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT);
        InMemoryCampaignPlanStore.requireCompatible(first, first);
    }

    @Test
    void aNullPlanIsRefused() {
        assertThatThrownBy(() -> store.store(null))
                .isInstanceOf(ContractException.class)
                .extracting(throwable -> ((ContractException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
    }

    @Test
    void lookupIsHostOnlyAndReturnsTheStoredPlan() {
        CampaignPlan plan = compile("one");
        store.store(plan);
        assertThat(store.find(plan.planId())).contains(plan);
        assertThat(store.find("0".repeat(64))).isEmpty();
    }

    @Test
    void theStoreAcceptsOnlyTheCompilerProducedPlanType() throws Exception {
        assertThat(CampaignPlanStore.class.getMethod("store", CampaignPlan.class)).isNotNull();
        assertThat(CampaignPlan.class.getConstructors()).isEmpty();
        assertThat(store.find("x")).isEmpty();
    }

    private CampaignPlan compile(String value) {
        return compiler.compile(request(value));
    }

    private CompileRequest request(String value) {
        return CompileRequest.edit(
                BuilderFixtures.editProposal().pins(catalog, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", value)).build(),
                BuilderFixtures.current(12L, 3L, null, List.of(current())), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context());
    }

    private ResolvedComponent current() {
        return BuilderFixtures.component(
                "a", SEGMENT, VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "base"));
    }
}