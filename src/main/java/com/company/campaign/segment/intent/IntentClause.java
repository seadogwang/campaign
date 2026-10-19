package com.company.campaign.segment.intent;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.RelationScope;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One semantic condition, immutable and carrying every slot separately.
 *
 * <p>Concept, definition kind, negation, operator, raw value, window, currency, aggregation and relation
 * scope are distinct slots rather than a merged blob, so resolution can compare each requested capability
 * against a Metadata definition and report exactly which one is incompatible. Every clause carries exactly
 * one {@link IntentProvenance}. Candidate ids are hints only: they can neither establish existence nor
 * authorize a definition.
 */
public record IntentClause(
        String clauseId,
        String concept,
        MetadataKind definitionKind,
        boolean negated,
        ComparisonOperator operator,
        IntentValue value,
        IntentWindow window,
        IntentCurrency currency,
        AggregateFunction aggregation,
        RelationScope relationScope,
        List<IntentScopeFilter> scopeFilters,
        IntentProvenance provenance,
        List<String> candidateHints,
        List<String> assumptions
) {

    public IntentClause {
        clauseId = IntentTexts.requireReferenceId(clauseId, "clauseId");
        if (clauseId.length() > IntentLimits.MAX_CLAUSE_ID_LENGTH) {
            throw new IntentException(
                    IntentErrorCodes.CLAUSE_ID_INVALID, "A clause id exceeds the length limit");
        }
        concept = IntentTexts.requireCode(concept, "concept");
        if (provenance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.PROVENANCE_INVALID, "Every clause requires exactly one provenance form");
        }
        scopeFilters = boundedScopeFilters(scopeFilters, definitionKind);
        candidateHints = IntentTexts.boundedReferenceIds(candidateHints, "candidateHints");
        assumptions = IntentTexts.boundedCodes(assumptions, "assumptions");
    }

    /** Backward-compatible constructor for clauses without dynamic Metric-scoped filters. */
    public IntentClause(
            String clauseId,
            String concept,
            MetadataKind definitionKind,
            boolean negated,
            ComparisonOperator operator,
            IntentValue value,
            IntentWindow window,
            IntentCurrency currency,
            AggregateFunction aggregation,
            RelationScope relationScope,
            IntentProvenance provenance,
            List<String> candidateHints,
            List<String> assumptions) {
        this(
                clauseId, concept, definitionKind, negated, operator, value, window, currency, aggregation,
                relationScope, List.of(), provenance, candidateHints, assumptions);
    }

    private static List<IntentScopeFilter> boundedScopeFilters(
            List<IntentScopeFilter> values, MetadataKind definitionKind) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (definitionKind != MetadataKind.METRIC) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "Only a Metric clause can carry scoped dynamic filters");
        }
        if (values.size() > IntentLimits.MAX_SCOPE_FILTERS) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "A Metric clause exceeds the scope-filter limit");
        }
        Set<String> unique = new LinkedHashSet<>();
        List<IntentScopeFilter> copy = new ArrayList<>(values.size());
        for (IntentScopeFilter value : values) {
            if (value == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.SCOPE_FILTER_INVALID, "A scope filter must not be null");
            }
            if (!unique.add(value.filterId())) {
                throw new IntentException(
                        IntentErrorCodes.DUPLICATE_SCOPE_FILTER_ID,
                        "Two Metric-scoped filters share one filter id");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }
}
