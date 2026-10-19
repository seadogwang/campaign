package com.company.campaign.segment.intent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A structured ambiguity identifying one Metric's scope filters and the permitted interpretations. */
public record IntentFilterScopeAmbiguity(
        String targetClauseId,
        List<IntentFilterScopeAlternative> options) {

    public IntentFilterScopeAmbiguity {
        targetClauseId = IntentTexts.requireReferenceId(
                targetClauseId, "filterScopeAmbiguity.targetClauseId");
        if (options == null || options.size() < 2 || options.size() > 8) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "A filter-scope ambiguity requires between two and eight alternatives");
        }
        Set<IntentFilterScopeAlternative> unique = new LinkedHashSet<>();
        for (IntentFilterScopeAlternative option : options) {
            if (option == null || !unique.add(option)) {
                throw IntentException.invalid(
                        IntentErrorCodes.SCOPE_FILTER_INVALID,
                        "Filter-scope ambiguity alternatives must be non-null and unique");
            }
        }
        options = List.copyOf(options);
    }

    public String targetPath() {
        return targetClauseId + ".scopeFilters";
    }
}
