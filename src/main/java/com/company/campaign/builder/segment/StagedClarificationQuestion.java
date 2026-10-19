package com.company.campaign.builder.segment;

import java.util.List;

/** One consolidated question that may resolve the same ambiguity at multiple semantic targets. */
public record StagedClarificationQuestion(
        String questionId,
        String questionCode,
        ClarificationAnswerMode answerMode,
        String questionText,
        List<String> targets,
        List<StagedClarificationOption> options) {

    public StagedClarificationQuestion {
        questionId = SemanticDraftTexts.identifier(questionId, "questionId");
        questionCode = SemanticDraftTexts.reasonCode(questionCode);
        if (answerMode == null) {
            throw new IllegalArgumentException("A clarification question requires an answer mode");
        }
        questionText = SemanticDraftTexts.required(questionText, "questionText", 512);
        targets = SemanticDraftTexts.list(targets, "question.target", 64, 256);
        options = options == null ? List.of() : List.copyOf(options);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("A clarification question requires at least one target");
        }
        if (answerMode == ClarificationAnswerMode.SELECT_ONE && options.size() < 2) {
            throw new IllegalArgumentException("A select-one question requires at least two options");
        }
        if (answerMode == ClarificationAnswerMode.SELECT_OR_DESCRIBE_METRIC && options.isEmpty()) {
            throw new IllegalArgumentException("A select-or-describe question requires suggested options");
        }
        if (answerMode != ClarificationAnswerMode.SELECT_ONE
                && answerMode != ClarificationAnswerMode.SELECT_OR_DESCRIBE_METRIC
                && !options.isEmpty()) {
            throw new IllegalArgumentException("A descriptive question cannot carry options");
        }
    }
}
