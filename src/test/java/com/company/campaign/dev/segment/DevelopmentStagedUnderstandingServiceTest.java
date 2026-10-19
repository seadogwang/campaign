package com.company.campaign.dev.segment;

import com.company.campaign.builder.segment.ClarificationBatchStatus;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentStagedUnderstandingServiceTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void refinesAgainstAllMetricsAndTheirReferencedDimensionsThenBatchesAmbiguity() {
        String utterance = "近90天消费金额大于1000";
        QueuePort port = new QueuePort(discovery(utterance), refined(utterance));
        DevelopmentStagedUnderstandingService service = new DevelopmentStagedUnderstandingService(port);

        DevelopmentStagedUnderstandingResult result = service.analyze(
                CoreLoyaltyDevelopmentProfile.request(utterance, Instant.parse("2026-10-05T04:00:00Z")));

        assertThat(result.clarificationBatch().status()).isEqualTo(ClarificationBatchStatus.NEEDS_INPUT);
        assertThat(result.clarificationBatch().questions()).singleElement().satisfies(question ->
                assertThat(question.options()).hasSize(2));
        assertThat(port.prompts).hasSize(2);
        assertThat(port.prompts.get(0)).contains("Stage: DISCOVERY").doesNotContain("order.paid_amount_sum");
        assertThat(port.prompts.get(1))
                .contains("Stage: METADATA_REFINEMENT")
                .contains("order.paid_amount_sum")
                .contains("order.gross_amount_sum")
                .contains("order.channel")
                .doesNotContain("member.current_tier");
    }

    private static byte[] discovery(String utterance) {
        return json(utterance, """
                {"slotType":"DEFINITION","status":"MISSING","value":null,
                 "candidates":[],"reasonCode":"METADATA_NOT_LOADED"}
                """);
    }

    private static byte[] refined(String utterance) {
        return json(utterance, """
                {"slotType":"DEFINITION","status":"AMBIGUOUS","value":null,
                 "candidates":[{"value":"order.paid_amount_sum","label":"实际支付金额"},
                 {"value":"order.gross_amount_sum","label":"订单原始金额"}],
                 "reasonCode":"MULTIPLE_METRIC_DEFINITIONS"}
                """);
    }

    private static byte[] json(String utterance, String definitionSlot) {
        String payload = """
                {"draftVersion":"1.0","facts":[{"factId":"f1",
                "sourceSpan":{"text":"%s","start":0,"end":%d},
                "searchTerms":["消费金额"],"slots":[
                {"slotType":"CAPABILITY_TYPE","status":"RESOLVED","value":"METRIC",
                 "candidates":[],"reasonCode":null},%s]}],"globalSlots":[]}
                """.formatted(utterance, utterance.length(), definitionSlot.strip());
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static final class QueuePort implements DevelopmentStructuredModelPort {
        private final Queue<byte[]> responses = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        private QueuePort(byte[]... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public byte[] invokeStructured(String prompt) {
            prompts.add(prompt);
            return responses.remove();
        }
    }
}
