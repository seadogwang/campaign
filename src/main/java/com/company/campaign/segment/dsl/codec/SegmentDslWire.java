package com.company.campaign.segment.dsl.codec;

import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslHashMaterial;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.TypedValueCondition;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.metric.MetricExpression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one projection of the Segment DSL domain model onto its JSON wire shape.
 *
 * <p>Both deterministic serialization and editable hashing go through this class, so the document's canonical
 * bytes and its editable hash can never disagree about a field, an order or a discriminator. Nothing here
 * consults Jackson polymorphism, default typing, the JVM default locale or the default timezone.
 *
 * <p>The maps it builds are an internal serialization projection, not a domain representation: the parser
 * never accepts a raw map or an open {@code value:any} in its place.
 */
final class SegmentDslWire {

    private SegmentDslWire() {
    }

    /** The complete editable document, including its declared hash. */
    static Map<String, Object> root(SegmentDsl document) {
        Map<String, Object> wire = new LinkedHashMap<>(hashMaterial(document.hashMaterial()));
        wire.put("editableDslHash", document.editableDslHash().value());
        return wire;
    }

    /** The exact material the editable hash covers: everything except {@code editableDslHash}. */
    static Map<String, Object> hashMaterial(SegmentDslHashMaterial material) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("schemaVersion", material.schemaVersion().value());
        wire.put("subject", material.subject().name());
        wire.putAll(metadataBinding(material.metadata()));
        wire.put("predicate", predicate(material.predicate()));
        return wire;
    }

    /** The flattened Metadata binding fields as they appear in the root document. */
    static Map<String, Object> metadataBinding(MetadataBinding binding) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("metadataSnapshotId", binding.snapshotId());
        wire.put("metadataSnapshotVersion", binding.snapshotVersion());
        wire.put("metadataSchemaVersion", binding.metadataSchemaVersion().value());
        wire.put("metadataContentHash", binding.contentHash().value());
        return wire;
    }

    static Map<String, Object> predicate(Predicate node) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("nodeId", node.nodeId());
        wire.put("type", node.type().name());
        switch (node) {
            case GroupPredicate group -> {
                List<Object> children = new ArrayList<>(group.children().size());
                group.children().forEach(child -> children.add(predicate(child)));
                wire.put("children", children);
            }
            case NotPredicate not -> wire.put("child", predicate(not.child()));
            case AttributePredicate attribute -> {
                wire.put("attributeId", attribute.attributeId());
                wire.put("operator", attribute.operator().name());
                condition(wire, attribute.condition());
            }
            case MetricPredicate metric -> {
                wire.put("metricId", metric.metricId());
                wire.put("window", window(metric.window()));
                if (!metric.scopeFilters().isEmpty()) {
                    List<Object> filters = new ArrayList<>(metric.scopeFilters().size());
                    metric.scopeFilters().forEach(filter -> filters.add(scopeFilter(filter)));
                    wire.put("scopeFilters", filters);
                }
                wire.put("operator", metric.operator().name());
                condition(wire, metric.condition());
            }
            case MetricExpressionPredicate metricExpression -> {
                wire.put("expression", metricExpression(metricExpression.expression()));
                wire.put("window", window(metricExpression.window()));
                wire.put("operator", metricExpression.operator().name());
                condition(wire, metricExpression.condition());
            }
            case ExistsPredicate exists -> {
                wire.put("relationId", exists.relationId());
                wire.put("window", window(exists.window()));
                wire.put("predicate", predicate(exists.predicate()));
            }
            case AudienceReferencePredicate reference -> {
                wire.put("audienceId", reference.audienceId());
                wire.put("versionId", reference.versionId());
                wire.put("versionHash", reference.versionHash().value());
            }
        }
        return wire;
    }

    private static Map<String, Object> metricExpression(MetricExpression expression) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", expression.kind().name());
        switch (expression) {
            case MetricExpression.MetricRef reference -> wire.put("metricId", reference.metricId());
            case MetricExpression.Add add -> {
                List<Object> operands = new ArrayList<>(add.operands().size());
                add.operands().forEach(operand -> operands.add(metricExpression(operand)));
                wire.put("operands", operands);
            }
            case MetricExpression.Subtract subtract -> {
                wire.put("left", metricExpression(subtract.left()));
                wire.put("right", metricExpression(subtract.right()));
            }
            case MetricExpression.Multiply multiply -> {
                wire.put("left", metricExpression(multiply.left()));
                wire.put("right", metricExpression(multiply.right()));
            }
            case MetricExpression.Divide divide -> {
                wire.put("left", metricExpression(divide.left()));
                wire.put("right", metricExpression(divide.right()));
                wire.put("zeroDenominator", divide.zeroDenominator().name());
            }
        }
        return wire;
    }

    private static Map<String, Object> scopeFilter(MetricScopeFilter filter) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("filterId", filter.filterId());
        wire.put("attributeId", filter.attributeId());
        wire.put("operator", filter.operator().name());
        condition(wire, filter.condition());
        return wire;
    }

    static Map<String, Object> value(TypedValue typedValue) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("kind", typedValue.kind().name());
        switch (typedValue) {
            case StringValue value -> wire.put("value", value.value());
            case IntegerValue value -> wire.put("value", value.value());
            case DecimalValue value -> wire.put("value", value.value());
            case BooleanValue value -> wire.put("value", value.value());
            case DateValue value -> wire.put("value", value.value());
            case DateTimeValue value -> wire.put("value", value.value());
            case EnumValue value -> wire.put("value", value.value());
            case MoneyValue money -> {
                wire.put("amount", money.amount());
                wire.put("currency", money.currency());
            }
            case ListValue list -> {
                List<Object> items = new ArrayList<>(list.items().size());
                list.items().forEach(item -> items.add(value(item)));
                wire.put("items", items);
            }
            case RangeValue range -> {
                wire.put("lower", value(range.lower()));
                wire.put("upper", value(range.upper()));
                wire.put("includeLower", range.includeLower());
                wire.put("includeUpper", range.includeUpper());
            }
        }
        return wire;
    }

    static Map<String, Object> window(TimeWindow timeWindow) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", timeWindow.type().name());
        switch (timeWindow) {
            case RollingWindow rolling -> {
                wire.put("amount", rolling.amount());
                wire.put("unit", rolling.unit().name());
            }
            case LifetimeWindow ignored -> {
            }
            case FixedWindow fixed -> {
                wire.put("start", fixed.start());
                wire.put("end", fixed.end());
            }
            case com.company.campaign.segment.dsl.window.RelativeToAttributeWindow relative -> {
                wire.put("anchorDefinitionId", relative.anchorDefinitionId());
                wire.put("startOffset", relative.startOffset());
                wire.put("endOffset", relative.endOffset());
                wire.put("unit", relative.unit().name());
            }
            case com.company.campaign.segment.dsl.window.SelectedEventWindow selected -> {
                wire.put("selectorId", selected.selectorId());
                List<Object> filters = new ArrayList<>(selected.selectorScopeFilters().size());
                selected.selectorScopeFilters().forEach(filter -> filters.add(scopeFilter(filter)));
                wire.put("selectorScopeFilters", filters);
            }
            case com.company.campaign.segment.dsl.window.RelativeToEventWindow relative -> {
                wire.put("selectorId", relative.selectorId());
                List<Object> filters = new ArrayList<>(relative.selectorScopeFilters().size());
                relative.selectorScopeFilters().forEach(filter -> filters.add(scopeFilter(filter)));
                wire.put("selectorScopeFilters", filters);
                wire.put("startOffset", relative.startOffset());
                wire.put("endOffset", relative.endOffset());
                wire.put("unit", relative.unit().name());
                wire.put("excludeAnchorEvent", relative.excludeAnchorEvent());
            }
        }
        return wire;
    }

    private static void condition(Map<String, Object> wire, ConditionValue condition) {
        if (condition instanceof TypedValueCondition present) {
            wire.put("value", value(present.value()));
        }
    }
}
