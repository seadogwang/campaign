package com.company.campaign.builder.segment;

import com.company.campaign.contracts.SourceSpan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StagedClarificationBatchFactoryTest {

    @Test
    void consolidatesMatchingFieldAmbiguitiesIntoOneQuestion() {
        SemanticSlot ambiguous = new SemanticSlot(
                SemanticSlotType.DEFINITION,
                SemanticSlotStatus.AMBIGUOUS,
                null,
                List.of(
                        new SemanticCandidate("order.paid_amount_sum", "实际支付金额"),
                        new SemanticCandidate("order.gross_amount_sum", "订单原始金额")),
                "MULTIPLE_METRIC_DEFINITIONS");
        SemanticDraft draft = new SemanticDraft("1.0", List.of(
                fact("f1", "消费金额", 0, ambiguous),
                fact("f2", "购买金额", 5, ambiguous)), List.of());

        StagedClarificationBatch batch = new StagedClarificationBatchFactory().create(draft);

        assertThat(batch.status()).isEqualTo(ClarificationBatchStatus.NEEDS_INPUT);
        assertThat(batch.questions()).singleElement().satisfies(question -> {
            assertThat(question.questionCode()).isEqualTo("SELECT_DEFINITION");
            assertThat(question.targets()).containsExactly(
                    "facts/f1/DEFINITION", "facts/f2/DEFINITION");
            assertThat(question.options()).extracting(StagedClarificationOption::value)
                    .containsExactly("order.paid_amount_sum", "order.gross_amount_sum");
        });
    }

    @Test
    void asksForANaturalLanguageCalculationWhenAMetricDefinitionIsMissing() {
        SemanticSlot missing = new SemanticSlot(
                SemanticSlotType.DEFINITION,
                SemanticSlotStatus.MISSING,
                null,
                List.of(),
                "METRIC_DEFINITION_NOT_FOUND");
        SemanticDraft draft = new SemanticDraft(
                "1.0", List.of(fact("f1", "复购率", 0, missing)), List.of());

        StagedClarificationQuestion question = new StagedClarificationBatchFactory()
                .create(draft).questions().getFirst();

        assertThat(question.questionCode()).isEqualTo("DESCRIBE_METRIC_CALCULATION");
        assertThat(question.answerMode()).isEqualTo(ClarificationAnswerMode.DESCRIBE_METRIC);
        assertThat(question.options()).isEmpty();
        assertThat(question.questionText()).contains("计算方式", "固定过滤", "除零");
    }

    @Test
    void offersModelSuggestionsWhileKeepingACustomMetricDescriptionPath() {
        SemanticSlot missing = new SemanticSlot(
                SemanticSlotType.DEFINITION,
                SemanticSlotStatus.MISSING,
                null,
                List.of(),
                "METRIC_DEFINITION_NOT_FOUND");
        SemanticSlot proposal = new SemanticSlot(
                SemanticSlotType.EXPRESSION,
                SemanticSlotStatus.PROPOSED,
                null,
                List.of(new SemanticCandidate(
                        "proposal_1", "复购率 = 至少购买两次的会员数 / 有过购买的会员数；分母为零时排除",
                        true)),
                "MODEL_CALCULATION_PROPOSAL");
        SemanticFactDraft fact = new SemanticFactDraft(
                "f1", new SourceSpan("复购率", 0, 3), List.of("复购率"),
                List.of(
                        new SemanticSlot(SemanticSlotType.CAPABILITY_TYPE, SemanticSlotStatus.RESOLVED,
                                "METRIC", List.of(), null),
                        missing,
                        proposal));

        StagedClarificationQuestion question = new StagedClarificationBatchFactory()
                .create(new SemanticDraft("1.0", List.of(fact), List.of()))
                .questions().getFirst();

        assertThat(question.questionCode()).isEqualTo("SELECT_OR_DESCRIBE_METRIC_CALCULATION");
        assertThat(question.answerMode()).isEqualTo(ClarificationAnswerMode.SELECT_OR_DESCRIBE_METRIC);
        assertThat(question.options()).singleElement().satisfies(option ->
                assertThat(option.label()).contains("至少购买两次", "分母为零"));
        assertThat(question.options().getFirst().modelSuggested()).isTrue();
        assertThat(question.targets()).containsExactly(
                "facts/f1/DEFINITION", "facts/f1/EXPRESSION");
    }

    private static SemanticFactDraft fact(
            String id, String text, int start, SemanticSlot ambiguity) {
        return new SemanticFactDraft(
                id,
                new SourceSpan(text, start, start + text.length()),
                List.of(text),
                List.of(
                        new SemanticSlot(
                                SemanticSlotType.CAPABILITY_TYPE,
                                SemanticSlotStatus.RESOLVED,
                                "METRIC",
                                List.of(),
                                null),
                        ambiguity));
    }
}
