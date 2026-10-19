package com.company.campaign.segment.intent;

/** One bounded interpretation for a row filter whose scope across coordinated Metrics is ambiguous. */
public sealed interface IntentFilterScopeAlternative
        permits IntentFilterScopeAlternative.InheritScopeFilters, IntentFilterScopeAlternative.Independent {

    /** Applies the exact scope filters already carried by another clause to the target clause. */
    record InheritScopeFilters(String fromClauseId) implements IntentFilterScopeAlternative {
        public InheritScopeFilters {
            fromClauseId = IntentTexts.requireReferenceId(
                    fromClauseId, "filterScopeAlternative.fromClauseId");
        }
    }

    /** Keeps the target Metric independent of the other clause's row filters. */
    record Independent() implements IntentFilterScopeAlternative {
    }
}
