package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.capability.catalog.RewardVersion;
import com.company.campaign.contracts.Disposition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 4: Promotion discovery, exact resolution, exposure calculation and safe preview run through the
 * accepted immutable catalog and capability pack.
 */
class PromotionToolFlowTest {

    private static final BigDecimal CEILING = new BigDecimal("100.0000");

    private static CampaignToolInvoker invoker() {
        return new CampaignToolInvoker(CampaignToolCatalog.standard(), CampaignSkillIds.PROMOTION_REFERENCE,
                StandardCampaignToolHandlers.all(), CampaignAssemblyFixtures.runContext());
    }

    private static void warmUp(CampaignToolInvoker invoker) {
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.RewardDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.RewardResolve(CampaignAssemblyFixtures.REWARD_REFERENCE)));
    }

    @Test
    void discoveryAndResolutionRunThroughTheFrozenCatalog() {
        CampaignToolInvoker invoker = invoker();
        CampaignToolAdmission discovered = invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.RewardDiscover(null, 8)));
        assertThat(discovered.resolved()).isTrue();
        CampaignToolArtifact.RewardDiscovery discovery = (CampaignToolArtifact.RewardDiscovery) discovered.claim(
                "call-1", CampaignToolIds.REWARD_DISCOVER, CampaignToolCatalog.standard().catalogHash());
        assertThat(discovery.matches()).hasSize(1);
        assertThat(discovery.matches().get(0).kindCode()).isEqualTo("POINTS");

        CampaignToolAdmission resolved = invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.RewardResolve(CampaignAssemblyFixtures.REWARD_REFERENCE)));
        RewardVersion version = ((CampaignToolArtifact.RewardResolution) resolved.claim(
                "call-2", CampaignToolIds.REWARD_RESOLVE, CampaignToolCatalog.standard().catalogHash())).version();
        assertThat(version.currency()).isEqualTo("CNY");
        assertThat(version.faceValue()).isEqualByComparingTo("10.0000");
    }

    @Test
    void exposureIsDeterministicFromFaceValueQuantityAndTheExplicitGovernanceCeiling() {
        CampaignToolInvoker invoker = invoker();
        warmUp(invoker);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.RewardExposureCalculate(
                        CampaignAssemblyFixtures.REWARD_REFERENCE, 3, CEILING)));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact.RewardExposure exposure = (CampaignToolArtifact.RewardExposure) admission.claim(
                "call-3", CampaignToolIds.REWARD_EXPOSURE_CALCULATE,
                CampaignToolCatalog.standard().catalogHash());
        assertThat(exposure.perRecipientExposure()).isEqualByComparingTo("10.0000");
        assertThat(exposure.maximumExposure()).isEqualByComparingTo("30.0000");
    }

    @Test
    void theGovernanceCeilingBoundsTheDeclaredMaximumExposure() {
        CampaignToolInvoker invoker = invoker();
        warmUp(invoker);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.RewardExposureCalculate(
                        CampaignAssemblyFixtures.REWARD_REFERENCE, 5, new BigDecimal("20.0000"))));

        CampaignToolArtifact.RewardExposure exposure = (CampaignToolArtifact.RewardExposure) admission.claim(
                "call-3", CampaignToolIds.REWARD_EXPOSURE_CALCULATE,
                CampaignToolCatalog.standard().catalogHash());
        assertThat(exposure.maximumExposure()).isEqualByComparingTo("20.0000");
    }

    @Test
    void anAmountOverTheAcceptedPerGrantMaximumFailsClosed() {
        CampaignToolInvoker invoker = invoker();
        warmUp(invoker);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.RewardExposureCalculate(
                        CampaignAssemblyFixtures.REWARD_REFERENCE, 20, new BigDecimal("1000.0000"))));

        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(admission.failure().code()).isEqualTo(CampaignAssemblyErrorCodes.TOOL_EXPOSURE_INVALID);
    }

    @Test
    void safePreviewProjectsKindCurrencyBoundedExposureAndHashesOnly() {
        CampaignToolInvoker invoker = invoker();
        warmUp(invoker);
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.RewardPreviewSafe(
                        CampaignAssemblyFixtures.REWARD_REFERENCE, 3, CEILING)));

        assertThat(admission.resolved()).isTrue();
        CampaignToolArtifact.RewardSafePreview preview = (CampaignToolArtifact.RewardSafePreview) admission.claim(
                "call-3", CampaignToolIds.REWARD_PREVIEW_SAFE, CampaignToolCatalog.standard().catalogHash());
        RewardSafePreviewCard card = preview.card();
        assertThat(card.kind()).isEqualTo("POINTS");
        assertThat(card.currency()).isEqualTo("CNY");
        assertThat(card.quantity()).isEqualTo(3);
        assertThat(card.maximumExposure()).isEqualByComparingTo("30.0000");
        assertThat(card.normalizedPayloadHash()).isEqualTo(preview.normalized().contentHash());
        assertThat(card.catalogHash()).isEqualTo(CampaignAssemblyFixtures.rewardCatalog().catalogHash());
    }
}