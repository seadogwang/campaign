package com.company.campaign.builder.capability.message;

import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentAssets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for inline-draft template resolution, exercising the production
 * {@link MessageInlineTemplateResolver#resolve} method directly.
 *
 * <p>The resolution iterates the frozen catalog and selects the first enabled,
 * runtime-available template version whose {@code channel} equals the requested channel.
 * An absent, disabled, unavailable or channel-mismatched version returns empty rather
 * than throwing, so the caller can produce a component-owned
 * {@code CampaignSkillOutcome.NeedsInput} clarification.
 *
 * <p>These tests prove the logic at the catalog level:
 * <ol>
 *   <li>WECHAT and SMS inline channels resolve to their respective template versions
 *       with {@code referenceCode}, a {@code bodyText} variable and the correct channel.</li>
 *   <li>A channel for which no enabled, runtime-available template exists — an
 *       unsupported channel, a disabled template, or an absent catalog — produces an
 *       empty result, never an exception.</li>
 * </ol>
 */
class MessageInlineDraftResolutionTest {

    // ---- supported inline channels -------------------------------------------------

    @Test
    void wechatInlineTemplateResolvesFromTheDevelopmentCatalog() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();
        Optional<MessageTemplateVersion> resolved =
                MessageInlineTemplateResolver.resolve(catalog, "WECHAT");

        assertThat(resolved).isPresent();
        MessageTemplateVersion version = resolved.get();
        assertThat(version.referenceCode())
                .isEqualTo(DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_WECHAT);
        assertThat(version.channel()).isEqualTo("WECHAT");
        assertThat(version.enabled()).isTrue();
        assertThat(version.runtimeAvailable()).isTrue();
        assertThat(version.variables()).hasSize(1);
        assertThat(version.variables().get(0).name()).isEqualTo("bodyText");
        assertThat(version.variables().get(0).type()).isEqualTo(CapabilityValueKind.STRING);
    }

    @Test
    void smsInlineTemplateResolvesFromTheDevelopmentCatalog() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();
        Optional<MessageTemplateVersion> resolved =
                MessageInlineTemplateResolver.resolve(catalog, "SMS");

        assertThat(resolved).isPresent();
        MessageTemplateVersion version = resolved.get();
        assertThat(version.referenceCode())
                .isEqualTo(DevelopmentCampaignAgentAssets.TEMPLATE_REFERENCE_SMS);
        assertThat(version.channel()).isEqualTo("SMS");
        assertThat(version.enabled()).isTrue();
        assertThat(version.runtimeAvailable()).isTrue();
        assertThat(version.variables()).hasSize(1);
        assertThat(version.variables().get(0).name()).isEqualTo("bodyText");
    }

    // ---- missing/disabled/unavailable channels produce empty ----------------------

    @Test
    void unsupportedChannelReturnsEmptyFromTheDevelopmentCatalog() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();
        // PUSH and IN_APP channels have no matching template in the dev catalog.
        assertThat(MessageInlineTemplateResolver.resolve(catalog, "PUSH")).isEmpty();
        assertThat(MessageInlineTemplateResolver.resolve(catalog, "IN_APP")).isEmpty();
        assertThat(MessageInlineTemplateResolver.resolve(catalog, "EMAIL")).isPresent();
    }

    @Test
    void disabledTemplateReturnsEmpty() {
        MessageTemplateVersion disabled = MessageTemplateVersion.of(
                "tpl-push-disabled", "tpl-push-disabled", "tpl-push-disabled-v1", "PUSH",
                List.of(), false, true, MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-disabled-catalog", List.of(disabled));

        assertThat(MessageInlineTemplateResolver.resolve(catalog, "PUSH")).isEmpty();
    }

    @Test
    void runtimeUnavailableTemplateReturnsEmpty() {
        MessageTemplateVersion unavailable = MessageTemplateVersion.of(
                "tpl-push-unavailable", "tpl-push-unavailable", "tpl-push-unavailable-v1",
                "PUSH", List.of(), true, false, MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-unavailable-catalog", List.of(unavailable));

        assertThat(MessageInlineTemplateResolver.resolve(catalog, "PUSH")).isEmpty();
    }

    @Test
    void nullCatalogReturnsEmpty() {
        assertThat(MessageInlineTemplateResolver.resolve(null, "WECHAT")).isEmpty();
    }

    @Test
    void channelDoesNotMatchEvenWhenEnabled() {
        // An EMAIL template should not match a request for WECHAT.
        MessageTemplateVersion emailOnly = MessageTemplateVersion.of(
                "tpl-email", "tpl-email", "tpl-email-v1", "EMAIL",
                List.of(), true, true, MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-email-catalog", List.of(emailOnly));

        assertThat(MessageInlineTemplateResolver.resolve(catalog, "EMAIL")).isPresent();
        assertThat(MessageInlineTemplateResolver.resolve(catalog, "WECHAT")).isEmpty();
        assertThat(MessageInlineTemplateResolver.resolve(catalog, "SMS")).isEmpty();
    }
}