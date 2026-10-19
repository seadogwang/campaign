package com.company.campaign.builder.campaignagent.http;

import com.company.campaign.builder.campaignagent.CampaignAgentException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strict wire request contract: unknown fields, duplicates, malformed revisions and unreadable bodies are
 * refused before a turn exists, and nothing of the rejected value is echoed.
 */
class CampaignAgentTurnCodecTest {

    private final CampaignAgentTurnCodec codec = new CampaignAgentTurnCodec();

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String valid() {
        return "{\"conversationId\":\"conversation-t27\",\"runId\":\"run-t27\",\"expectedRevision\":0,"
                + "\"message\":\"build a welcome campaign\",\"answers\":[],\"at\":null}";
    }

    private static void assertCode(Runnable call, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(CampaignAgentException.class)
                .extracting(failure -> ((CampaignAgentException) failure).code())
                .isEqualTo(code);
    }

    @Test
    void theExactSixFieldRequestIsAccepted() {
        CampaignAgentTurnHttpRequest decoded = codec.decode(body(valid()));
        assertThat(decoded.conversationId()).isEqualTo("conversation-t27");
        assertThat(decoded.runId()).isEqualTo("run-t27");
        assertThat(decoded.expectedRevision()).isZero();
        assertThat(decoded.message()).isEqualTo("build a welcome campaign");
        assertThat(decoded.answers()).isEmpty();
        assertThat(decoded.at()).isNull();
    }

    @Test
    void aBoundedTypedAnswerIsAccepted() {
        String json = "{\"conversationId\":\"conversation-t27\",\"runId\":\"run-t27\",\"expectedRevision\":3,"
                + "\"message\":\"use the offered window\",\"answers\":[{\"code\":\"SELECT_WINDOW\","
                + "\"optionId\":\"option-a\",\"freeText\":null,\"component\":null}],"
                + "\"at\":\"2026-10-09T10:00:00Z\"}";
        CampaignAgentTurnHttpRequest decoded = codec.decode(body(json));
        assertThat(decoded.expectedRevision()).isEqualTo(3);
        assertThat(decoded.answers()).hasSize(1);
        assertThat(decoded.answers().get(0).code()).isEqualTo("SELECT_WINDOW");
        assertThat(decoded.at()).isEqualTo("2026-10-09T10:00:00Z");
    }

    @Test
    void anUnknownFieldIsRefused() {
        assertCode(() -> codec.decode(body(valid().replace("\"at\":null", "\"at\":null,\"campaignId\":\"c-1\""))),
                CampaignAgentHttpErrorCodes.REQUEST_UNKNOWN_FIELD);
    }

    @Test
    void aMissingFieldIsRefused() {
        assertCode(() -> codec.decode(body(
                        "{\"conversationId\":\"conversation-t27\",\"runId\":\"run-t27\",\"expectedRevision\":0,"
                                + "\"message\":\"hello\",\"answers\":[]}")),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
    }

    @Test
    void aDuplicateKeyIsRefused() {
        assertCode(() -> codec.decode(body(
                        "{\"conversationId\":\"conversation-t27\",\"runId\":\"run-t27\",\"expectedRevision\":0,"
                                + "\"message\":\"hello\",\"message\":\"again\",\"answers\":[],\"at\":null}")),
                CampaignAgentHttpErrorCodes.REQUEST_DUPLICATE_KEY);
    }

    @Test
    void aMalformedRevisionIsRefused() {
        assertCode(() -> codec.decode(body(valid().replace("\"expectedRevision\":0", "\"expectedRevision\":-1"))),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
        assertCode(() -> codec.decode(body(valid().replace("\"expectedRevision\":0", "\"expectedRevision\":1.5"))),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
        assertCode(() -> codec.decode(body(valid().replace("\"expectedRevision\":0", "\"expectedRevision\":\"0\""))),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
        assertCode(() -> codec.decode(body(
                        valid().replace("\"expectedRevision\":0", "\"expectedRevision\":100000000000"))),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
    }

    @Test
    void aMalformedIdentifierIsRefused() {
        assertCode(() -> codec.decode(body(valid().replace("conversation-t27", "../etc/passwd"))),
                CampaignAgentHttpErrorCodes.REQUEST_INVALID);
    }

    @Test
    void anUnreadableOrOversizedBodyIsRefused() {
        assertCode(() -> codec.decode(null), CampaignAgentHttpErrorCodes.REQUEST_MALFORMED);
        assertCode(() -> codec.decode(new byte[0]), CampaignAgentHttpErrorCodes.REQUEST_MALFORMED);
        assertCode(() -> codec.decode(new byte[]{(byte) 0xC3, (byte) 0x28}),
                CampaignAgentHttpErrorCodes.REQUEST_MALFORMED);
        assertCode(() -> codec.decode(body("{not json")), CampaignAgentHttpErrorCodes.REQUEST_MALFORMED);
        assertCode(() -> codec.decode(body("[1,2]")), CampaignAgentHttpErrorCodes.REQUEST_INVALID);
        assertCode(() -> codec.decode(new byte[CampaignAgentTurnCodec.MAX_REQUEST_BYTES + 1]),
                CampaignAgentHttpErrorCodes.REQUEST_LIMIT_EXCEEDED);
    }

    @Test
    void aRefusedBodyNeverEchoesItsContent() {
        String secret = "totally-confidential-utterance";
        try {
            codec.decode(body("{\"conversationId\":\"conversation-t27\",\"runId\":\"run-t27\","
                    + "\"expectedRevision\":0,\"message\":\"" + secret + "\",\"answers\":[],\"at\":null,"
                    + "\"note\":\"" + secret + "\"}"));
            org.junit.jupiter.api.Assertions.fail("the body should have been refused");
        } catch (CampaignAgentException failure) {
            assertThat(failure.getMessage()).doesNotContain(secret);
        }
    }

    @Test
    void theRequestIdIsDeterministicAndDerivedFromTheBody() {
        String first = codec.requestId(body(valid()));
        String second = codec.requestId(body(valid()));
        assertThat(first).isEqualTo(second).startsWith("req-").hasSize(36);
        assertThat(codec.requestId(body(valid().replace("build a welcome campaign", "another goal"))))
                .isNotEqualTo(first);
        assertThat(codec.requestId(null)).isEqualTo("req-unavailable");
    }

    @Test
    void theResponseNeverCarriesAModelByteOrAProviderFact() {
        // The codec has no field for either, so a projection cannot leak one by construction.
        assertThat(CampaignAgentTurnHttpResponse.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("modelOutput", "prompt", "artifact", "payload", "provider", "member")
                .contains("accepted", "revision", "skills", "clarification", "proposal");
    }
}