package com.company.campaign.builder.segment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict non-executable requirement understanding used before formal Segment Intent construction. */
public record SemanticDraft(
        String draftVersion,
        List<SemanticFactDraft> facts,
        List<SemanticSlot> globalSlots) {

    public static final String VERSION = "1.0";

    public SemanticDraft {
        if (!VERSION.equals(draftVersion)) {
            throw new IllegalArgumentException("Unsupported semantic draft version");
        }
        facts = facts == null ? List.of() : List.copyOf(new ArrayList<>(facts));
        if (facts.isEmpty() || facts.size() > 64) {
            throw new IllegalArgumentException("A semantic draft requires bounded facts");
        }
        Set<String> factIds = new HashSet<>();
        for (SemanticFactDraft fact : facts) {
            if (fact == null || !factIds.add(fact.factId())) {
                throw new IllegalArgumentException("Semantic draft fact IDs must be non-null and unique");
            }
        }
        globalSlots = globalSlots == null ? List.of() : List.copyOf(new ArrayList<>(globalSlots));
        if (globalSlots.size() > SemanticSlotType.values().length) {
            throw new IllegalArgumentException("A semantic draft exceeds its global-slot limit");
        }
        Set<SemanticSlotType> globalTypes = new HashSet<>();
        for (SemanticSlot slot : globalSlots) {
            if (slot == null || !globalTypes.add(slot.slotType())) {
                throw new IllegalArgumentException("Global semantic slot types must be non-null and unique");
            }
        }
    }
}
