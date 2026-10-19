package com.company.campaign.builder.segment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministically consolidates every field-level uncertainty into one current clarification batch. */
public final class StagedClarificationBatchFactory {

    public StagedClarificationBatch create(SemanticDraft draft) {
        Map<String, PendingQuestion> pending = new LinkedHashMap<>();
        List<String> unsupported = new ArrayList<>();
        for (SemanticFactDraft fact : draft.facts()) {
            boolean metricCapability = isMetricCapability(fact);
            SemanticSlot missingMetric = metricCapability ? missingMetricDefinition(fact) : null;
            SemanticSlot metricProposals = missingMetric == null ? null : metricProposals(fact);
            if (metricProposals != null) {
                collectMetricProposals(fact, missingMetric, metricProposals, pending);
            }
            for (SemanticSlot slot : fact.slots()) {
                if (metricProposals != null && (slot == missingMetric || slot == metricProposals)) {
                    continue;
                }
                collect(slot, "facts/" + fact.factId() + "/" + slot.slotType(), metricCapability,
                        pending, unsupported);
            }
        }
        for (SemanticSlot slot : draft.globalSlots()) {
            collect(slot, "globalSlots/" + slot.slotType(), false, pending, unsupported);
        }

        List<StagedClarificationQuestion> questions = new ArrayList<>();
        int questionNumber = 1;
        for (PendingQuestion question : pending.values()) {
            List<StagedClarificationOption> options = new ArrayList<>();
            int optionNumber = 1;
            for (SemanticCandidate candidate : question.slot.candidates()) {
                options.add(new StagedClarificationOption(
                        "o" + optionNumber++, candidate.value(), candidate.label(), candidate.modelSuggested()));
            }
            questions.add(new StagedClarificationQuestion(
                    "q" + questionNumber++,
                    question.questionCode,
                    question.answerMode,
                    question.questionText,
                    List.copyOf(question.targets),
                    List.copyOf(options)));
        }

        ClarificationBatchStatus status = !unsupported.isEmpty()
                ? ClarificationBatchStatus.UNSUPPORTED
                : questions.isEmpty() ? ClarificationBatchStatus.READY : ClarificationBatchStatus.NEEDS_INPUT;
        return new StagedClarificationBatch(status, questions, unsupported);
    }

    private static void collect(
            SemanticSlot slot,
            String target,
            boolean metricCapability,
            Map<String, PendingQuestion> pending,
            List<String> unsupported) {
        if (slot.status() == SemanticSlotStatus.UNSUPPORTED) {
            unsupported.add(target);
            return;
        }
        if (slot.status() == SemanticSlotStatus.RESOLVED) {
            return;
        }
        boolean metricDescription = metricCapability
                && slot.slotType() == SemanticSlotType.DEFINITION
                && slot.status() == SemanticSlotStatus.MISSING
                && "METRIC_DEFINITION_NOT_FOUND".equals(slot.reasonCode());
        boolean proposal = slot.status() == SemanticSlotStatus.PROPOSED;
        boolean selection = slot.status() == SemanticSlotStatus.AMBIGUOUS || proposal;
        ClarificationAnswerMode answerMode = proposal
                ? ClarificationAnswerMode.SELECT_OR_DESCRIBE_METRIC
                : selection ? ClarificationAnswerMode.SELECT_ONE
                : metricDescription ? ClarificationAnswerMode.DESCRIBE_METRIC : ClarificationAnswerMode.FREE_TEXT;
        String questionCode = proposal
                ? "SELECT_OR_DESCRIBE_METRIC_CALCULATION"
                : metricDescription
                ? "DESCRIBE_METRIC_CALCULATION"
                : (selection ? "SELECT_" : "PROVIDE_") + slot.slotType();
        String text = proposal
                ? "可以选择尚未验证的 AI 建议计算方式，或用自然语言描述其他计算方式、统计对象、固定过滤、除零和空集合规则。"
                : metricDescription
                ? "指标库中没有匹配口径。请用自然语言描述计算方式、统计对象、固定过滤、除零和空集合规则。"
                : questionText(slot.slotType(), selection);
        String signature = slot.slotType() + "|" + slot.status() + "|" + slot.reasonCode() + "|"
                + metricDescription + "|"
                + slot.candidates().stream()
                .map(candidate -> candidate.value() + "=" + candidate.label() + "=" + candidate.modelSuggested())
                .toList();
        pending.computeIfAbsent(signature, ignored -> new PendingQuestion(
                        slot, answerMode, questionCode, text))
                .targets.add(target);
    }

    private static void collectMetricProposals(
            SemanticFactDraft fact,
            SemanticSlot missingMetric,
            SemanticSlot proposals,
            Map<String, PendingQuestion> pending) {
        String signature = "METRIC_PROPOSAL|" + proposals.candidates().stream()
                .map(candidate -> candidate.value() + "=" + candidate.label() + "=" + candidate.modelSuggested())
                .toList();
        PendingQuestion question = pending.computeIfAbsent(signature, ignored -> new PendingQuestion(
                proposals,
                ClarificationAnswerMode.SELECT_OR_DESCRIBE_METRIC,
                "SELECT_OR_DESCRIBE_METRIC_CALCULATION",
                "指标库中没有匹配口径。可以选择尚未验证的 AI 建议计算方式，或用自然语言描述其他计算方式、统计对象、固定过滤、除零和空集合规则。"));
        question.targets.add("facts/" + fact.factId() + "/" + missingMetric.slotType());
        question.targets.add("facts/" + fact.factId() + "/" + proposals.slotType());
    }

    private static SemanticSlot missingMetricDefinition(SemanticFactDraft fact) {
        return fact.slots().stream()
                .filter(slot -> slot.slotType() == SemanticSlotType.DEFINITION)
                .filter(slot -> slot.status() == SemanticSlotStatus.MISSING)
                .filter(slot -> "METRIC_DEFINITION_NOT_FOUND".equals(slot.reasonCode()))
                .findFirst()
                .orElse(null);
    }

    private static SemanticSlot metricProposals(SemanticFactDraft fact) {
        return fact.slots().stream()
                .filter(slot -> slot.slotType() == SemanticSlotType.EXPRESSION)
                .filter(slot -> slot.status() == SemanticSlotStatus.PROPOSED)
                .filter(slot -> "MODEL_CALCULATION_PROPOSAL".equals(slot.reasonCode()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isMetricCapability(SemanticFactDraft fact) {
        return fact.slots().stream()
                .filter(slot -> slot.slotType() == SemanticSlotType.CAPABILITY_TYPE)
                .anyMatch(slot -> "METRIC".equals(slot.value())
                        || "METRIC_EXPRESSION".equals(slot.value())
                        || slot.candidates().stream().anyMatch(candidate ->
                        "METRIC".equals(candidate.value()) || "METRIC_EXPRESSION".equals(candidate.value())));
    }

    private static String questionText(SemanticSlotType slotType, boolean selection) {
        String subject = switch (slotType) {
            case CAPABILITY_TYPE -> "业务能力类型";
            case CONCEPT -> "业务概念";
            case DEFINITION -> "业务口径";
            case AGGREGATION -> "聚合方式";
            case OPERATOR -> "比较方式";
            case VALUE -> "条件值含义";
            case TIME_WINDOW -> "时间范围";
            case SCOPE_FILTER -> "过滤维度";
            case EVENT_SELECTOR -> "事件范围";
            case LOGICAL_GROUPING -> "条件组合方式";
            case NEGATION -> "否定范围";
            case CURRENCY -> "币种";
            case EXPRESSION -> "指标计算方式";
        };
        return selection ? "请选择" + subject + "。" : "请补充" + subject + "。";
    }

    private static final class PendingQuestion {
        private final SemanticSlot slot;
        private final ClarificationAnswerMode answerMode;
        private final String questionCode;
        private final String questionText;
        private final List<String> targets = new ArrayList<>();

        private PendingQuestion(
                SemanticSlot slot,
                ClarificationAnswerMode answerMode,
                String questionCode,
                String questionText) {
            this.slot = slot;
            this.answerMode = answerMode;
            this.questionCode = questionCode;
            this.questionText = questionText;
        }
    }
}
