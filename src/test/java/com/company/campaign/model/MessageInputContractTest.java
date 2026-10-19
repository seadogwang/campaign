package com.company.campaign.model;

import com.company.campaign.builder.campaignagent.CampaignClarificationAnswer;
import com.company.campaign.builder.campaignagent.CampaignMessageInputs;
import com.company.campaign.builder.compiler.CapabilityPayload;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T39A: the Message input contract is one closed one-of — a complete catalog reference shape or a complete
 * inline draft shape — and the structured-model decoder accepts exactly those two strict shapes and fails
 * closed on everything else.
 *
 * <p>The concrete acceptance copy below is test evidence only: it proves the decoder extracts an arbitrary
 * user-authored body generically, and that no production prompt or code path carries the wording itself.
 */
class MessageInputContractTest {

    /** The exact user-authored copy of the T39 acceptance scenario; permitted in tests only. */
    private static final String ACCEPTANCE_COPY = "通知客户店里有8折优惠";

    private static final List<CampaignClarificationAnswer> NO_ANSWERS = List.of();

    private static CampaignMessageInputs message(byte[] output) {
        return new StructuredSkillInputResolvers(request -> output).messageInputs("turn", NO_ANSWERS);
    }

    private static CampaignMessageInputs message(String json) {
        return message(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertUnresolved(byte[] output) {
        assertThat(message(output).resolved()).isFalse();
    }

    private static void assertUnresolved(String json) {
        assertUnresolved(json.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ success shapes

    @Test
    void aCompleteCatalogReferenceShapeResolves() {
        CampaignMessageInputs inputs =
                message("{\"referenceCode\":\"tpl-welcome\",\"variables\":{\"firstName\":\"Ada\"}}");

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.isReference()).isTrue();
        assertThat(inputs.isInline()).isFalse();
        assertThat(inputs.referenceCode()).isEqualTo("tpl-welcome");
        assertThat(inputs.variables().fields()).containsEntry("firstName", "Ada");
        assertThat(inputs.channel()).isNull();
        assertThat(inputs.body()).isNull();
    }

    @Test
    void aCompleteInlineDraftShapeResolvesTheChannelAndTheExactUserAuthoredBody() {
        CampaignMessageInputs inputs =
                message("{\"channel\":\"SMS\",\"body\":\"" + ACCEPTANCE_COPY + "\"}");

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.isInline()).isTrue();
        assertThat(inputs.isReference()).isFalse();
        assertThat(inputs.channel()).isEqualTo(CampaignMessageInputs.Channel.SMS);
        // Exact copy: the decoder never trims, translates or paraphrases the user's own words.
        assertThat(inputs.body()).isEqualTo(ACCEPTANCE_COPY);
        assertThat(inputs.referenceCode()).isNull();
        assertThat(inputs.variables()).isNull();
    }

    @Test
    void aMultiChannelInlineCollectionResolvesOneExactDraftPerChannel() {
        CampaignMessageInputs inputs = message("{\"messages\":["
                + "{\"channel\":\"WECHAT\",\"body\":\"same copy\"},"
                + "{\"channel\":\"SMS\",\"body\":\"same copy\"}]}");

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.inlineDrafts())
                .extracting(draft -> draft.channel().name() + ':' + draft.body())
                .containsExactly("SMS:same copy", "WECHAT:same copy");
        assertThat(inputs.channel()).isNull();
        assertThat(inputs.body()).isNull();
    }

    @Test
    void everyDeclaredChannelIsAcceptedGenerically() {
        assertThat(CampaignMessageInputs.Channel.fromCode("SMS")).isEqualTo(CampaignMessageInputs.Channel.SMS);
        assertThat(CampaignMessageInputs.Channel.fromCode("EMAIL")).isEqualTo(CampaignMessageInputs.Channel.EMAIL);
        assertThat(CampaignMessageInputs.Channel.fromCode("PUSH")).isEqualTo(CampaignMessageInputs.Channel.PUSH);
        assertThat(CampaignMessageInputs.Channel.fromCode("IN_APP")).isEqualTo(CampaignMessageInputs.Channel.IN_APP);
        assertThat(CampaignMessageInputs.Channel.fromCode("WECHAT")).isEqualTo(CampaignMessageInputs.Channel.WECHAT);
        // The vocabulary is canonical machine codes only, never a case-insensitive or language keyword rule.
        assertThat(CampaignMessageInputs.Channel.fromCode("sms")).isNull();
        assertThat(CampaignMessageInputs.Channel.fromCode("Email")).isNull();
        assertThat(CampaignMessageInputs.Channel.fromCode(null)).isNull();

        CampaignMessageInputs inputs = message("{\"channel\":\"EMAIL\",\"body\":\"a generic catalogue note\"}");
        assertThat(inputs.isInline()).isTrue();
        assertThat(inputs.channel()).isEqualTo(CampaignMessageInputs.Channel.EMAIL);
        assertThat(inputs.body()).isEqualTo("a generic catalogue note");
    }

    // ------------------------------------------------------------------ fail-closed cases

    @Test
    void aDocumentThatFitsNeitherShapeIsUnresolved() {
        assertUnresolved("{}");
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\"}");
        assertUnresolved("{\"variables\":{\"firstName\":\"Ada\"}}");
        assertUnresolved("{\"channel\":\"SMS\"}");
        assertUnresolved("{\"body\":\"copy\"}");
        assertUnresolved("{\"referenceCode\":null,\"variables\":{}}");
        assertUnresolved("{\"messages\":[]}");
        assertUnresolved("{\"messages\":[{\"channel\":\"SMS\",\"body\":\"a\"},"
                + "{\"channel\":\"SMS\",\"body\":\"b\"}]}");
    }

    @Test
    void aMixedOrExtraFieldDocumentIsUnresolved() {
        // Both shapes at once.
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\",\"variables\":{},\"channel\":\"SMS\",\"body\":\"x\"}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\"x\",\"referenceCode\":\"tpl-welcome\"}");
        // One declared shape plus an undeclared field: never a provider, credential, Tool or SQL carrier.
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\",\"variables\":{},\"extra\":1}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\"x\",\"provider\":\"x\"}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\"x\",\"toolId\":\"t\"}");
    }

    @Test
    void anUnsupportedOrWronglyTypedChannelIsUnresolved() {
        assertUnresolved("{\"channel\":\"sms\",\"body\":\"x\"}");
        assertUnresolved("{\"channel\":\"FAX\",\"body\":\"x\"}");
        assertUnresolved("{\"channel\":\"\",\"body\":\"x\"}");
        assertUnresolved("{\"channel\":7,\"body\":\"x\"}");
        assertUnresolved("{\"channel\":null,\"body\":\"x\"}");
    }

    @Test
    void anAbsentEmptyNonTextualOrOversizedBodyIsUnresolved() {
        assertUnresolved("{\"channel\":\"SMS\"}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\"\"}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":7}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":null}");
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\""
                + "a".repeat(CampaignMessageInputs.MAX_BODY_LENGTH + 1) + "\"}");
    }

    @Test
    void malformedDuplicateKeyReservedAndOversizedOutputIsUnresolved() {
        assertUnresolved("not json");
        assertUnresolved("{\"channel\":\"SMS\",\"channel\":\"EMAIL\",\"body\":\"x\"}");
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\",\"referenceCode\":\"tpl-other\",\"variables\":{}}");
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\",\"variables\":{\"provider\":\"x\"}}");
        assertUnresolved("{\"referenceCode\":\"tpl-welcome\",\"variables\":{\"tenantId\":\"t\"}}");
        // An oversized model response is refused before any decode.
        assertUnresolved("{\"channel\":\"SMS\",\"body\":\"" + "a".repeat(40_000) + "\"}");
        // A response that is not valid UTF-8 is refused.
        assertUnresolved(new byte[] {(byte) 0xC3, (byte) 0x28});
    }

    // ------------------------------------------------------------------ contract and prompt

    @Test
    void theTwoComponentReferenceConstructorStaysSourceCompatible() {
        CampaignMessageInputs inputs = new CampaignMessageInputs(
                "tpl-welcome", CapabilityPayload.of(Map.of("firstName", "Ada")));

        assertThat(inputs.resolved()).isTrue();
        assertThat(inputs.isReference()).isTrue();
        assertThat(inputs.referenceCode()).isEqualTo("tpl-welcome");
        assertThat(CampaignMessageInputs.unresolved().resolved()).isFalse();
    }

    @Test
    void theClosedOneOfCannotBeConstructedHalfResolved() {
        assertThatThrownBy(() -> new CampaignMessageInputs("tpl-welcome", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CampaignMessageInputs.ofInline(null, "copy"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CampaignMessageInputs.ofInline(CampaignMessageInputs.Channel.SMS, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CampaignMessageInputs.ofInline(
                CampaignMessageInputs.Channel.SMS, "a".repeat(CampaignMessageInputs.MAX_BODY_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theMessagePromptDeclaresTheGenericOneOfAndTheClosedChannelVocabulary() {
        StringBuilder prompt = new StringBuilder();
        StructuredModelPort transport = request -> {
            prompt.append(request.prompt());
            return "{}".getBytes(StandardCharsets.UTF_8);
        };
        new StructuredSkillInputResolvers(transport).messageInputs("turn", NO_ANSWERS);

        assertThat(StructuredSkillInputResolvers.MessagePrompt.VERSION)
                .isEqualTo("campaign-skill-message-input/3");
        assertThat(StructuredSkillInputResolvers.MessagePrompt.HASH.value()).startsWith("sha256:");
        assertThat(prompt.toString())
                .contains(StructuredSkillInputResolvers.MessagePrompt.VERSION)
                .contains("\"referenceCode\"")
                .contains("\"messages\"")
                .contains("\"channel\"")
                .contains("SMS, EMAIL, PUSH, IN_APP")
                // No concrete acceptance text and no hard-coded wording-to-channel mapping.
                .doesNotContain(ACCEPTANCE_COPY)
                .doesNotContain("短信")
                .doesNotContain("8折");
    }
}
