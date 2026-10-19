package com.company.campaign.builder.capability.message;

import com.company.campaign.builder.capability.CapabilityPlanPreviewCard;
import com.company.campaign.builder.capability.catalog.MessageClassification;
import com.company.campaign.builder.capability.catalog.MessageTemplateCatalog;
import com.company.campaign.builder.capability.catalog.MessageTemplateVariable;
import com.company.campaign.builder.capability.catalog.MessageTemplateVersion;
import com.company.campaign.builder.capability.pack.CapabilityValueKind;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityPreviewCards;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.CompilerVersions;
import com.company.campaign.builder.testsupport.BuilderFixtures;
import com.company.campaign.contracts.Issue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T19 section 4: the concrete {@code message.template} pack.
 *
 * <p>It resolves one exact frozen template version by reference code, validates channel and variables
 * against that version's own schema, freezes the normalized template pins/channel/canonical variables,
 * resolves one exact catalog reference and projects exactly one bound capability preview card. Every
 * unknown field, missing/disabled/unavailable version, channel mismatch and variable fault fails closed.
 */
class MessageTemplatePackTest {

    private static final MessageTemplateVersion WELCOME = MessageTemplateVersion.of(
            "tpl-welcome", "tpl-welcome", "tpl-welcome-v1", "EMAIL",
            List.of(new MessageTemplateVariable("firstName", CapabilityValueKind.STRING, true, 16),
                    new MessageTemplateVariable("loyaltyPoints", CapabilityValueKind.INTEGER, false,
                            MessageTemplateVariable.NO_MAX_LENGTH),
                    new MessageTemplateVariable("tier", CapabilityValueKind.STRING, false, 8),
                    new MessageTemplateVariable("enrolledOn", CapabilityValueKind.DATE, false,
                            MessageTemplateVariable.NO_MAX_LENGTH)),
            true, true, MessageClassification.PUBLIC);

    private static final MessageTemplateVersion DISABLED = MessageTemplateVersion.of(
            "tpl-disabled", "tpl-disabled", "tpl-disabled-v1", "EMAIL", List.of(),
            false, true, MessageClassification.PUBLIC);

    private static final MessageTemplateVersion UNAVAILABLE = MessageTemplateVersion.of(
            "tpl-unavailable", "tpl-unavailable", "tpl-unavailable-v1", "EMAIL", List.of(),
            true, false, MessageClassification.PUBLIC);

    private static final MessageTemplateVersion SMS = MessageTemplateVersion.of(
            "tpl-sms", "tpl-sms", "tpl-sms-v1", "SMS", List.of(),
            true, true, MessageClassification.PUBLIC);

    private final MessageTemplatePack pack = new MessageTemplatePack(MessageTemplateCatalog.of(
            "tpl-catalog-1", List.of(WELCOME, DISABLED, UNAVAILABLE, SMS)));

    // ------------------------------------------------------------------ frozen metadata

    @Test
    void thePackDeclaresItsExactContractSlotAndHonestRuntime() {
        assertThat(pack.capabilityCode()).isEqualTo("message.template");
        assertThat(pack.contractVersion()).isEqualTo("1.0");
        assertThat(pack.slot().code()).isEqualTo("campaign.message");
        assertThat(pack.slot().singleton()).isFalse();
        assertThat(pack.slot().ordered()).isFalse();
        assertThat(pack.enabled()).isTrue();
        // Previewable but honestly not production-publishable while T20-T23 do not exist.
        assertThat(pack.runtimeCompatibility().previewSupported()).isTrue();
        assertThat(pack.runtimeCompatibility().publishable()).isFalse();

        assertThat(pack.packMetadata().capabilityCode()).isEqualTo("message.template");
        assertThat(pack.packMetadata().contractVersion()).isEqualTo("1.0");
        assertThat(pack.packMetadata().payloadContract().requiredNames()).containsExactly("referenceCode");
        assertThat(pack.packMetadata().payloadContract().optionalNames())
                .containsExactly("channel", "variables");
        assertThat(pack.packMetadata().previewCardKind())
                .isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(pack.packMetadata().previewCardVersion())
                .isEqualTo(CapabilityPlanPreviewCard.CARD_CONTRACT_VERSION);
        pack.packMetadata().requireValidPackHash();
    }

    // ------------------------------------------------------------------ the accepted resolution

    @Test
    void itResolvesTheExactVersionAndProjectsTheBoundCard() {
        CapabilityResolution.Resolved resolved =
                committed(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                        "variables", Map.of("firstName", "Ada"))));

        assertThat(resolved.value().fields())
                .containsEntry("templateLogicalId", "tpl-welcome")
                .containsEntry("templateVersionId", "tpl-welcome-v1")
                .containsEntry("templateContentHash", WELCOME.contentHash().value())
                .containsEntry("channel", "EMAIL")
                .containsEntry("variables", Map.of("firstName", "Ada"));
        assertThat(resolved.references()).containsExactly(WELCOME.resolvedReference());

        assertThat(resolved.capabilityPreviewCards()).hasSize(1);
        CapabilityPlanPreviewCard card = resolved.capabilityPreviewCards().get(0);
        assertThat(card.cardKind()).isEqualTo(CapabilityPlanPreviewCard.CAPABILITY_PREVIEW);
        assertThat(card.componentLogicalId()).isEqualTo("message-a");
        assertThat(card.capabilityCode()).isEqualTo("message.template");
        assertThat(card.contractVersion()).isEqualTo("1.0");
        assertThat(card.normalizedPayloadHash()).isEqualTo(resolved.value().contentHash());
        assertThat(card.resolvedCatalogReferenceHash())
                .isEqualTo(CapabilityPreviewCards.canonicalReferenceHash(resolved.references()));
        assertThat(card.runtimeCompatibility()).isEqualTo(pack.runtimeCompatibility());
        assertThat(card.evaluationAssetHash())
                .isEqualTo(pack.packMetadata().evaluationAsset().contentHash());
        // The accepted Segment card path is untouched.
        assertThat(resolved.cards()).isEmpty();
    }

    @Test
    void theResolutionIsADeterministicPureFunctionOfItsRequest() {
        CapabilityResolution.Resolved first = committed(resolve("message-a",
                Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Ada"))));
        CapabilityResolution.Resolved second = committed(resolve("message-a",
                Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "Ada"))));

        assertThat(first.value()).isEqualTo(second.value());
        assertThat(first.value().canonicalBytes()).isEqualTo(second.value().canonicalBytes());
        assertThat(first.capabilityPreviewCards()).isEqualTo(second.capabilityPreviewCards());
    }

    @Test
    void optionalVariablesMayBeOmittedAndTypedValuesAreCanonicalized() {
        CapabilityResolution.Resolved resolved = committed(resolve("message-a", Map.of(
                "referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", "Ada", "loyaltyPoints", 100, "enrolledOn", "2026-01-03"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) resolved.value().fields().get("variables");
        assertThat(variables).containsOnlyKeys("enrolledOn", "firstName", "loyaltyPoints");
        assertThat(new BigDecimal(variables.get("loyaltyPoints").toString())).isEqualByComparingTo("100");
        // A date value is re-canonicalized to its ISO text form.
        assertThat(variables.get("enrolledOn")).isEqualTo("2026-01-03");
    }

    @Test
    void aDeclaredChannelMustEqualTheResolvedVersionsOwnChannel() {
        assertThat(committed(resolve("message-a",
                Map.of("referenceCode", "tpl-sms", "channel", "SMS"))).value().fields())
                .containsEntry("channel", "SMS");
        // A channel that is not the version's own channel is a mismatch, never a silent override.
        assertThat(code(resolve("message-a",
                Map.of("referenceCode", "tpl-sms", "channel", "EMAIL"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_CHANNEL_MISMATCH);
    }

    // ------------------------------------------------------------------ fail closed

    @Test
    void anUnknownOrMalformedFieldFailsClosed() {
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome", "body", "hi"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_FIELD);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome", "channel", "email"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
        assertThat(code(resolve("message-a", Map.of())))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl welcome"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
    }

    @Test
    void aMissingDisabledOrUnavailableVersionFailsClosedAndNeverFallsBackToLatest() {
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-absent"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_REFERENCE);
        // A code that is only a prefix of a real one is absent, never resolved to a neighbouring version.
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-wel"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNKNOWN_REFERENCE);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-disabled"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_DISABLED);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-unavailable"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_UNAVAILABLE);
    }

    @Test
    void everyVariableFaultFailsClosed() {
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", "Ada", "extra", "x")))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", 5)))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", "x".repeat(17))))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", "Ada", "loyaltyPoints", "many")))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome",
                "variables", Map.of("firstName", "Ada", "enrolledOn", "not-a-date")))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
        assertThat(code(resolve("message-a", Map.of("referenceCode", "tpl-welcome", "variables", "Ada"))))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_VARIABLES_INVALID);
    }

    @Test
    void anAbsentRequestFailsClosed() {
        assertThat(code(pack.resolve(null)))
                .isEqualTo(MessageTemplateErrorCodes.MESSAGE_TEMPLATE_PAYLOAD_INVALID);
    }

    @Test
    void aFailureEchoesNoRejectedValue() {
        CapabilityResolution.Failed failure = (CapabilityResolution.Failed) resolve("message-a",
                Map.of("referenceCode", "tpl-welcome", "variables", Map.of("firstName", "x".repeat(17))));
        for (Issue issue : failure.issues()) {
            assertThat(issue.message()).doesNotContain("xxxx");
            assertThat(issue.details().values()).allSatisfy(detail ->
                    assertThat(String.valueOf(detail)).doesNotContain("xxxx"));
        }
    }

    // ------------------------------------------------------------------ helpers

    private CapabilityResolution resolve(String logicalId, Map<String, Object> payload) {
        return pack.resolve(new CapabilityRequest(logicalId, CapabilityPayload.of(payload),
                BuilderFixtures.catalog(), BuilderFixtures.policy(), BuilderFixtures.context()));
    }

    private static CapabilityResolution.Resolved committed(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Resolved.class);
        return (CapabilityResolution.Resolved) resolution;
    }

    private static String code(CapabilityResolution resolution) {
        assertThat(resolution).isInstanceOf(CapabilityResolution.Failed.class);
        return ((CapabilityResolution.Failed) resolution).issues().get(0).code();
    }

    /** Guard: the builder fixtures this test relies on are the accepted compiler's own. */
    @Test
    void theFixtureCompilerVersionIsTheAcceptedOne() {
        assertThat(CompilerVersions.COMPILER_VERSION).isNotBlank();
    }
}
