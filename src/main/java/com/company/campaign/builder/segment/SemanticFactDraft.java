package com.company.campaign.builder.segment;

import com.company.campaign.contracts.SourceSpan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One source-grounded business fact and all semantic fields currently understood about it. */
public record SemanticFactDraft(
        String factId,
        SourceSpan sourceSpan,
        List<String> searchTerms,
        List<SemanticSlot> slots) {

    public SemanticFactDraft {
        factId = SemanticDraftTexts.identifier(factId, "factId");
        if (sourceSpan == null || sourceSpan.isEmpty()) {
            throw new IllegalArgumentException("A semantic fact requires a non-empty source span");
        }
        searchTerms = SemanticDraftTexts.list(searchTerms, "searchTerm", 16, 128);
        if (searchTerms.isEmpty()) {
            throw new IllegalArgumentException("A semantic fact requires at least one search term");
        }
        slots = slots == null ? List.of() : List.copyOf(new ArrayList<>(slots));
        if (slots.isEmpty() || slots.size() > SemanticSlotType.values().length) {
            throw new IllegalArgumentException("A semantic fact requires bounded semantic slots");
        }
        Set<SemanticSlotType> types = new HashSet<>();
        for (SemanticSlot slot : slots) {
            if (slot == null || !types.add(slot.slotType())) {
                throw new IllegalArgumentException("Semantic fact slot types must be non-null and unique");
            }
        }
        if (!types.contains(SemanticSlotType.CAPABILITY_TYPE)) {
            throw new IllegalArgumentException("A semantic fact requires a capability-type slot");
        }
    }
}
