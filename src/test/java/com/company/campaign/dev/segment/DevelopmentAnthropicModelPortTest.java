package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.SegmentModelRequestFactory;
import com.company.campaign.builder.segment.SegmentAdapterErrorCodes;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentAnthropicModelPortTest {

    private static final String TOKEN = "dummy-token-not-a-secret";

    @Test
    void sendsOneBearerCredentialAndTheFormalFrozenContract() {
        DevelopmentAnthropicModelPort port = new DevelopmentAnthropicModelPort(
                new DevelopmentModelConfiguration(TOKEN, "https://provider.invalid/apps/anthropic", "model-id"));
        var modelRequest = new SegmentModelRequestFactory().build(
                "金卡会员",
                "zh-CN",
                CoreLoyaltyDevelopmentProfile.snapshot(),
                CoreLoyaltyDevelopmentProfile.resolutionPolicy(),
                Instant.parse("2026-10-04T00:00:00Z"),
                ZoneId.of("Asia/Shanghai"),
                CoreLoyaltyDevelopmentProfile.registeredDefaults());

        var httpRequest = port.buildHttpRequest(modelRequest);
        String body = new String(port.requestBody(modelRequest), StandardCharsets.UTF_8);
        String prompt = port.prompt(modelRequest);

        assertThat(httpRequest.uri().toString())
                .isEqualTo("https://provider.invalid/apps/anthropic/v1/messages");
        assertThat(httpRequest.headers().allValues("Authorization"))
                .containsExactly("Bearer " + TOKEN);
        assertThat(httpRequest.headers().firstValue("x-api-key")).isEmpty();
        assertThat(httpRequest.headers().firstValue("accept")).contains("text/event-stream");
        assertThat(body).doesNotContain(TOKEN);
        assertThat(body)
                .contains("\"stream\":true")
                .contains("\"temperature\":0")
                .contains("\"type\":\"disabled\"");
        assertThat(prompt)
                .contains(SegmentIntentPromptContract.VERSION)
                .contains(SegmentIntentPromptContract.HASH.value())
                .containsOnlyOnce("Return one formal Segment Intent v1.3 JSON object")
                .containsOnlyOnce("Root fields:")
                .contains("formal Segment Intent v1.3")
                .contains("Temporal context: {\"asOf\":\"2026-10-04T00:00:00Z\",\"timezone\":\"Asia/Shanghai\"}")
                .contains("Registered model defaults: {\"reportingCurrency\":\"CNY\"}")
                .contains("calendar day after the final date")
                .contains("complete calendar months immediately")
                .contains("bucketRequirement EVERY_BUCKET")
                .contains("ORDERED_ENUM->ENUM")
                .contains("host Resolver expands the boundary by Metadata rank")
                .contains("Utterance UTF-16 index guide")
                .contains("\"start\":0,\"text\":\"金\"")
                .contains("\"conceptCodes\":[\"CURRENT_TIER\"]")
                .contains("\"stableId\":\"member.current_tier\"")
                .contains("\"rank\":40")
                .doesNotContain("Expand an ordered-enum phrase")
                .doesNotContain("current_tier_code")
                .doesNotContain("data_scope_members")
                .doesNotContain("fx_rates");
        assertThat(prompt).doesNotContain("Schema repair attempt:");
        assertThat(port.prompt(modelRequest.forSchemaRepair(List.of(SegmentAdapterErrorCodes.OUTPUT_INVALID))))
                .contains("Schema repair attempt:")
                .contains(SegmentAdapterErrorCodes.OUTPUT_INVALID)
                .containsOnlyOnce("Return one formal Segment Intent v1.3 JSON object");
    }

    @Test
    void joinsOnlyTextDeltasFromTheBoundedUtf8Stream() throws Exception {
        String stream = "data: {\"type\":\"message_start\"}\n\n"
                + "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"text\","
                + "\"text\":\"{\\\"intentVersion\\\":\"}}\n\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"\\\"1.3\\\"}\"}}\n\n"
                + "data: [DONE]\n\n";

        assertThat(DevelopmentAnthropicModelPort.extractStreamingText(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("{\"intentVersion\":\"1.3\"}");
    }
}
