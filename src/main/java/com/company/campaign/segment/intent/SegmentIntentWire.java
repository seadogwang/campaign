package com.company.campaign.segment.intent;

import com.company.campaign.metadata.domain.RelationScope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one projection of the Intent model onto its JSON wire shape.
 *
 * <p>Both serialization and hashing go through this class, so the document's canonical bytes and the Intent
 * hash can never disagree about a field or an order. Nullable slots are omitted rather than written as
 * {@code null}, so a canonical document contains exactly the populated intent. Nothing here consults
 * Jackson polymorphism, default typing, the JVM default locale or the default timezone.
 */
final class SegmentIntentWire {

    private SegmentIntentWire() {
    }

    static Map<String, Object> root(SegmentIntent intent) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("intentVersion", intent.intentVersion().value());
        wire.put("subject", intent.subject().name());
        List<Object> clauses = new ArrayList<>(intent.clauses().size());
        for (IntentClause clause : intent.clauses()) {
            clauses.add(clause(clause));
        }
        wire.put("clauses", clauses);
        wire.put("logicalExpression", expression(intent.logicalExpression()));
        wire.put("ambiguities", ambiguities(intent));
        wire.put("unsupportedRequirements", names(intent.unsupportedRequirements()));
        if (intent.hasAudienceReferences()) {
            // The member is written only when the Intent really carries a reference, so an Intent with no
            // reference projects to exactly the bytes — and therefore the hash — it always did. A reference
            // Intent is a new artifact rather than a reinterpretation of an existing one.
            List<Object> references = new ArrayList<>(intent.audienceReferences().size());
            for (IntentAudienceReference reference : intent.audienceReferences()) {
                references.add(audienceReference(reference));
            }
            wire.put("audienceReferences", references);
        }
        return wire;
    }

    /**
     * The one projection of a first-class audience reference.
     *
     * <p>It writes the stable identity, the bounded candidate texts and the one provenance form — and nothing
     * else. There is no version pin, no owner, no scope and no Gate fact to write, because the record has none.
     */
    static Map<String, Object> audienceReference(IntentAudienceReference reference) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("referenceId", reference.referenceId());
        wire.put("candidateTexts", List.copyOf(reference.candidateTexts()));
        wire.put("provenance", provenance(reference.provenance()));
        return wire;
    }

    private static List<Object> ambiguities(SegmentIntent intent) {
        List<Object> values = new ArrayList<>();
        for (IntentAmbiguity ambiguity : intent.ambiguities()) {
            if (ambiguity == IntentAmbiguity.AMBIGUOUS_FILTER_SCOPE
                    && !intent.filterScopeAmbiguities().isEmpty()) {
                for (IntentFilterScopeAmbiguity filterScope : intent.filterScopeAmbiguities()) {
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("code", ambiguity.name());
                    structured.put("target", filterScope.targetPath());
                    List<Object> options = new ArrayList<>(filterScope.options().size());
                    for (IntentFilterScopeAlternative option : filterScope.options()) {
                        Map<String, Object> value = new LinkedHashMap<>();
                        if (option instanceof IntentFilterScopeAlternative.InheritScopeFilters inherit) {
                            value.put("kind", "INHERIT_SCOPE_FILTERS");
                            value.put("fromClauseId", inherit.fromClauseId());
                        } else {
                            value.put("kind", "INDEPENDENT");
                        }
                        options.add(value);
                    }
                    structured.put("options", options);
                    values.add(structured);
                }
                continue;
            }
            if (ambiguity != IntentAmbiguity.AMBIGUOUS_WINDOW || intent.windowAmbiguities().isEmpty()) {
                values.add(ambiguity.name());
                continue;
            }
            for (IntentWindowAmbiguity windowAmbiguity : intent.windowAmbiguities()) {
                Map<String, Object> structured = new LinkedHashMap<>();
                structured.put("code", ambiguity.name());
                structured.put("target", windowAmbiguity.targetPath());
                List<Object> options = new ArrayList<>(windowAmbiguity.options().size());
                for (IntentWindowAlternative option : windowAmbiguity.options()) {
                    if (option instanceof IntentWindowAlternative.InheritWindow inherit) {
                        Map<String, Object> inherited = new LinkedHashMap<>();
                        inherited.put("kind", "INHERIT_WINDOW");
                        inherited.put("fromClauseId", inherit.fromClauseId());
                        options.add(inherited);
                    } else {
                        options.add(window(((IntentWindowAlternative.ExplicitWindow) option).window()));
                    }
                }
                structured.put("options", options);
                values.add(structured);
            }
        }
        return List.copyOf(values);
    }

    static Map<String, Object> clause(IntentClause clause) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("clauseId", clause.clauseId());
        wire.put("concept", clause.concept());
        if (clause.definitionKind() != null) {
            wire.put("definitionKind", clause.definitionKind().name());
        }
        wire.put("negated", clause.negated());
        if (clause.operator() != null) {
            wire.put("operator", clause.operator().name());
        }
        if (clause.value() != null) {
            wire.put("value", value(clause.value()));
        }
        if (clause.window() != null) {
            wire.put("window", window(clause.window()));
        }
        if (clause.currency() != null) {
            wire.put("currency", currency(clause.currency()));
        }
        if (clause.aggregation() != null) {
            wire.put("aggregation", clause.aggregation().name());
        }
        if (clause.relationScope() != null) {
            wire.put("relationScope", relationScope(clause.relationScope()));
        }
        if (!clause.scopeFilters().isEmpty()) {
            List<Object> filters = new ArrayList<>(clause.scopeFilters().size());
            for (IntentScopeFilter filter : clause.scopeFilters()) {
                filters.add(scopeFilter(filter));
            }
            wire.put("scopeFilters", filters);
        }
        wire.put("provenance", provenance(clause.provenance()));
        wire.put("candidateHints", List.copyOf(clause.candidateHints()));
        wire.put("assumptions", List.copyOf(clause.assumptions()));
        return wire;
    }

    static Map<String, Object> scopeFilter(IntentScopeFilter filter) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("filterId", filter.filterId());
        wire.put("concept", filter.concept());
        wire.put("domain", filter.domain().name());
        wire.put("identifierKind", filter.identifierKind().name());
        wire.put("operator", filter.operator().name());
        if (filter.value() != null) {
            wire.put("value", value(filter.value()));
        }
        wire.put("provenance", provenance(filter.provenance()));
        wire.put("candidateHints", List.copyOf(filter.candidateHints()));
        wire.put("assumptions", List.copyOf(filter.assumptions()));
        return wire;
    }

    static Map<String, Object> provenance(IntentProvenance provenance) {
        Map<String, Object> wire = new LinkedHashMap<>();
        if (provenance instanceof IntentProvenance.FromSourceSpan fromSpan) {
            wire.put("type", "SOURCE_SPAN");
            wire.put("text", fromSpan.span().text());
            wire.put("start", fromSpan.span().start());
            wire.put("end", fromSpan.span().end());
        } else {
            IntentProvenance.FromRegisteredDefault fromDefault =
                    (IntentProvenance.FromRegisteredDefault) provenance;
            wire.put("type", "REGISTERED_DEFAULT");
            wire.put("defaultId", fromDefault.defaultId());
            wire.put("version", fromDefault.version());
            wire.put("contentHash", fromDefault.contentHash().value());
        }
        return wire;
    }

    static Map<String, Object> value(IntentValue value) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", value.kind().name());
        if (value instanceof IntentValue.ListValue list) {
            List<Object> items = new ArrayList<>(list.items().size());
            for (IntentValue.Scalar item : list.items()) {
                items.add(value(item));
            }
            wire.put("items", items);
        } else if (value instanceof IntentValue.RangeValue range) {
            wire.put("lower", value(range.lower()));
            wire.put("upper", value(range.upper()));
            wire.put("includeLower", range.includeLower());
            wire.put("includeUpper", range.includeUpper());
        } else if (value instanceof IntentValue.BooleanValue booleanValue) {
            wire.put("raw", booleanValue.raw());
        } else {
            wire.put("raw", raw(value));
        }
        return wire;
    }

    static Map<String, Object> window(IntentWindow window) {
        Map<String, Object> wire = new LinkedHashMap<>();
        if (window instanceof IntentWindow.CalendarMonths calendarMonths) {
            wire.put("kind", "CALENDAR_MONTHS");
            wire.put("count", calendarMonths.count());
            wire.put("includeCurrent", calendarMonths.includeCurrent());
            wire.put("bucketRequirement", calendarMonths.bucketRequirement().name());
        } else if (window instanceof IntentWindow.Rolling rolling) {
            wire.put("kind", window.kind().name());
            wire.put("amount", rolling.amount());
            wire.put("unit", rolling.unit().name());
        } else if (window instanceof IntentWindow.Fixed fixed) {
            wire.put("kind", window.kind().name());
            wire.put("start", fixed.start());
            wire.put("end", fixed.end());
        } else if (window instanceof IntentWindow.RelativeToAttribute relative) {
            wire.put("kind", window.kind().name());
            wire.put("anchorDefinitionId", relative.anchorDefinitionId());
            wire.put("startOffset", relative.startOffset());
            wire.put("endOffset", relative.endOffset());
            wire.put("unit", relative.unit().name());
        } else if (window instanceof IntentWindow.SelectedEvent selected) {
            wire.put("kind", window.kind().name());
            wire.put("selectorId", selected.selectorId());
            List<Object> filters = new ArrayList<>(selected.selectorScopeFilters().size());
            selected.selectorScopeFilters().forEach(filter -> filters.add(scopeFilter(filter)));
            wire.put("selectorScopeFilters", filters);
        } else if (window instanceof IntentWindow.RelativeToEvent relative) {
            wire.put("kind", window.kind().name());
            wire.put("selectorId", relative.selectorId());
            List<Object> filters = new ArrayList<>(relative.selectorScopeFilters().size());
            relative.selectorScopeFilters().forEach(filter -> filters.add(scopeFilter(filter)));
            wire.put("selectorScopeFilters", filters);
            wire.put("startOffset", relative.startOffset());
            wire.put("endOffset", relative.endOffset());
            wire.put("unit", relative.unit().name());
            wire.put("excludeAnchorEvent", relative.excludeAnchorEvent());
        } else {
            wire.put("kind", window.kind().name());
        }
        return wire;
    }

    static Map<String, Object> currency(IntentCurrency currency) {
        Map<String, Object> wire = new LinkedHashMap<>();
        if (currency instanceof IntentCurrency.Explicit explicit) {
            wire.put("mode", "EXPLICIT");
            wire.put("code", explicit.code());
        } else {
            IntentCurrency.RegisteredDefault registered = (IntentCurrency.RegisteredDefault) currency;
            wire.put("mode", "REGISTERED_DEFAULT");
            wire.put("defaultId", registered.defaultId());
            wire.put("version", registered.version());
            wire.put("contentHash", registered.contentHash().value());
        }
        return wire;
    }

    static Map<String, Object> relationScope(RelationScope scope) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("joinKind", scope.joinKind().name());
        if (scope.sourceJoinField() != null) {
            wire.put("sourceJoinField", scope.sourceJoinField());
        }
        if (scope.targetJoinField() != null) {
            wire.put("targetJoinField", scope.targetJoinField());
        }
        return wire;
    }

    static Map<String, Object> expression(LogicalExpression expression) {
        Map<String, Object> wire = new LinkedHashMap<>();
        if (expression instanceof LogicalExpression.Clause clause) {
            wire.put("type", "CLAUSE");
            wire.put("clauseId", clause.clauseId());
        } else if (expression instanceof LogicalExpression.Negation negation) {
            wire.put("type", "NOT");
            wire.put("child", expression(negation.child()));
        } else {
            LogicalExpression.Group group = (LogicalExpression.Group) expression;
            wire.put("type", group.operator().name());
            List<Object> children = new ArrayList<>(group.children().size());
            for (LogicalExpression child : group.children()) {
                children.add(expression(child));
            }
            wire.put("children", children);
        }
        return wire;
    }

    private static String raw(IntentValue value) {
        if (value instanceof IntentValue.StringValue stringValue) {
            return stringValue.raw();
        }
        if (value instanceof IntentValue.IntegerValue integerValue) {
            return integerValue.raw();
        }
        if (value instanceof IntentValue.DecimalValue decimalValue) {
            return decimalValue.raw();
        }
        if (value instanceof IntentValue.DateValue dateValue) {
            return dateValue.raw();
        }
        if (value instanceof IntentValue.TimestampValue timestampValue) {
            return timestampValue.raw();
        }
        if (value instanceof IntentValue.EnumValue enumValue) {
            return enumValue.raw();
        }
        if (value instanceof IntentValue.MoneyValue moneyValue) {
            return moneyValue.raw();
        }
        throw IntentException.invalid(
                IntentErrorCodes.VALUE_INVALID, "A scalar value could not be projected onto the wire");
    }

    private static List<String> names(Enum<?>[] values) {
        List<String> names = new ArrayList<>(values.length);
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        List<String> names = new ArrayList<>(values.size());
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }
}
