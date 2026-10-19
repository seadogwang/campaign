package com.company.campaign.segment.intent;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.FilterDomain;
import com.company.campaign.metadata.domain.FilterIdentifierKind;

import java.util.List;

/**
 * One user-supplied Attribute condition whose rows are scoped inside a Metric aggregate.
 *
 * <p>This is an unresolved Intent shape, not a database filter and not SQL. The model supplies a semantic
 * concept, raw typed value, grounded provenance and optional Metadata candidate hints. A later deterministic
 * resolver must prove that the selected Attribute is an allowlisted filter dimension of the selected Metric
 * on the same relation scope before this condition can reach a DSL or query plan.
 */
public record IntentScopeFilter(
        String filterId,
        String concept,
        FilterDomain domain,
        FilterIdentifierKind identifierKind,
        ComparisonOperator operator,
        IntentValue value,
        IntentProvenance provenance,
        List<String> candidateHints,
        List<String> assumptions
) {

    public IntentScopeFilter {
        filterId = IntentTexts.requireReferenceId(filterId, "scopeFilter.filterId");
        concept = IntentTexts.requireCode(concept, "scopeFilter.concept");
        if (domain == null || identifierKind == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "A scope filter requires domain and identifier semantics");
        }
        if (operator == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID, "A scope filter requires an operator");
        }
        requireValueShape(operator, value);
        if (provenance == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.PROVENANCE_INVALID, "A scope filter requires exactly one provenance form");
        }
        candidateHints = IntentTexts.boundedReferenceIds(candidateHints, "scopeFilter.candidateHints");
        assumptions = IntentTexts.boundedCodes(assumptions, "scopeFilter.assumptions");
    }

    /** Backward-compatible programmatic form for callers without two-level filter semantics. */
    public IntentScopeFilter(
            String filterId,
            String concept,
            ComparisonOperator operator,
            IntentValue value,
            IntentProvenance provenance,
            List<String> candidateHints,
            List<String> assumptions) {
        this(filterId, concept, FilterDomain.UNSPECIFIED, FilterIdentifierKind.UNSPECIFIED,
                operator, value, provenance, candidateHints, assumptions);
    }

    private static void requireValueShape(ComparisonOperator operator, IntentValue value) {
        if (operator.isNullOperator()) {
            if (value != null) {
                throw IntentException.invalid(
                        IntentErrorCodes.SCOPE_FILTER_INVALID,
                        "A NULL scope-filter operator must not carry a value");
            }
            return;
        }
        if (value == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "A non-NULL scope-filter operator requires a value");
        }
        switch (operator) {
            case IN, NOT_IN -> {
                if (!(value instanceof IntentValue.ListValue)) {
                    throw IntentException.invalid(
                            IntentErrorCodes.SCOPE_FILTER_INVALID,
                            "A list scope-filter operator requires a LIST value");
                }
            }
            case BETWEEN -> {
                if (!(value instanceof IntentValue.RangeValue)) {
                    throw IntentException.invalid(
                            IntentErrorCodes.SCOPE_FILTER_INVALID,
                            "A range scope-filter operator requires a RANGE value");
                }
            }
            default -> {
                if (!(value instanceof IntentValue.Scalar)) {
                    throw IntentException.invalid(
                            IntentErrorCodes.SCOPE_FILTER_INVALID,
                            "A scalar scope-filter operator requires a scalar value");
                }
            }
        }
    }
}
