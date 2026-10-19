package com.company.campaign.segment.dsl.codec;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.contract.SegmentDslException;
import com.company.campaign.segment.dsl.contract.SegmentDslLimits;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
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
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.BooleanValue;
import com.company.campaign.segment.dsl.value.DateValue;
import com.company.campaign.segment.dsl.value.DateTimeValue;
import com.company.campaign.segment.dsl.value.DecimalValue;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.IntegerValue;
import com.company.campaign.segment.dsl.value.ListValue;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.dsl.value.RangeValue;
import com.company.campaign.segment.dsl.value.ScalarValue;
import com.company.campaign.segment.dsl.value.StringValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.value.ValueKind;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.window.WindowType;
import com.company.campaign.segment.dsl.window.WindowUnit;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.metric.ZeroDenominatorPolicy;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.canonical.ContentHashException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Strict tree reader for the editable Segment DSL.
 *
 * <p>It performs every shape, arity, field-membership and discriminator check itself rather than delegating to
 * Jackson binding, so an unknown field, a missing required field, an alternate {@code children} shape on a
 * {@code NOT} or a raw map in place of a typed value is rejected with a stable code. Nesting depth is bounded
 * on entry, before any recursion, and failure messages never echo payload content.
 */
final class SegmentDslParser {

    private static final Set<String> ROOT_FIELDS = Set.of(
            "schemaVersion", "subject", "metadataSnapshotId", "metadataSnapshotVersion",
            "metadataSchemaVersion", "metadataContentHash", "predicate", "editableDslHash");

    private static final Set<String> GROUP_FIELDS = Set.of("nodeId", "type", "children");
    private static final Set<String> NOT_FIELDS = Set.of("nodeId", "type", "child");
    private static final Set<String> ATTRIBUTE_FIELDS = Set.of("nodeId", "type", "attributeId", "operator", "value");
    private static final Set<String> METRIC_FIELDS =
            Set.of("nodeId", "type", "metricId", "window", "scopeFilters", "operator", "value");
    private static final Set<String> METRIC_EXPRESSION_FIELDS =
            Set.of("nodeId", "type", "expression", "window", "operator", "value");
    private static final Set<String> METRIC_REF_EXPRESSION_FIELDS = Set.of("kind", "metricId");
    private static final Set<String> ADD_EXPRESSION_FIELDS = Set.of("kind", "operands");
    private static final Set<String> BINARY_EXPRESSION_FIELDS = Set.of("kind", "left", "right");
    private static final Set<String> DIVIDE_EXPRESSION_FIELDS =
            Set.of("kind", "left", "right", "zeroDenominator");
    private static final Set<String> METRIC_SCOPE_FILTER_FIELDS =
            Set.of("filterId", "attributeId", "operator", "value");
    private static final Set<String> EXISTS_FIELDS =
            Set.of("nodeId", "type", "relationId", "window", "predicate");

    /**
     * The exact five-field reference shape.
     *
     * <p>It is closed on purpose: a name, query, SQL text, window, owner, DataScope, permission, canonical
     * byte, dependency list or mutable {@code current}/{@code latest} pointer is not a member here, so such a
     * payload fails as an unknown field rather than being ignored or coerced into a wider reference.
     */
    private static final Set<String> AUDIENCE_REFERENCE_FIELDS =
            Set.of("nodeId", "type", "audienceId", "versionId", "versionHash");

    private static final Set<String> SCALAR_VALUE_FIELDS = Set.of("kind", "value");
    private static final Set<String> MONEY_VALUE_FIELDS = Set.of("kind", "amount", "currency");
    private static final Set<String> LIST_VALUE_FIELDS = Set.of("kind", "items");
    private static final Set<String> RANGE_VALUE_FIELDS =
            Set.of("kind", "lower", "upper", "includeLower", "includeUpper");

    private static final Set<String> ROLLING_WINDOW_FIELDS = Set.of("type", "amount", "unit");
    private static final Set<String> LIFETIME_WINDOW_FIELDS = Set.of("type");
    private static final Set<String> FIXED_WINDOW_FIELDS = Set.of("type", "start", "end");
    private static final Set<String> RELATIVE_ATTRIBUTE_WINDOW_FIELDS =
            Set.of("type", "anchorDefinitionId", "startOffset", "endOffset", "unit");
    private static final Set<String> SELECTED_EVENT_WINDOW_FIELDS =
            Set.of("type", "selectorId", "selectorScopeFilters");
    private static final Set<String> RELATIVE_EVENT_WINDOW_FIELDS =
            Set.of("type", "selectorId", "selectorScopeFilters", "startOffset", "endOffset", "unit",
                    "excludeAnchorEvent");

    SegmentDsl parseRoot(JsonNode root) {
        requireObject(root, "the Segment DSL document");
        requireOnlyFields(root, ROOT_FIELDS);
        SchemaVersion schemaVersion = SegmentDslSchemaVersions.requireSupported(
                text(requirePresent(root, "schemaVersion")));
        SubjectKind subject = parseSubject(requirePresent(root, "subject"));
        MetadataBinding binding = new MetadataBinding(
                text(requirePresent(root, "metadataSnapshotId")),
                text(requirePresent(root, "metadataSnapshotVersion")),
                parseMetadataSchemaVersion(requirePresent(root, "metadataSchemaVersion")),
                parseContentHash(requirePresent(root, "metadataContentHash"),
                        SegmentDslErrorCodes.METADATA_BINDING_INVALID));
        Predicate predicate = parsePredicate(requirePresent(root, "predicate"), 1);
        ContentHash editableDslHash = parseContentHash(
                requirePresent(root, "editableDslHash"), SegmentDslErrorCodes.SHAPE_INVALID);
        return new SegmentDsl(schemaVersion, subject, binding, predicate, editableDslHash);
    }

    TypedValue parseValue(JsonNode node) {
        ValueKind kind = readKind(node);
        return switch (kind) {
            case LIST -> parseListValue(node);
            case RANGE -> parseRangeValue(node);
            default -> parseScalarValue(node, kind);
        };
    }

    /**
     * The scalar-only path for LIST items and RANGE bounds.
     *
     * <p>A nested container is structurally inadmissible here, so the {@code kind} is read first and a
     * {@code LIST}/{@code RANGE} is rejected immediately. Nothing descends into the rejected subtree, so a
     * deeply nested malicious container cannot recurse before it fails.
     */
    private ScalarValue parseScalarValue(JsonNode node) {
        ValueKind kind = readKind(node);
        if (kind == ValueKind.LIST || kind == ValueKind.RANGE) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A LIST or RANGE bound must be a scalar value");
        }
        return parseScalarValue(node, kind);
    }

    private ScalarValue parseScalarValue(JsonNode node, ValueKind kind) {
        return switch (kind) {
            case STRING -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new StringValue(text(requirePresent(node, "value")));
            }
            case INTEGER -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new IntegerValue(integer(requirePresent(node, "value")));
            }
            case DECIMAL -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new DecimalValue(text(requirePresent(node, "value")));
            }
            case BOOLEAN -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new BooleanValue(booleanValue(requirePresent(node, "value")));
            }
            case DATE -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new DateValue(text(requirePresent(node, "value")));
            }
            case DATETIME -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new DateTimeValue(text(requirePresent(node, "value")));
            }
            case ENUM -> {
                requireOnlyFields(node, SCALAR_VALUE_FIELDS);
                yield new EnumValue(text(requirePresent(node, "value")));
            }
            case MONEY -> {
                requireOnlyFields(node, MONEY_VALUE_FIELDS);
                yield new MoneyValue(
                        text(requirePresent(node, "amount")), text(requirePresent(node, "currency")));
            }
            case LIST, RANGE -> throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A LIST or RANGE bound must be a scalar value");
        };
    }

    private ListValue parseListValue(JsonNode node) {
        requireOnlyFields(node, LIST_VALUE_FIELDS);
        JsonNode items = requirePresent(node, "items");
        if (!items.isArray()) {
            throw shape("A LIST value requires an items array");
        }
        List<ScalarValue> parsed = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            parsed.add(parseScalarValue(item));
        }
        return new ListValue(parsed);
    }

    private RangeValue parseRangeValue(JsonNode node) {
        requireOnlyFields(node, RANGE_VALUE_FIELDS);
        return new RangeValue(
                parseScalarValue(requirePresent(node, "lower")),
                parseScalarValue(requirePresent(node, "upper")),
                booleanValue(requirePresent(node, "includeLower")),
                booleanValue(requirePresent(node, "includeUpper")));
    }

    private static ValueKind readKind(JsonNode node) {
        if (node == null || node.isNull()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A typed value must be a JSON object");
        }
        requireObject(node, "a TypedValue");
        return ValueKind.fromWire(text(requirePresent(node, "kind")));
    }

    TimeWindow parseWindow(JsonNode node) {
        requireObject(node, "a TimeWindow");
        String typeName = text(requirePresent(node, "type"));
        WindowType type = WindowType.fromWire(typeName);
        return switch (type) {
            case ROLLING -> {
                requireOnlyFields(node, ROLLING_WINDOW_FIELDS);
                yield new RollingWindow(
                        windowAmount(requirePresent(node, "amount")),
                        WindowUnit.fromWire(text(requirePresent(node, "unit"))));
            }
            case LIFETIME -> {
                requireOnlyFields(node, LIFETIME_WINDOW_FIELDS);
                yield new LifetimeWindow();
            }
            case FIXED -> {
                requireOnlyFields(node, FIXED_WINDOW_FIELDS);
                yield new FixedWindow(
                        text(requirePresent(node, "start")), text(requirePresent(node, "end")));
            }
            case RELATIVE_TO_ATTRIBUTE -> {
                requireOnlyFields(node, RELATIVE_ATTRIBUTE_WINDOW_FIELDS);
                yield new com.company.campaign.segment.dsl.window.RelativeToAttributeWindow(
                        text(requirePresent(node, "anchorDefinitionId")),
                        windowAmount(requirePresent(node, "startOffset")),
                        windowAmount(requirePresent(node, "endOffset")),
                        WindowUnit.fromWire(text(requirePresent(node, "unit"))));
            }
            case SELECTED_EVENT -> {
                requireOnlyFields(node, SELECTED_EVENT_WINDOW_FIELDS);
                yield new com.company.campaign.segment.dsl.window.SelectedEventWindow(
                        text(requirePresent(node, "selectorId")),
                        parseMetricScopeFilters(node.get("selectorScopeFilters")));
            }
            case RELATIVE_TO_EVENT -> {
                requireOnlyFields(node, RELATIVE_EVENT_WINDOW_FIELDS);
                yield new com.company.campaign.segment.dsl.window.RelativeToEventWindow(
                        text(requirePresent(node, "selectorId")),
                        parseMetricScopeFilters(node.get("selectorScopeFilters")),
                        windowAmount(requirePresent(node, "startOffset")),
                        windowAmount(requirePresent(node, "endOffset")),
                        WindowUnit.fromWire(text(requirePresent(node, "unit"))),
                        booleanValue(requirePresent(node, "excludeAnchorEvent")));
            }
        };
    }

    Predicate parsePredicate(JsonNode node, int depth) {
        if (depth > SegmentDslLimits.MAX_PREDICATE_DEPTH) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "Predicate nesting exceeds the supported depth");
        }
        if (node == null || node.isNull()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.SHAPE_INVALID, "A predicate must be a JSON object");
        }
        requireObject(node, "a predicate");
        PredicateType type = PredicateType.fromWire(text(requirePresent(node, "type")));
        String nodeId = text(requirePresent(node, "nodeId"));
        return switch (type) {
            case ALL, ANY -> {
                requireOnlyFields(node, GROUP_FIELDS);
                JsonNode children = requirePresent(node, "children");
                if (!children.isArray()) {
                    throw shape("A group predicate requires a children array");
                }
                List<Predicate> parsed = new ArrayList<>(children.size());
                for (JsonNode child : children) {
                    parsed.add(parsePredicate(child, depth + 1));
                }
                yield new GroupPredicate(nodeId, type, parsed);
            }
            case NOT -> {
                requireOnlyFields(node, NOT_FIELDS);
                yield new NotPredicate(nodeId, parsePredicate(requirePresent(node, "child"), depth + 1));
            }
            case ATTRIBUTE -> {
                requireOnlyFields(node, ATTRIBUTE_FIELDS);
                ComparisonOperator operator = parseOperator(requirePresent(node, "operator"));
                yield new AttributePredicate(
                        nodeId,
                        text(requirePresent(node, "attributeId")),
                        operator,
                        parseCondition(node, operator));
            }
            case METRIC -> {
                requireOnlyFields(node, METRIC_FIELDS);
                ComparisonOperator operator = parseOperator(requirePresent(node, "operator"));
                yield new MetricPredicate(
                        nodeId,
                        text(requirePresent(node, "metricId")),
                        parseWindow(requirePresent(node, "window")),
                        parseMetricScopeFilters(node.get("scopeFilters")),
                        operator,
                        parseCondition(node, operator));
            }
            case METRIC_EXPRESSION -> {
                requireOnlyFields(node, METRIC_EXPRESSION_FIELDS);
                ComparisonOperator operator = parseOperator(requirePresent(node, "operator"));
                yield new MetricExpressionPredicate(
                        nodeId,
                        parseMetricExpression(requirePresent(node, "expression"), 1),
                        parseWindow(requirePresent(node, "window")),
                        operator,
                        parseCondition(node, operator));
            }
            case EXISTS -> {
                requireOnlyFields(node, EXISTS_FIELDS);
                yield new ExistsPredicate(
                        nodeId,
                        text(requirePresent(node, "relationId")),
                        parseWindow(requirePresent(node, "window")),
                        parsePredicate(requirePresent(node, "predicate"), depth + 1));
            }
            case AUDIENCE_REFERENCE -> {
                requireOnlyFields(node, AUDIENCE_REFERENCE_FIELDS);
                // The pin is read as three exact parts; a bare audience identifier is never a version pin.
                yield AudienceReferencePredicate.of(
                        nodeId,
                        text(requirePresent(node, "audienceId")),
                        text(requirePresent(node, "versionId")),
                        text(requirePresent(node, "versionHash")));
            }
        };
    }

    private MetricExpression parseMetricExpression(JsonNode node, int depth) {
        if (depth > SegmentDslLimits.MAX_METRIC_EXPRESSION_DEPTH) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED,
                    "Metric-expression nesting exceeds the supported depth");
        }
        requireObject(node, "a Metric expression");
        MetricExpression.Kind kind = MetricExpression.Kind.fromWire(text(requirePresent(node, "kind")));
        return switch (kind) {
            case METRIC_REF -> {
                requireOnlyFields(node, METRIC_REF_EXPRESSION_FIELDS);
                yield new MetricExpression.MetricRef(text(requirePresent(node, "metricId")));
            }
            case ADD -> {
                requireOnlyFields(node, ADD_EXPRESSION_FIELDS);
                JsonNode operands = requirePresent(node, "operands");
                if (!operands.isArray()) {
                    throw shape("ADD requires an operands array");
                }
                List<MetricExpression> parsed = new ArrayList<>(operands.size());
                for (JsonNode operand : operands) {
                    parsed.add(parseMetricExpression(operand, depth + 1));
                }
                yield new MetricExpression.Add(parsed);
            }
            case SUBTRACT -> {
                requireOnlyFields(node, BINARY_EXPRESSION_FIELDS);
                yield new MetricExpression.Subtract(
                        parseMetricExpression(requirePresent(node, "left"), depth + 1),
                        parseMetricExpression(requirePresent(node, "right"), depth + 1));
            }
            case MULTIPLY -> {
                requireOnlyFields(node, BINARY_EXPRESSION_FIELDS);
                yield new MetricExpression.Multiply(
                        parseMetricExpression(requirePresent(node, "left"), depth + 1),
                        parseMetricExpression(requirePresent(node, "right"), depth + 1));
            }
            case DIVIDE -> {
                requireOnlyFields(node, DIVIDE_EXPRESSION_FIELDS);
                yield new MetricExpression.Divide(
                        parseMetricExpression(requirePresent(node, "left"), depth + 1),
                        parseMetricExpression(requirePresent(node, "right"), depth + 1),
                        ZeroDenominatorPolicy.fromWire(text(requirePresent(node, "zeroDenominator"))));
            }
        };
    }

    private List<MetricScopeFilter> parseMetricScopeFilters(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        if (!node.isArray()) {
            throw shape("Metric scopeFilters must be an array");
        }
        if (node.size() > SegmentDslLimits.MAX_METRIC_SCOPE_FILTERS) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.LIMIT_EXCEEDED, "A metric has too many scope filters");
        }
        List<MetricScopeFilter> filters = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            requireObject(item, "a Metric scope filter");
            requireOnlyFields(item, METRIC_SCOPE_FILTER_FIELDS);
            ComparisonOperator operator = parseOperator(requirePresent(item, "operator"));
            filters.add(new MetricScopeFilter(
                    text(requirePresent(item, "filterId")),
                    text(requirePresent(item, "attributeId")),
                    operator,
                    parseCondition(item, operator)));
        }
        return filters;
    }

    private ConditionValue parseCondition(JsonNode node, ComparisonOperator operator) {
        JsonNode value = node.get("value");
        if (operator.isNullOperator()) {
            if (value != null) {
                throw new SegmentDslException(
                        SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID,
                        "A NULL operator must not carry a value");
            }
            return ConditionValue.none();
        }
        if (value == null) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.OPERATOR_VALUE_SHAPE_INVALID, "Operator requires a value");
        }
        return ConditionValue.of(parseValue(value));
    }

    private ComparisonOperator parseOperator(JsonNode node) {
        String name = text(node);
        for (ComparisonOperator operator : ComparisonOperator.values()) {
            if (operator.name().equals(name)) {
                return operator;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.UNKNOWN_OPERATOR, "Unknown comparison operator");
    }

    private SubjectKind parseSubject(JsonNode node) {
        String name = text(node);
        for (SubjectKind kind : SubjectKind.values()) {
            if (kind.name().equals(name)) {
                return kind;
            }
        }
        throw new SegmentDslException(
                SegmentDslErrorCodes.SUBJECT_INVALID, "Unknown root subject");
    }

    private SchemaVersion parseMetadataSchemaVersion(JsonNode node) {
        try {
            return SchemaVersion.parse(text(node));
        } catch (RuntimeException exception) {
            // The version failure carries the rejected text in its details, so it is never attached as a cause.
            throw new SegmentDslException(
                    SegmentDslErrorCodes.METADATA_BINDING_INVALID,
                    "Malformed Metadata schema version in the snapshot binding");
        }
    }

    private ContentHash parseContentHash(JsonNode node, String code) {
        try {
            return new ContentHash(text(node));
        } catch (ContentHashException exception) {
            // The hash failure carries the rejected text in its details, so it is never attached as a cause.
            throw new SegmentDslException(code, "Malformed content hash");
        }
    }

    private static long integer(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID,
                    "An INTEGER value must be a JSON integer within the signed 64-bit range");
        }
        return node.longValue();
    }

    private static int windowAmount(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TIME_WINDOW_INVALID, "A window amount must be a bounded JSON integer");
        }
        return node.intValue();
    }

    private static boolean booleanValue(JsonNode node) {
        if (!node.isBoolean()) {
            throw new SegmentDslException(
                    SegmentDslErrorCodes.TYPED_VALUE_INVALID, "A BOOLEAN value must be a JSON boolean");
        }
        return node.booleanValue();
    }

    private static JsonNode requirePresent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            throw shape("Missing required field '" + field + "'");
        }
        return value;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) {
            throw shape("Expected a JSON string");
        }
        return node.textValue();
    }

    private static void requireObject(JsonNode node, String what) {
        if (node == null || !node.isObject()) {
            throw shape("Expected a JSON object for " + what);
        }
    }

    private static void requireOnlyFields(JsonNode node, Set<String> allowed) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) {
                // The unknown name is caller-controlled and may carry PII or a secret, so it is not echoed.
                throw new SegmentDslException(
                        SegmentDslErrorCodes.UNKNOWN_FIELD, "A field that is not part of this shape is present");
            }
        }
    }

    private static SegmentDslException shape(String message) {
        return new SegmentDslException(SegmentDslErrorCodes.SHAPE_INVALID, message);
    }
}
