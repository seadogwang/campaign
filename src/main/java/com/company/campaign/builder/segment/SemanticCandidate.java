package com.company.campaign.builder.segment;

/** One bounded machine value, user-facing label and optional unverified model suggestion. */
public record SemanticCandidate(String value, String label, boolean modelSuggested) {

    public SemanticCandidate(String value, String label) {
        this(value, label, false);
    }

    public SemanticCandidate {
        value = SemanticDraftTexts.required(value, "candidate.value", 256);
        label = SemanticDraftTexts.required(label, "candidate.label", 256);
    }
}
