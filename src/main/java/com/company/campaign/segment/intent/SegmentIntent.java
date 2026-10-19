package com.company.campaign.segment.intent;

import com.company.campaign.contracts.ContractVersions;
import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.SubjectKind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The strict structured intent the model returns before any DSL exists.
 *
 * <p>It is immutable, closed and free of every trusted or executable concern: no tenant, program, user,
 * DataScope, permission, SQL, Gate, activation, Save or Publish field exists on it, and the strict codec
 * rejects any such property instead of ignoring it. Clauses have unique bounded ids, the logical expression
 * references exactly those ids once each, and every clause carries exactly one provenance form.
 *
 * <p>An Intent is not a Segment DSL and never becomes one here: raw values stay raw, candidate ids stay
 * hints, and resolution is a separate deterministic step.
 *
 * <p>A request that asks for an existing audience is a first-class {@link IntentAudienceReference} beside the
 * clauses rather than a clause with unusual slots. Its identity shares the one condition namespace with clause
 * ids — the logical expression addresses both through the same leaf — while its meaning stays separate: it
 * names untrusted candidate text, never a version pin, and it is resolved by a different deterministic step.
 */
public record SegmentIntent(
        SchemaVersion intentVersion,
        SubjectKind subject,
        List<IntentClause> clauses,
        LogicalExpression logicalExpression,
        List<IntentAmbiguity> ambiguities,
        List<UnsupportedRequirement> unsupportedRequirements,
        List<IntentWindowAmbiguity> windowAmbiguities,
        List<IntentFilterScopeAmbiguity> filterScopeAmbiguities,
        List<IntentAudienceReference> audienceReferences
) {

    public SegmentIntent {
        if (!ContractVersions.isCurrent(intentVersion)) {
            throw new IntentException(
                    IntentErrorCodes.VERSION_UNSUPPORTED,
                    "The Intent version is not the exact supported version");
        }
        if (subject != SubjectKind.MEMBER) {
            // v1.3 supports exactly one root subject; an additional root subject is out of scope.
            throw IntentException.invalid(
                    IntentErrorCodes.SUBJECT_INVALID, "An Intent requires the one supported subject");
        }
        clauses = clauses == null ? List.of() : List.copyOf(boundedClauses(clauses));
        audienceReferences = boundedAudienceReferences(audienceReferences);
        if (clauses.isEmpty() && audienceReferences.isEmpty()) {
            throw IntentException.invalid(
                    IntentErrorCodes.CLAUSES_INVALID,
                    "An Intent requires at least one clause or audience reference");
        }
        Map<String, IntentClause> byId = new LinkedHashMap<>();
        for (IntentClause clause : clauses) {
            if (clause == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.CLAUSES_INVALID, "An Intent clause must not be null");
            }
            if (byId.putIfAbsent(clause.clauseId(), clause) != null) {
                throw IntentException.invalid(
                        IntentErrorCodes.DUPLICATE_CLAUSE_ID, "Two clauses share one clause id");
            }
        }
        Set<String> conditionIds = new LinkedHashSet<>(byId.keySet());
        for (IntentAudienceReference reference : audienceReferences) {
            // A clause and an audience reference share one condition namespace, so the logical expression can
            // address exactly one of them by an id that is unambiguous.
            if (!conditionIds.add(reference.referenceId())) {
                throw IntentException.invalid(
                        IntentErrorCodes.DUPLICATE_CLAUSE_ID,
                        "A clause and an audience reference share one condition id");
            }
        }
        if (logicalExpression == null) {
            throw IntentException.invalid(
                    IntentErrorCodes.EXPRESSION_INVALID, "An Intent requires a logical expression");
        }
        logicalExpression.validateAgainst(conditionIds);
        ambiguities = uniqueEnum(ambiguities, IntentAmbiguity.values().length, "ambiguities");
        unsupportedRequirements =
                uniqueEnum(unsupportedRequirements, UnsupportedRequirement.values().length, "unsupportedRequirements");
        windowAmbiguities = validateWindowAmbiguities(windowAmbiguities, byId, ambiguities);
        filterScopeAmbiguities = validateFilterScopeAmbiguities(filterScopeAmbiguities, byId, ambiguities);
    }

    public SegmentIntent(
            SchemaVersion intentVersion,
            SubjectKind subject,
            List<IntentClause> clauses,
            LogicalExpression logicalExpression,
            List<IntentAmbiguity> ambiguities,
            List<UnsupportedRequirement> unsupportedRequirements) {
        this(intentVersion, subject, clauses, logicalExpression, ambiguities, unsupportedRequirements,
                List.of(), List.of(), List.of());
    }

    public SegmentIntent(
            SchemaVersion intentVersion,
            SubjectKind subject,
            List<IntentClause> clauses,
            LogicalExpression logicalExpression,
            List<IntentAmbiguity> ambiguities,
            List<UnsupportedRequirement> unsupportedRequirements,
            List<IntentWindowAmbiguity> windowAmbiguities) {
        this(intentVersion, subject, clauses, logicalExpression, ambiguities, unsupportedRequirements,
                windowAmbiguities, List.of(), List.of());
    }

    public SegmentIntent(
            SchemaVersion intentVersion,
            SubjectKind subject,
            List<IntentClause> clauses,
            LogicalExpression logicalExpression,
            List<IntentAmbiguity> ambiguities,
            List<UnsupportedRequirement> unsupportedRequirements,
            List<IntentWindowAmbiguity> windowAmbiguities,
            List<IntentFilterScopeAmbiguity> filterScopeAmbiguities) {
        this(intentVersion, subject, clauses, logicalExpression, ambiguities, unsupportedRequirements,
                windowAmbiguities, filterScopeAmbiguities, List.of());
    }

    /** Clause ids in declaration order. */
    public List<String> clauseIds() {
        List<String> ids = new ArrayList<>(clauses.size());
        for (IntentClause clause : clauses) {
            ids.add(clause.clauseId());
        }
        return List.copyOf(ids);
    }

    /** True when this Intent asks for at least one Existing Audience Reference. */
    public boolean hasAudienceReferences() {
        return !audienceReferences.isEmpty();
    }

    /** The audience reference with the given identity, or null when this Intent carries none. */
    public IntentAudienceReference audienceReference(String referenceId) {
        for (IntentAudienceReference reference : audienceReferences) {
            if (reference.referenceId().equals(referenceId)) {
                return reference;
            }
        }
        return null;
    }

    private static List<IntentClause> boundedClauses(List<IntentClause> clauses) {
        if (clauses.isEmpty()) {
            return List.of();
        }
        if (clauses.size() > IntentLimits.MAX_CLAUSES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The Intent exceeds the clause limit");
        }
        return clauses;
    }

    private static List<IntentAudienceReference> boundedAudienceReferences(
            List<IntentAudienceReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        if (references.size() > IntentLimits.MAX_AUDIENCE_REFERENCES) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "The Intent exceeds the audience-reference limit");
        }
        for (IntentAudienceReference reference : references) {
            if (reference == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.AUDIENCE_REFERENCE_INVALID,
                        "An Intent audience reference must not be null");
            }
        }
        return List.copyOf(references);
    }

    private static <E> List<E> uniqueEnum(List<E> values, int limit, String field) {
        if (values == null) {
            return List.of();
        }
        if (values.size() > limit) {
            throw new IntentException(
                    IntentErrorCodes.LIMIT_EXCEEDED, "Intent list '" + field + "' exceeds the entry limit");
        }
        Set<E> unique = new LinkedHashSet<>();
        List<E> copy = new ArrayList<>(values.size());
        for (E value : values) {
            if (value == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.MACHINE_CODE_INVALID, "Intent list '" + field + "' contains a null");
            }
            if (!unique.add(value)) {
                throw new IntentException(
                        IntentErrorCodes.MACHINE_CODE_INVALID, "Intent list '" + field + "' repeats an entry");
            }
            copy.add(value);
        }
        return List.copyOf(copy);
    }

    private static List<IntentWindowAmbiguity> validateWindowAmbiguities(
            List<IntentWindowAmbiguity> values,
            Map<String, IntentClause> clauses,
            List<IntentAmbiguity> ambiguities) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (!ambiguities.contains(IntentAmbiguity.AMBIGUOUS_WINDOW)
                || values.size() > IntentLimits.MAX_CODE_ENTRIES) {
            throw IntentException.invalid(
                    IntentErrorCodes.WINDOW_INVALID,
                    "Structured window ambiguities require the AMBIGUOUS_WINDOW code and bounded entries");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (IntentWindowAmbiguity ambiguity : values) {
            if (ambiguity == null || !targets.add(ambiguity.targetClauseId())) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "Structured window ambiguities require unique non-null targets");
            }
            IntentClause target = clauses.get(ambiguity.targetClauseId());
            if (target == null || target.window() == null) {
                throw IntentException.invalid(
                        IntentErrorCodes.WINDOW_INVALID,
                        "A structured window ambiguity must target a clause with a window");
            }
            for (IntentWindowAlternative option : ambiguity.options()) {
                if (option instanceof IntentWindowAlternative.InheritWindow inherit) {
                    IntentClause source = clauses.get(inherit.fromClauseId());
                    if (source == null || source.window() == null
                            || source.clauseId().equals(target.clauseId())) {
                        throw IntentException.invalid(
                                IntentErrorCodes.WINDOW_INVALID,
                                "An inherited window alternative requires another clause with a window");
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    private static List<IntentFilterScopeAmbiguity> validateFilterScopeAmbiguities(
            List<IntentFilterScopeAmbiguity> values,
            Map<String, IntentClause> clauses,
            List<IntentAmbiguity> ambiguities) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        if (!ambiguities.contains(IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE)
                || values.size() > IntentLimits.MAX_CODE_ENTRIES) {
            throw IntentException.invalid(
                    IntentErrorCodes.SCOPE_FILTER_INVALID,
                    "Structured filter-scope ambiguities require their ambiguity code and bounded entries");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (IntentFilterScopeAmbiguity ambiguity : values) {
            if (ambiguity == null || !targets.add(ambiguity.targetClauseId())) {
                throw IntentException.invalid(
                        IntentErrorCodes.SCOPE_FILTER_INVALID,
                        "Structured filter-scope ambiguities require unique non-null targets");
            }
            IntentClause target = clauses.get(ambiguity.targetClauseId());
            if (target == null || target.definitionKind() != com.company.campaign.metadata.MetadataKind.METRIC) {
                throw IntentException.invalid(
                        IntentErrorCodes.SCOPE_FILTER_INVALID,
                        "A structured filter-scope ambiguity must target a Metric clause");
            }
            for (IntentFilterScopeAlternative option : ambiguity.options()) {
                if (option instanceof IntentFilterScopeAlternative.InheritScopeFilters inherit) {
                    IntentClause source = clauses.get(inherit.fromClauseId());
                    if (source == null || source.scopeFilters().isEmpty()
                            || source.clauseId().equals(target.clauseId())) {
                        throw IntentException.invalid(
                                IntentErrorCodes.SCOPE_FILTER_INVALID,
                                "An inherited filter-scope alternative requires another filtered clause");
                    }
                }
            }
        }
        return List.copyOf(values);
    }
}
