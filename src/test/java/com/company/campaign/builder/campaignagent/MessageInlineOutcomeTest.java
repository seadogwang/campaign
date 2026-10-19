package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.message.MessageInlineTemplateResolver;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentAssets;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that an empty inline-template resolution becomes
 * {@link CampaignSkillOutcome.NeedsInput} through the production decision path.
 *
 * <p>The test calls the extracted package-private
 * {@link CampaignSkillRouter#resolveInlineTemplateOrClarify} method — the
 * same production logic the {@code MessageRunner} uses — and asserts the
 * outcome shape, skill id, and bounded error/question codes.
 *
 * <p>The test also proves the decision is never a {@link CampaignSkillOutcome.Terminal}
 * and never throws an exception.
 */
class MessageInlineOutcomeTest {

    // ---- positive: template exists — decision continues -------------------------------

    @Test
    void existingTemplateProducesEmptyDecisionSoCallerContinues() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();

        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "EMAIL");

        assertThat(result).describedAs("an existing template should not produce a clarification")
                .isEmpty();
    }

    // ---- negative: empty resolution → NeedsInput -------------------------------------

    @Test
    void unsupportedChannelBecomesNeedsInputWithMessageSkillId() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();

        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "PUSH");

        assertThat(result).describedAs("an unsupported channel must produce a clarification").isPresent();
        CampaignSkillOutcome outcome = result.get();

        assertThat(outcome)
                .describedAs("the outcome must be NeedsInput, not Terminal or any other shape")
                .isInstanceOf(CampaignSkillOutcome.NeedsInput.class);
        assertThat(outcome.skillId())
                .isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);

        CampaignSkillOutcome.NeedsInput needsInput = (CampaignSkillOutcome.NeedsInput) outcome;
        assertThat(needsInput.code())
                .describedAs("the error code must be bounded and machine-readable")
                .isEqualTo(CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED);
        assertThat(needsInput.questionCodes())
                .describedAs("the question codes must name a bounded clarification question")
                .containsExactly("MESSAGE_TEMPLATE_REQUIRED");
        assertThat(needsInput.options())
                .describedAs("a free-text clarification carries no selectable options")
                .isEmpty();
        assertThat(needsInput.freeTextAllowed())
                .describedAs("a message-reference clarification permits free-text input")
                .isTrue();
    }

    @Test
    void nullCatalogBecomesNeedsInputNotException() {
        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(null, "WECHAT");

        assertThat(result).describedAs("a null catalog must produce a clarification, not a crash").isPresent();
        CampaignSkillOutcome outcome = result.get();

        assertThat(outcome)
                .isInstanceOf(CampaignSkillOutcome.NeedsInput.class);
        assertThat(outcome.skillId())
                .isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);

        CampaignSkillOutcome.NeedsInput needsInput = (CampaignSkillOutcome.NeedsInput) outcome;
        assertThat(needsInput.code())
                .isEqualTo(CampaignAgentErrorCodes.MESSAGE_INPUT_REQUIRED);
        assertThat(needsInput.questionCodes())
                .containsExactly("MESSAGE_TEMPLATE_REQUIRED");
    }

    @Test
    void disabledTemplateBecomesNeedsInput() {
        MessageTemplateVersion disabled = MessageTemplateVersion.of(
                "tpl-sms-disabled", "tpl-sms-disabled", "tpl-sms-disabled-v1", "SMS",
                List.of(), false, true,
                com.company.campaign.builder.capability.catalog.MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-disabled-catalog", List.of(disabled));

        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "SMS");

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CampaignSkillOutcome.NeedsInput.class);
        assertThat(result.get().skillId()).isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);
    }

    @Test
    void runtimeUnavailableTemplateBecomesNeedsInput() {
        MessageTemplateVersion unavailable = MessageTemplateVersion.of(
                "tpl-wechat-unavailable", "tpl-wechat-unavailable", "tpl-wechat-unavailable-v1",
                "WECHAT", List.of(), true, false,
                com.company.campaign.builder.capability.catalog.MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-unavailable-catalog", List.of(unavailable));

        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "WECHAT");

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CampaignSkillOutcome.NeedsInput.class);
        assertThat(result.get().skillId()).isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);
    }

    @Test
    void channelMismatchBecomesNeedsInput() {
        MessageTemplateVersion emailOnly = MessageTemplateVersion.of(
                "tpl-email", "tpl-email", "tpl-email-v1", "EMAIL",
                List.of(), true, true,
                com.company.campaign.builder.capability.catalog.MessageClassification.PUBLIC);
        MessageTemplateCatalog catalog = MessageTemplateCatalog.of(
                "tpl-email-catalog", List.of(emailOnly));

        Optional<CampaignSkillOutcome> result =
                CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "SMS");

        assertThat(result).isPresent();
        assertThat(result.get()).isInstanceOf(CampaignSkillOutcome.NeedsInput.class);
        assertThat(result.get().skillId()).isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);
    }

    // ---- safety: empty resolution is never Terminal and never throws ------------------

    @Test
    void emptyResolutionIsNeverTerminal() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();

        for (String channel : List.of("PUSH", "IN_APP")) {
            Optional<CampaignSkillOutcome> result =
                    CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, channel);

            assertThat(result).describedAs("channel %s must produce a clarification", channel).isPresent();
            assertThat(result.get())
                    .describedAs("channel %s must not be Terminal", channel)
                    .isNotInstanceOf(CampaignSkillOutcome.Terminal.class);
            assertThat(result.get().skillId())
                    .isEqualTo(CampaignSkillIds.MESSAGE_TEMPLATE);
        }
    }

    @Test
    void emptyResolutionDoesNotThrow() {
        MessageTemplateCatalog catalog = DevelopmentCampaignAgentAssets.messageCatalog();

        // These channels have no matching template in the development catalog —
        // the production decision must not throw.
        CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "PUSH");
        CampaignSkillRouter.resolveInlineTemplateOrClarify(catalog, "IN_APP");
        CampaignSkillRouter.resolveInlineTemplateOrClarify(null, "WECHAT");
        CampaignSkillRouter.resolveInlineTemplateOrClarify(null, null);
    }
}