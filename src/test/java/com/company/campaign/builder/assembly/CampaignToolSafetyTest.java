package com.company.campaign.builder.assembly;

import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 5: a safe result contains no template body, reward secret, Provider setting, credential, SQL,
 * member/contact identifier or raw exception text.
 */
class CampaignToolSafetyTest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private static final List<String> RESTRICTED_TOKENS = List.of(
            "provider", "credential", "password", "apikey", "jdbc", "sql", "select ", "memberId",
            "contactId", "tenantId", "dataScope", "secret", "inventory", "templateBody", "bodyHtml");

    private static String textOf(Object value) {
        return new String(CANONICAL_JSON.write(value), StandardCharsets.UTF_8);
    }

    @Test
    void thePublicMessageResultCarriesNeitherTheSuppliedValueNorRestrictedTokens() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                CampaignToolCatalog.standard(), CampaignSkillIds.MESSAGE_TEMPLATE,
                StandardCampaignToolHandlers.all(), CampaignAssemblyFixtures.runContext());
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "SENTINEL-VALUE"))));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-4",
                new CampaignToolRequest.MessagePreviewSafe(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "SENTINEL-VALUE"))));

        String publicText = textOf(admission.result());
        assertThat(publicText).doesNotContain("SENTINEL-VALUE");
        for (String token : RESTRICTED_TOKENS) {
            assertThat(publicText.toLowerCase()).as(token).doesNotContain(token.toLowerCase());
        }
        assertThat(publicText).doesNotContain("\"variables\"");
    }

    @Test
    void thePublicPromotionResultCarriesNoSecretInventoryOrProviderFact() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                CampaignToolCatalog.standard(), CampaignSkillIds.PROMOTION_REFERENCE,
                StandardCampaignToolHandlers.all(), CampaignAssemblyFixtures.runContext());
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.RewardDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.RewardResolve(CampaignAssemblyFixtures.REWARD_REFERENCE)));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.RewardPreviewSafe(
                        CampaignAssemblyFixtures.REWARD_REFERENCE, 3, new java.math.BigDecimal("100.0000"))));

        String publicText = textOf(admission.result());
        for (String token : RESTRICTED_TOKENS) {
            assertThat(publicText.toLowerCase()).as(token).doesNotContain(token.toLowerCase());
        }
    }

    @Test
    void aThrowingHandlerNeverLeaksItsRawMessageOrRestrictedText() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                CampaignToolCatalog.standard(), CampaignSkillIds.MESSAGE_TEMPLATE,
                Map.of(CampaignToolIds.MESSAGE_TEMPLATE_DISCOVER, (run, request) -> {
                    throw new IllegalStateException(
                            "jdbc:select * from member where memberId='member-1' credential=secret");
                }), CampaignAssemblyFixtures.runContext());
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 8)));

        String failureText = textOf(admission.failure()) + textOf(invoker.trace());
        assertThat(admission.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(failureText).doesNotContain("member-1");
        assertThat(failureText).doesNotContain("jdbc:select");
        assertThat(failureText).doesNotContain("credential=secret");
    }

    @Test
    void aSafeCardPrintsOnlyBoundedCodesAndHashes() {
        CampaignToolInvoker invoker = new CampaignToolInvoker(
                CampaignToolCatalog.standard(), CampaignSkillIds.MESSAGE_TEMPLATE,
                StandardCampaignToolHandlers.all(), CampaignAssemblyFixtures.runContext());
        invoker.invoke(CampaignAssemblyFixtures.call("call-1",
                new CampaignToolRequest.MessageDiscover(null, 64)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-2",
                new CampaignToolRequest.MessageResolve(CampaignAssemblyFixtures.TEMPLATE_REFERENCE)));
        invoker.invoke(CampaignAssemblyFixtures.call("call-3",
                new CampaignToolRequest.MessageVariablesValidate(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "SENTINEL-VALUE"))));
        CampaignToolAdmission admission = invoker.invoke(CampaignAssemblyFixtures.call("call-4",
                new CampaignToolRequest.MessagePreviewSafe(
                        CampaignAssemblyFixtures.TEMPLATE_REFERENCE,
                        CampaignAssemblyFixtures.variables("firstName", "SENTINEL-VALUE"))));
        CampaignToolArtifact.MessageSafePreview preview = (CampaignToolArtifact.MessageSafePreview) admission.claim(
                "call-4", CampaignToolIds.MESSAGE_PREVIEW_SAFE, CampaignToolCatalog.standard().catalogHash());

        assertThat(preview.card().toString()).doesNotContain("SENTINEL-VALUE");
        assertThat(preview.card().normalizedPayloadHash().value()).matches("sha256:[0-9a-f]{64}");
        assertThat(preview.card().toString().toLowerCase()).doesNotContain("provider");
    }
}