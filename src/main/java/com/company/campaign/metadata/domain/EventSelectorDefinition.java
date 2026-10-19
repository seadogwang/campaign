package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Versioned business meaning for selecting one event, independent of physical storage. */
public record EventSelectorDefinition(
        String id,
        String displayName,
        List<String> aliases,
        SubjectKind subject,
        EventSelectorKind selectorKind,
        boolean unscopedByDefault,
        List<EventSelectorBinding> bindings,
        String disambiguation) {

    public EventSelectorDefinition {
        id = MetadataTexts.requireId(id, "eventSelector.id");
        displayName = MetadataTexts.requireDisplayName(displayName, "eventSelector.displayName");
        aliases = MetadataTexts.canonicalTextList(aliases, "eventSelector.alias");
        MetadataTexts.requireNoCollision(displayName, aliases, "eventSelector.alias");
        subject = MetadataTexts.requireNonNull(
                subject, "eventSelector.subject", MetadataErrorCodes.METADATA_VALUE_INVALID);
        selectorKind = MetadataTexts.requireNonNull(
                selectorKind, "eventSelector.kind", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (bindings == null || bindings.isEmpty()) {
            throw new MetadataException(MetadataErrorCodes.METADATA_VALUE_INVALID,
                    "An event selector requires at least one relation binding");
        }
        List<EventSelectorBinding> values = new ArrayList<>(bindings);
        Set<String> relationIds = new HashSet<>();
        for (EventSelectorBinding binding : values) {
            if (binding == null || !relationIds.add(binding.relationId())) {
                throw new MetadataException(MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Event selector bindings must be unique by relation ID");
            }
        }
        values.sort(java.util.Comparator.comparing(EventSelectorBinding::relationId));
        bindings = List.copyOf(values);
        disambiguation = MetadataTexts.optionalText(disambiguation, "eventSelector.disambiguation");
    }

    public EventSelectorBinding bindingFor(String relationId) {
        return bindings.stream().filter(binding -> binding.relationId().equals(relationId)).findFirst().orElse(null);
    }
}
