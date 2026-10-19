package com.company.campaign.persistence;

import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.CompilerException;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.FixtureCapability;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import com.company.campaign.persistence.entity.CampaignPlanEntity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static com.company.campaign.builder.testsupport.BuilderFixtures.SEGMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class PostgresCampaignPlanStoreReadIntegrityTest {

    private static final String VERSION = "segment-rule-based-1.0";

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();
    private final FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(
            FixtureCapability.passThrough(SEGMENT, VERSION, FixtureCapability.ordered("segment")));

    @Test
    void verifyReadIntegrityRejectsTamperedTenantId() {
        CampaignPlan plan = compile("tenant-tamper");
        CampaignPlanEntity entity = matchingEntity(plan);
        entity.setTenantId("tampered-tenant");
        CompilerException thrown = catchThrowableOfType(
                () -> PostgresCampaignPlanStore.verifyReadIntegrity(entity, plan),
                CompilerException.class);
        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
        assertThat(thrown.details()).containsEntry("invariant", "tenantId");
    }

    @Test
    void verifyReadIntegrityPassesWhenEveryColumnMatches() {
        CampaignPlan plan = compile("happy-match");
        CampaignPlanEntity entity = matchingEntity(plan);
        assertThatCode(() -> PostgresCampaignPlanStore.verifyReadIntegrity(entity, plan))
                .doesNotThrowAnyException();
    }

    private CampaignPlan compile(String value) {
        return compiler.compile(CompileRequest.edit(
                BuilderFixtures.editProposal().pins(catalog, policy)
                        .upsert(SEGMENT, "a", Map.of("spec", value)).build(),
                BuilderFixtures.current(12L, 3L, null, List.of(current())), catalog, policy,
                BuilderFixtures.instants(), BuilderFixtures.context()));
    }

    private ResolvedComponent current() {
        return BuilderFixtures.component(
                "a", SEGMENT, VERSION, FixtureCapability.ordered("segment"), Map.of("spec", "base"));
    }

    private static CampaignPlanEntity matchingEntity(CampaignPlan plan) {
        CampaignPlanEntity entity = new CampaignPlanEntity();
        entity.setPlanId(plan.planId());
        entity.setPlanHash(plan.planHash().value());
        entity.setManifestHash(plan.manifestHash().value());
        entity.setCanonicalMaterialBytes(plan.canonicalMaterialBytes());
        entity.setManifestJson(
                new String(plan.manifest().canonicalBytes(), StandardCharsets.UTF_8));
        entity.setTenantId(plan.tenantId());
        entity.setUserId(plan.userId());
        entity.setCompilerVersion(plan.compilerVersion());
        entity.setBaseCampaignRevision(plan.baseCampaignRevision());
        entity.setBaseDraftId(plan.baseDraftId());
        entity.setBaseDraftRevision(plan.baseDraftRevision());
        entity.setProposalHash(plan.proposalHash().value());
        entity.setCatalogVersion(plan.catalogVersion());
        entity.setCatalogHash(plan.catalogHash().value());
        entity.setPolicyVersion(plan.policyVersion());
        entity.setPolicyHash(plan.policyHash().value());
        return entity;
    }
}
