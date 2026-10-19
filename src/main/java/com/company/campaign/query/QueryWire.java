package com.company.campaign.query;

import com.company.campaign.metadata.domain.MetadataFilter;
import com.company.campaign.metadata.domain.MetadataFilterEntry;
import com.company.campaign.metadata.domain.StringSemantics;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.canonical.CanonicalWindow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one deterministic projection of the reused T07 canonical semantic types onto canonical JSON wire maps.
 *
 * <p>The Query AST and the Semantic Query Plan both serialize the same canonical value, definition, window
 * and relation types, so the projection lives in exactly one place instead of two that must be kept in step.
 * It builds a fresh map tree on every call and never returns or retains caller-owned collections, so hash
 * material can never be mutated after the fact.
 *
 * <p>The projection deliberately matches the accepted T07 {@code CanonicalSegmentCodec} shape byte for byte:
 * a Query AST compiled from a canonical predicate therefore serializes to the same bytes as that predicate's
 * canonical node, and the AST adds no semantic field of its own.
 */
public final class QueryWire {

    private QueryWire() {
    }

    public static Map<String, Object> definition(CanonicalDefinition definition) {
        requirePresent(definition, "definition");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", definition.kind().name());
        wire.put("id", definition.id());
        wire.put("subject", definition.subject().name());
        wire.put("dataType", definition.dataType().name());
        wire.put("nullSemantics", definition.nullSemantics().name());
        wire.put("currencySemantics", definition.currencySemantics().name());
        if (definition.stringSemantics() != null) {
            wire.put("stringSemantics", stringSemantics(definition.stringSemantics()));
        }
        return wire;
    }

    public static Map<String, Object> metricDefinition(CanonicalMetricDefinition definition) {
        requirePresent(definition, "metricDefinition");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", "METRIC");
        wire.put("id", definition.id());
        wire.put("subject", definition.subject().name());
        wire.put("resultType", definition.resultType().name());
        wire.put("nullSemantics", definition.nullSemantics().name());
        wire.put("currencySemantics", definition.currencySemantics().name());
        if (definition.stringSemantics() != null) {
            wire.put("stringSemantics", stringSemantics(definition.stringSemantics()));
        }
        wire.put("aggregation", definition.aggregation().name());
        wire.put("emptySetBehavior", definition.emptySetBehavior().name());
        if (definition.emptySetValue() != null) {
            Map<String, Object> emptySetValue = new LinkedHashMap<>();
            emptySetValue.put("dataType", definition.emptySetValue().dataType().name());
            emptySetValue.put("value", definition.emptySetValue().canonicalValue());
            wire.put("emptySetValue", emptySetValue);
        }
        if (definition.eventTimeSemantics() != null) {
            wire.put("eventTimeSemantics", definition.eventTimeSemantics().name());
        }
        wire.put("windowBoundarySemantics", definition.windowBoundarySemantics().name());
        return wire;
    }

    public static Map<String, Object> relation(CanonicalRelationReference relation) {
        requirePresent(relation, "relation");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", "RELATION");
        wire.put("id", relation.id());
        wire.put("sourceEntity", relation.sourceEntity().name());
        wire.put("targetEntity", relation.targetEntity().name());
        wire.put("cardinality", relation.cardinality().name());
        wire.put("joinKind", relation.joinKind().name());
        if (relation.sourceJoinField() != null) {
            wire.put("sourceJoinField", relation.sourceJoinField());
        }
        if (relation.targetJoinField() != null) {
            wire.put("targetJoinField", relation.targetJoinField());
        }
        wire.put("targetNullSemantics", relation.targetNullSemantics().name());
        wire.put("emptyRelationSemantics", relation.emptyRelationSemantics().name());
        if (relation.eventTimeSemantics() != null) {
            wire.put("eventTimeSemantics", relation.eventTimeSemantics().name());
        }
        if (relation.eventTimeField() != null) {
            wire.put("eventTimeField", relation.eventTimeField());
        }
        return wire;
    }

    public static Map<String, Object> window(CanonicalWindow window) {
        requirePresent(window, "window");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", window.kind().name());
        switch (window) {
            case CanonicalWindow.RollingWindow rolling -> wire.put("days", rolling.days());
            case CanonicalWindow.LifetimeWindow ignored -> {
            }
            case CanonicalWindow.FixedWindow fixed -> {
                wire.put("start", fixed.startInstant());
                wire.put("end", fixed.endInstant());
            }
            case CanonicalWindow.RelativeToAttributeWindow relative -> {
                wire.put("anchorDefinitionId", relative.anchorDefinitionId());
                wire.put("startOffsetDays", relative.startOffsetDays());
                wire.put("endOffsetDays", relative.endOffsetDays());
            }
            case CanonicalWindow.SelectedEventWindow selected -> {
                wire.put("selectorId", selected.selectorId());
                List<Object> filters = new ArrayList<>(selected.selectorScopeFilters().size());
                selected.selectorScopeFilters().forEach(filter -> filters.add(metricFilter(filter)));
                wire.put("selectorScopeFilters", filters);
            }
            case CanonicalWindow.RelativeToEventWindow relative -> {
                wire.put("selectorId", relative.selectorId());
                List<Object> filters = new ArrayList<>(relative.selectorScopeFilters().size());
                relative.selectorScopeFilters().forEach(filter -> filters.add(metricFilter(filter)));
                wire.put("selectorScopeFilters", filters);
                wire.put("startOffsetDays", relative.startOffsetDays());
                wire.put("endOffsetDays", relative.endOffsetDays());
                wire.put("excludeAnchorEvent", relative.excludeAnchorEvent());
            }
        }
        return wire;
    }

    public static Map<String, Object> eventSelector(EventSelectorDefinition selector) {
        requirePresent(selector, "eventSelector");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("id", selector.id());
        wire.put("displayName", selector.displayName());
        wire.put("aliases", selector.aliases());
        wire.put("subject", selector.subject().name());
        wire.put("selectorKind", selector.selectorKind().name());
        wire.put("unscopedByDefault", selector.unscopedByDefault());
        List<Object> bindings = new ArrayList<>(selector.bindings().size());
        selector.bindings().forEach(binding -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("relationId", binding.relationId());
            value.put("identityAttributeId", binding.identityAttributeId());
            List<Object> order = new ArrayList<>(binding.orderBy().size());
            binding.orderBy().forEach(item -> order.add(Map.of(
                    "attributeId", item.attributeId(), "direction", item.direction().name())));
            value.put("orderBy", order);
            value.put("fixedFilter", filter(binding.fixedFilter()));
            List<Object> dimensions = new ArrayList<>(binding.scopeFilterDimensions().size());
            binding.scopeFilterDimensions().forEach(dimension -> dimensions.add(Map.of(
                    "attributeId", dimension.attributeId(),
                    "allowedOperators", dimension.allowedOperators().stream().map(Enum::name).toList(),
                    "domain", dimension.domain().name(),
                    "identifierKind", dimension.identifierKind().name())));
            value.put("scopeFilterDimensions", dimensions);
            bindings.add(value);
        });
        wire.put("bindings", bindings);
        if (selector.disambiguation() != null) {
            wire.put("disambiguation", selector.disambiguation());
        }
        return wire;
    }

    public static Map<String, Object> metricFilter(CanonicalMetricFilter filter) {
        requirePresent(filter, "metricFilter");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("definition", definition(filter.definition()));
        wire.put("operator", filter.operator().name());
        if (filter.value() != null) {
            wire.put("value", value(filter.value()));
        }
        return wire;
    }

    public static Map<String, Object> value(CanonicalValue value) {
        requirePresent(value, "value");
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", value.kind().name());
        switch (value) {
            case CanonicalValue.TextValue text -> wire.put("value", text.value());
            case CanonicalValue.IntegerValue integer -> wire.put("value", integer.value());
            case CanonicalValue.DecimalValue decimal -> wire.put("value", decimal.value());
            case CanonicalValue.BooleanValue bool -> wire.put("value", bool.value());
            case CanonicalValue.DateValue date -> wire.put("value", date.value());
            case CanonicalValue.InstantValue instant -> wire.put("instant", instant.instant());
            case CanonicalValue.EnumValue enumValue -> wire.put("value", enumValue.code());
            case CanonicalValue.MoneyValue money -> {
                wire.put("amount", money.amount());
                wire.put("currency", money.currency());
            }
            case CanonicalValue.ListValue list -> {
                List<Object> items = new ArrayList<>(list.items().size());
                list.items().forEach(item -> items.add(value(item)));
                wire.put("items", items);
            }
            case CanonicalValue.RangeValue range -> {
                wire.put("lower", value(range.lower()));
                wire.put("upper", value(range.upper()));
                wire.put("includeLower", range.includeLower());
                wire.put("includeUpper", range.includeUpper());
            }
        }
        return wire;
    }

    public static Map<String, Object> filter(MetadataFilter filter) {
        requirePresent(filter, "filter");
        Map<String, Object> wire = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>(filter.entries().size());
        for (MetadataFilterEntry entry : filter.entries()) {
            Map<String, Object> entryWire = new LinkedHashMap<>();
            entryWire.put("key", entry.key());
            entryWire.put("values", List.copyOf(entry.values()));
            entries.add(entryWire);
        }
        wire.put("entries", entries);
        return wire;
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new QueryException(QueryErrorCodes.QUERY_AST_INVALID, invariant,
                    "A Query wire projection requires a non-null input.");
        }
    }

    private static Map<String, Object> stringSemantics(StringSemantics semantics) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("caseSensitivity", semantics.caseSensitivity().name());
        wire.put("normalization", semantics.normalization().name());
        wire.put("localeMode", semantics.localeMode().name());
        if (semantics.locale() != null) {
            wire.put("locale", semantics.locale().value());
        }
        return wire;
    }
}
