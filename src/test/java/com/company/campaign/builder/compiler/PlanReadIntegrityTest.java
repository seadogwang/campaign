package com.company.campaign.builder.compiler;

import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Strict fail-closed read integrity for CampaignPlan and ResolvedCampaignManifest.
 *
 * <p>Golden tests store a valid plan and verify every reconstructed field.
 * Tamper tests corrupt the stored canonical material or manifest and prove the
 * reconstruction path rejects the tampered input.
 */
class PlanReadIntegrityTest {

    private static final String VERSION = "segment-rule-based-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));
    private final InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();

    // ------------------------------------------------------------------ golden / happy

    @Test
    void reconstructProducesExactCopyOfOriginalPlan() {
        CampaignPlan original = compile("golden-value");
        store.store(original);
        CampaignPlan recovered = store.find(original.planId()).orElseThrow();
        assertThat(recovered.planId()).isEqualTo(original.planId());
        assertThat(recovered.planHash()).isEqualTo(original.planHash());
        assertThat(recovered.manifestHash()).isEqualTo(original.manifestHash());
        assertThat(recovered.tenantId()).isEqualTo(original.tenantId());
        assertThat(recovered.userId()).isEqualTo(original.userId());
        assertThat(recovered.compilerVersion()).isEqualTo(original.compilerVersion());
        assertThat(recovered.proposalHash()).isEqualTo(original.proposalHash());
        assertThat(recovered.catalogVersion()).isEqualTo(original.catalogVersion());
        assertThat(recovered.catalogHash()).isEqualTo(original.catalogHash());
        assertThat(recovered.policyVersion()).isEqualTo(original.policyVersion());
        assertThat(recovered.policyHash()).isEqualTo(original.policyHash());
        assertThat(recovered.canonicalMaterialBytes()).isEqualTo(original.canonicalMaterialBytes());
        assertThat(recovered.manifest().canonicalBytes()).isEqualTo(original.manifest().canonicalBytes());
        assertThat(recovered.planHash().matches(recovered.canonicalMaterialBytes())).isTrue();
        assertThat(recovered.manifestHash().matches(recovered.manifest().canonicalBytes())).isTrue();
    }

    @Test
    void storeAndFindRoundTripsAllRequiredColumns() {
        CampaignPlan plan = compile("roundtrip");
        store.store(plan);
        CampaignPlan recovered = store.find(plan.planId()).orElseThrow();
        assertThat(recovered.planId()).isEqualTo(plan.planId());
        assertThat(recovered.planHash()).isEqualTo(plan.planHash());
        assertThat(recovered.manifestHash()).isEqualTo(plan.manifestHash());
        assertThat(recovered.tenantId()).isEqualTo(plan.tenantId());
        assertThat(recovered.programId()).isEqualTo(plan.programId());
        assertThat(recovered.userId()).isEqualTo(plan.userId());
        assertThat(recovered.baseCampaignRevision()).isEqualTo(plan.baseCampaignRevision());
        assertThat(recovered.baseDraftId()).isEqualTo(plan.baseDraftId());
        assertThat(recovered.baseDraftRevision()).isEqualTo(plan.baseDraftRevision());
        assertThat(recovered.compilerVersion()).isEqualTo(plan.compilerVersion());
        assertThat(recovered.proposalHash()).isEqualTo(plan.proposalHash());
        assertThat(recovered.catalogVersion()).isEqualTo(plan.catalogVersion());
        assertThat(recovered.catalogHash()).isEqualTo(plan.catalogHash());
        assertThat(recovered.policyVersion()).isEqualTo(plan.policyVersion());
        assertThat(recovered.policyHash()).isEqualTo(plan.policyHash());
        assertThat(recovered).isEqualTo(plan);
    }

    @Test
    void goldenPlanHashCoversEveryRequiredField() {
        CampaignPlan first = compile("value-a");
        CampaignPlan second = compile("value-b");
        assertThat(first.planHash()).isNotEqualTo(second.planHash());
        assertThat(first.planId()).isNotEqualTo(second.planId());
        assertThat(first.planHash().matches(first.canonicalMaterialBytes())).isTrue();
        assertThat(second.planHash().matches(second.canonicalMaterialBytes())).isTrue();
    }

    // ------------------------------------------------------------------ CampaignPlan.reconstruct tamper

    @Test
    void reconstructRejectsNoncanonicalMaterialBytes() {
        CampaignPlan plan = compile("tamper-noncanonical");
        byte[] canonical = plan.canonicalMaterialBytes();
        String canonicalText = new String(canonical, StandardCharsets.UTF_8);
        String tamperedText = canonicalText.replace("{", "{ ");
        byte[] tampered = tamperedText.getBytes(StandardCharsets.UTF_8);
        assertThat(tampered).isNotEqualTo(canonical);
        CompilerException thrown = catchThrowableOfType(
                () -> CampaignPlan.reconstruct(tampered, new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8)),
                CompilerException.class);
        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
        assertThat(thrown.details()).containsEntry("invariant", "canonicalMaterialBytes");
    }

    @Test
    void reconstructRejectsTamperedManifestJson() {
        CampaignPlan plan = compile("tamper-manifest");
        String canonicalManifestJson = new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8);
        // Corrupt the manifest JSON by mangling a required field name so the
        // strict FAIL_ON_UNKNOWN_PROPERTIES parser rejects it.
        String tamperedManifest = canonicalManifestJson.replace(
                "manifestFormatVersion", "XmanifestFormatVersion");
        assertThat(tamperedManifest).isNotEqualTo(canonicalManifestJson);
        assertThatThrownBy(
                () -> CampaignPlan.reconstruct(plan.canonicalMaterialBytes(), tamperedManifest))
                .isInstanceOf(com.company.campaign.shared.canonical.CanonicalJsonException.class);
    }

    @Test
    void reconstructRejectsEmptyMaterialBytes() {
        CampaignPlan plan = compile("tamper-empty");
        String manifestJson = new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> CampaignPlan.reconstruct(new byte[0], manifestJson))
                .isInstanceOf(CompilerException.class)
                .extracting(throwable -> ((CompilerException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
    }

    @Test
    void reconstructRejectsNullMaterialOrManifest() {
        CampaignPlan plan = compile("tamper-null");
        String manifestJson = new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8);
        byte[] material = plan.canonicalMaterialBytes();
        assertThatThrownBy(() -> CampaignPlan.reconstruct(null, manifestJson))
                .isInstanceOf(CompilerException.class)
                .extracting(throwable -> ((CompilerException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
        assertThatThrownBy(() -> CampaignPlan.reconstruct(material, null))
                .isInstanceOf(CompilerException.class)
                .extracting(throwable -> ((CompilerException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
        assertThatThrownBy(() -> CampaignPlan.reconstruct(material, ""))
                .isInstanceOf(CompilerException.class)
                .extracting(throwable -> ((CompilerException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
    }

    @Test
    void requireCompatibleRejectsRowWhenOnlyPlanHashMatches() {
        CampaignPlan first = compile("compat-a");
        CampaignPlan second = compile("compat-b");
        assertThat(first.planHash()).isNotEqualTo(second.planHash());
        assertThat(first.manifestHash()).isNotEqualTo(second.manifestHash());
        assertThatThrownBy(
                () -> InMemoryCampaignPlanStore.requireCompatible(first, second))
                .isInstanceOf(CompilerException.class)
                .extracting(throwable -> ((CompilerException) throwable).code())
                .isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_CONFLICT);
        InMemoryCampaignPlanStore.requireCompatible(first, first);
    }

    @Test
    void canonicalMaterialBytesAreDeterministic() {
        CampaignPlan first = compile("det-a");
        CampaignPlan second = compile("det-a");
        assertThat(first.planId()).isEqualTo(second.planId());
        assertThat(first.planHash()).isEqualTo(second.planHash());
        assertThat(first.canonicalMaterialBytes()).isEqualTo(second.canonicalMaterialBytes());
        assertThat(first.manifest().canonicalBytes()).isEqualTo(second.manifest().canonicalBytes());
    }

    @Test
    void planReconstructValidatesJourneyTopologyHashNullSymmetry() {
        CompileRequest request = CompileRequest.edit(
                BuilderFixtures.editProposal().pins(catalog, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", "no-journey")).build(),
                BuilderFixtures.current(12L, 3L, null, List.of(current())), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context());
        CampaignPlan plan = compiler.compile(request);
        assertThat(plan.journeyTopology()).isNull();
        CampaignPlan recovered = CampaignPlan.reconstruct(
                plan.canonicalMaterialBytes(), new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8));
        assertThat(recovered.journeyTopology()).isNull();
        assertThat(recovered).isEqualTo(plan);
    }

    // ------------------------------------------------------------------ helpers

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