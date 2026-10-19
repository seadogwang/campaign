package com.company.campaign.builder.segment;

/** One deterministic option derived from model candidates already bounded by the host contract. */
public record StagedClarificationOption(String optionId, String value, String label, boolean modelSuggested) {

    public StagedClarificationOption {
        optionId = SemanticDraftTexts.identifier(optionId, "optionId");
        value = SemanticDraftTexts.required(value, "option.value", 256);
        label = SemanticDraftTexts.required(label, "option.label", 256);
    }
}
