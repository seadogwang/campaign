package com.company.campaign.builder.segment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One semantic field whose resolved value or uncertainty is explicit and machine-checkable. */
public record SemanticSlot(
        SemanticSlotType slotType,
        SemanticSlotStatus status,
        String value,
        List<SemanticCandidate> candidates,
        String reasonCode) {

    public SemanticSlot {
        if (slotType == null || status == null) {
            throw new IllegalArgumentException("A semantic slot requires type and status");
        }
        candidates = candidates == null ? List.of() : List.copyOf(new ArrayList<>(candidates));
        if (candidates.size() > 32) {
            throw new IllegalArgumentException("A semantic slot exceeds its candidate limit");
        }
        Set<String> values = new HashSet<>();
        int recommendations = 0;
        for (SemanticCandidate candidate : candidates) {
            if (candidate == null || !values.add(candidate.value())) {
                throw new IllegalArgumentException("Semantic slot candidates must be non-null and unique");
            }
            if (candidate.modelSuggested()) {
                recommendations++;
            }
        }
        if (recommendations > 1) {
            throw new IllegalArgumentException("A semantic slot may suggest at most one candidate");
        }
        switch (status) {
            case RESOLVED -> {
                value = SemanticDraftTexts.required(value, "slot.value", 1_024);
                if (!candidates.isEmpty() || reasonCode != null) {
                    throw new IllegalArgumentException("A resolved semantic slot cannot carry uncertainty");
                }
            }
            case AMBIGUOUS -> {
                requireNoValue(value);
                reasonCode = SemanticDraftTexts.reasonCode(reasonCode);
                if (candidates.size() < 2) {
                    throw new IllegalArgumentException("An ambiguous semantic slot requires at least two candidates");
                }
            }
            case PROPOSED -> {
                requireNoValue(value);
                reasonCode = SemanticDraftTexts.reasonCode(reasonCode);
                if (slotType != SemanticSlotType.EXPRESSION) {
                    throw new IllegalArgumentException("Only a semantic expression may carry model proposals");
                }
                if (candidates.isEmpty()) {
                    throw new IllegalArgumentException("A proposed semantic slot requires candidates");
                }
                if (recommendations != 1) {
                    throw new IllegalArgumentException("A proposed semantic slot requires one model suggestion");
                }
            }
            case MISSING, UNSUPPORTED -> {
                requireNoValue(value);
                reasonCode = SemanticDraftTexts.reasonCode(reasonCode);
                if (!candidates.isEmpty()) {
                    throw new IllegalArgumentException("A missing or unsupported slot cannot carry candidates");
                }
            }
        }
    }

    private static void requireNoValue(String value) {
        if (value != null) {
            throw new IllegalArgumentException("An unresolved semantic slot cannot carry a selected value");
        }
    }
}
