package com.company.campaign.builder.capability.promotion;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.RewardCatalog;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.builder.compiler.CampaignCompiler;
import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.builder.compiler.CompileRequest;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.builder.testsupport.RecordingAuthorizationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 sections 2 and 4: the concrete {@code promotion.reference} pack through the accepted compiler.
 *
 * <p>The pack is an ordinary frozen capability contract, so a resolved component and its one bound generic
 * capability preview card reach the {@code CampaignPlan} with no compiler change at all.
 */
class PromotionReferencePackCompilerTest {

    private final RecordingAuthorizationService authorization = new RecordingAuthorizationService();
    private final CampaignCompiler compiler = new CampaignCompiler(authorization);
    private final FrozenAuthoringPolicy policy = BuilderFixtures.policy();

    private final PromotionReferencePack pack = new PromotionReferencePack(RewardCatalog.of("rw-catalog-1",
            List.of(RewardVersion.of("rw-points-100", "rw-points-100", "rw-points-100-v1", "POINTS", "USD",
                    new BigDecimal("10.00"), new BigDecimal("100.00"), true, true))));

    @Test
    void aResolvedPromotionComponentAndItsBoundCardReachThePlan() {
        FrozenCapabilityCatalog catalog = BuilderFixtures.catalog(pack);
        CampaignPlan plan = compiler.compile(CompileRequest.create(
                BuilderFixtures.createProposal().pins(catalog, policy)
                        .upsertNew("promotion.reference", "promotion-new",
                                Map.of("referenceCode", "rw-points-100", "quantity", 3))
                        .build(),
                catalog, policy, BuilderFixtures.instants(), BuilderFixtures.context()));

        assertThat(plan.manifest().components()).hasSize(1);
        ResolvedComponent component = plan.manifest().components().get(0);
        assertThat(component.capabilityCode()).isEqualTo("promotion.reference");
        assertThat(component.contractVersion()).isEqualTo("1.0");
        assertThat(component.slot().code()).isEqualTo("campaign.promotion");
        assertThat(component.references()).containsExactly(
                new ResolvedCatalogReference("rw-points-100", "REWARD", "rw-points-100", "rw-points-100-v1"));

        assertThat(plan.capabilityPreviewCards()).hasSize(1);
        CapabilityPlanPreviewCard card = plan.capabilityPreviewCards().get(0);
        assertThat(card.componentLogicalId()).isEqualTo(component.logicalId());
        assertThat(card.capabilityCode()).isEqualTo("promotion.reference");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(component.value().contentHash());
        assertThat(card.runtimeCompatibility()).isEqualTo(pack.runtimeCompatibility());
        assertThat(card.hasResolvedCatalogReference()).isTrue();
        assertThat(plan.previewCards()).isEmpty();
    }
}