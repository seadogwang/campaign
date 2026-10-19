package com.company.campaign.builder.segment;

import java.util.List;

/** One user-facing batch containing every currently independent clarification. */
public record StagedClarificationBatch(
        ClarificationBatchStatus status,
        List<StagedClarificationQuestion> questions,
        List<String> unsupportedTargets) {

    public StagedClarificationBatch {
        if (status == null) {
            throw new IllegalArgumentException("A clarification batch requires a status");
        }
        questions = questions == null ? List.of() : List.copyOf(questions);
        unsupportedTargets = SemanticDraftTexts.list(
                unsupportedTargets, "unsupportedTarget", 64, 256);
        if (status == ClarificationBatchStatus.READY
                && (!questions.isEmpty() || !unsupportedTargets.isEmpty())) {
            throw new IllegalArgumentException("A ready clarification batch cannot carry unresolved targets");
        }
        if (status == ClarificationBatchStatus.NEEDS_INPUT && questions.isEmpty()) {
            throw new IllegalArgumentException("A needs-input batch requires questions");
        }
        if (status == ClarificationBatchStatus.UNSUPPORTED && unsupportedTargets.isEmpty()) {
            throw new IllegalArgumentException("An unsupported batch requires unsupported targets");
        }
    }
}
