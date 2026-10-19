package com.company.campaign.segment.canonical;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one canonical serialization and hashing boundary for the semantic AST.
 *
 * <p>It projects the closed canonical model onto a compact JSON shape and hashes it through the existing T01
 * {@link CanonicalJson} / {@link ContentHash} pair — no second hashing primitive is introduced. Every public
 * method defensively copies nothing outward: it returns fresh bytes or an immutable hash, and builds its maps
 * locally, so a caller cannot mutate hash material after the fact.
 *
 * <p>Because the model is already normalized, the same semantic meaning always produces byte-identical
 * serialization here regardless of node IDs, child order, decimal scale, timestamp offset or Metadata-owned
 * string normalization.
 *
 * <p>The codec is immutable and thread-safe.
 */
public final class CanonicalSegmentCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Canonical UTF-8 bytes of one canonical segment. */
    public byte[] write(CanonicalSegment segment) {
        CanonicalInvariants.requirePresent(segment, "segment");
        return CANONICAL_JSON.write(root(segment));
    }

    /** Canonical JSON text of one canonical segment; the bytes are always UTF-8. */
    public String writeString(CanonicalSegment segment) {
        return new String(write(segment), StandardCharsets.UTF_8);
    }

    /** Canonical content hash of one canonical segment. */
    public ContentHash hash(CanonicalSegment segment) {
        return ContentHash.sha256(write(segment));
    }

    /** Canonical UTF-8 bytes of one canonical predicate subtree, used as the child-order sort key. */
    public byte[] writeNode(CanonicalNode node) {
        CanonicalInvariants.requirePresent(node, "node");
        return CANONICAL_JSON.write(node(node));
    }

    /** Canonical content hash of one canonical predicate subtree. */
    public ContentHash hashNode(CanonicalNode node) {
        return ContentHash.sha256(writeNode(node));
    }

    /** Canonical UTF-8 bytes of one canonical value, used as the list-item sort key. */
    public byte[] writeValue(CanonicalValue value) {
        CanonicalInvariants.requirePresent(value, "value");
        return CANONICAL_JSON.write(value(value));
    }

    /** Canonical content hash of one canonical value. */
    public ContentHash hashValue(CanonicalValue value) {
        return ContentHash.sha256(writeValue(value));
    }

    /** Canonical JSON text of one canonical value, for diagnostics and golden tests. */
    public String writeValueString(CanonicalValue value) {
        CanonicalInvariants.requirePresent(value, "value");
        return new String(CANONICAL_JSON.write(value(value)), StandardCharsets.UTF_8);
    }

    private static Map<String, Object> root(CanonicalSegment segment) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("canonicalizerVersion", segment.canonicalizerVersion());
        wire.put("schemaVersion", segment.schemaVersion().value());
        wire.put("subject", segment.subject().name());
        wire.put("metadata", metadata(segment));
        wire.put("predicate", node(segment.predicate()));
        return wire;
    }

    private static Map<String, Object> metadata(CanonicalSegment segment) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("metadataSnapshotId", segment.metadata().snapshotId());
        wire.put("metadataSnapshotVersion", segment.metadata().snapshotVersion());
        wire.put("metadataSchemaVersion", segment.metadata().metadataSchemaVersion().value());
        wire.put("metadataContentHash", segment.metadata().contentHash().value());
        return wire;
    }

    private static Map<String, Object> node(CanonicalNode node) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (node) {
            case CanonicalNode.Group group -> {
                wire.put("type", group.kind().name());
                List<Object> children = new ArrayList<>(group.children().size());
                group.children().forEach(child -> children.add(node(child)));
                wire.put("children", children);
            }
            case CanonicalNode.Negation negation -> {
                wire.put("type", "NOT");
                wire.put("child", node(negation.child()));
            }
            case CanonicalNode.Attribute attribute -> {
                wire.put("type", "ATTRIBUTE");
                wire.put("definition", definition(attribute.definition()));
                wire.put("operator", attribute.operator().name());
                if (attribute.value() != null) {
                    wire.put("value", value(attribute.value()));
                }
            }
            case CanonicalNode.Metric metric -> {
                wire.put("type", "METRIC");
                wire.put("definition", metricDefinition(metric.definition()));
                wire.put("window", window(metric.window()));
                if (!metric.scopeFilters().isEmpty()) {
                    List<Object> filters = new ArrayList<>(metric.scopeFilters().size());
                    metric.scopeFilters().forEach(filter -> filters.add(metricFilter(filter)));
                    wire.put("scopeFilters", filters);
                }
                wire.put("operator", metric.operator().name());
                if (metric.value() != null) {
                    wire.put("value", value(metric.value()));
                }
            }
            case CanonicalNode.MetricCalculation calculation -> {
                wire.put("type", "METRIC_EXPRESSION");
                wire.put("expression", metricExpression(calculation.expression()));
                wire.put("window", window(calculation.window()));
                wire.put("operator", calculation.operator().name());
                if (calculation.value() != null) {
                    wire.put("value", value(calculation.value()));
                }
            }
            case CanonicalNode.Exists exists -> {
                wire.put("type", "EXISTS");
                wire.put("relation", relation(exists.relation()));
                wire.put("window", window(exists.window()));
                wire.put("predicate", node(exists.child()));
            }
            case CanonicalNode.AudienceReference reference -> {
                wire.put("type", "AUDIENCE_REFERENCE");
                wire.put("reference", audienceVersionReference(reference.reference()));
            }
        }
        return wire;
    }

    /** The exact three-part immutable pin a canonical reference covers; no owner or scope fact is present. */
    private static Map<String, Object> audienceVersionReference(CanonicalAudienceVersionRef reference) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("audienceId", reference.audienceId());
        wire.put("versionId", reference.versionId());
        wire.put("versionHash", reference.versionHash().value());
        return wire;
    }

    private static Map<String, Object> metricExpression(CanonicalMetricExpression expression) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (expression) {
            case CanonicalMetricExpression.MetricRef reference -> {
                wire.put("kind", "METRIC_REF");
                wire.put("definition", metricDefinition(reference.definition()));
            }
            case CanonicalMetricExpression.Add add -> {
                wire.put("kind", "ADD");
                List<Object> operands = new ArrayList<>(add.operands().size());
                add.operands().forEach(operand -> operands.add(metricExpression(operand)));
                wire.put("operands", operands);
            }
            case CanonicalMetricExpression.Subtract subtract -> {
                wire.put("kind", "SUBTRACT");
                wire.put("left", metricExpression(subtract.left()));
                wire.put("right", metricExpression(subtract.right()));
            }
            case CanonicalMetricExpression.Multiply multiply -> {
                wire.put("kind", "MULTIPLY");
                wire.put("left", metricExpression(multiply.left()));
                wire.put("right", metricExpression(multiply.right()));
            }
            case CanonicalMetricExpression.Divide divide -> {
                wire.put("kind", "DIVIDE");
                wire.put("left", metricExpression(divide.left()));
                wire.put("right", metricExpression(divide.right()));
                wire.put("zeroDenominator", divide.zeroDenominator().name());
            }
        }
        wire.put("resultType", metricExpressionType(expression.resultType()));
        return wire;
    }

    private static Map<String, Object> metricExpressionType(CanonicalMetricExpressionType type) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("dataType", type.dataType().name());
        wire.put("unit", type.unit().name());
        wire.put("currencySemantics", type.currencySemantics().name());
        return wire;
    }

    private static Map<String, Object> metricFilter(CanonicalMetricFilter filter) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("definition", definition(filter.definition()));
        wire.put("operator", filter.operator().name());
        if (filter.value() != null) {
            wire.put("value", value(filter.value()));
        }
        return wire;
    }

    private static Map<String, Object> definition(CanonicalDefinition definition) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", definition.kind().name());
        wire.put("id", definition.id());
        wire.put("subject", definition.subject().name());
        wire.put("dataType", definition.dataType().name());
        wire.put("nullSemantics", definition.nullSemantics().name());
        wire.put("currencySemantics", definition.currencySemantics().name());
        if (definition.stringSemantics() != null) {
            wire.put("stringSemantics", stringSemantics(definition));
        }
        return wire;
    }

    private static Map<String, Object> metricDefinition(CanonicalMetricDefinition definition) {
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

    private static Map<String, Object> relation(CanonicalRelationReference relation) {
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

    private static Map<String, Object> stringSemantics(CanonicalDefinition definition) {
        return stringSemantics(definition.stringSemantics());
    }

    private static Map<String, Object> stringSemantics(
            com.company.campaign.metadata.domain.StringSemantics semantics) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("caseSensitivity", semantics.caseSensitivity().name());
        wire.put("normalization", semantics.normalization().name());
        wire.put("localeMode", semantics.localeMode().name());
        if (semantics.locale() != null) {
            wire.put("locale", semantics.locale().value());
        }
        return wire;
    }

    private static Map<String, Object> window(CanonicalWindow window) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", window.kind().name());
        switch (window) {
            case CanonicalWindow.RollingWindow rolling -> {
                wire.put("days", rolling.days());
            }
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

    private static Map<String, Object> value(CanonicalValue value) {
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
}
