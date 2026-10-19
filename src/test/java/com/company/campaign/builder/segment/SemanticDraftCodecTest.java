package com.company.campaign.builder.segment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticDraftCodecTest {

    private final SemanticDraftCodec codec = new SemanticDraftCodec();

    @Test
    void parsesGroundedFieldLevelAmbiguity() {
        String json = """
                {"draftVersion":"1.0","facts":[{"factId":"f1",
                "sourceSpan":{"text":"消费金额","start":0,"end":4},
                "searchTerms":["消费金额"],"slots":[
                {"slotType":"CAPABILITY_TYPE","status":"RESOLVED","value":"METRIC",
                 "candidates":[],"reasonCode":null},
                {"slotType":"DEFINITION","status":"AMBIGUOUS","value":null,
                 "candidates":[{"value":"order.paid_amount_sum","label":"实际支付金额"},
                 {"value":"order.gross_amount_sum","label":"订单原始金额"}],
                 "reasonCode":"MULTIPLE_METRIC_DEFINITIONS"}]}],"globalSlots":[]}
                """;

        SemanticDraft draft = codec.parse(json.getBytes(StandardCharsets.UTF_8), "消费金额");

        assertThat(draft.facts()).hasSize(1);
        assertThat(draft.facts().getFirst().slots().get(1).status())
                .isEqualTo(SemanticSlotStatus.AMBIGUOUS);
    }

    @Test
    void rejectsContradictoryAmbiguityAndUngroundedSpans() {
        assertThatThrownBy(() -> new SemanticSlot(
                SemanticSlotType.DEFINITION,
                SemanticSlotStatus.AMBIGUOUS,
                "order.paid_amount_sum",
                java.util.List.of(
                        new SemanticCandidate("a", "A"),
                        new SemanticCandidate("b", "B")),
                "MULTIPLE_DEFINITIONS"))
                .isInstanceOf(IllegalArgumentException.class);

        String json = """
                {"draftVersion":"1.0","facts":[{"factId":"f1",
                "sourceSpan":{"text":"错误文本","start":0,"end":4},
                "searchTerms":["消费金额"],"slots":[
                {"slotType":"CAPABILITY_TYPE","status":"RESOLVED","value":"METRIC",
                 "candidates":[],"reasonCode":null}]}],"globalSlots":[]}
                """;
        assertThatThrownBy(() -> codec.parse(json.getBytes(StandardCharsets.UTF_8), "消费金额"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void repairsOnlyTheIndexesOfAUniqueExactSourceText() {
        String json = """
                {"draftVersion":"1.0","facts":[{"factId":"f1",
                "sourceSpan":{"text":"消费金额","start":0,"end":5},
                "searchTerms":["消费金额"],"slots":[
                {"slotType":"CAPABILITY_TYPE","status":"RESOLVED","value":"METRIC",
                 "candidates":[],"reasonCode":null}]}],"globalSlots":[]}
                """;

        SemanticDraft draft = codec.parse(json.getBytes(StandardCharsets.UTF_8), "查消费金额");

        assertThat(draft.facts().getFirst().sourceSpan().start()).isEqualTo(1);
        assertThat(draft.facts().getFirst().sourceSpan().end()).isEqualTo(5);
    }
}
