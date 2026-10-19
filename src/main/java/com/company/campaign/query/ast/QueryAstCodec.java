package com.company.campaign.query.ast;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.QueryWire;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.segment.canonical.CanonicalMetricExpression;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one canonical serialization and hashing boundary for the typed Query AST.
 *
 * <p>It projects the closed AST onto the same canonical JSON shape as the T07 canonical predicate tree and
 * hashes it through the existing T01 {@link CanonicalJson} / {@link ContentHash} pair — no second hashing
 * primitive is introduced. The projection is built locally on every call, so a caller cannot mutate hash
 * material after the fact, and because the AST preserves structure exactly, two differently-grouped trees
 * always serialize to different bytes.
 *
 * <p>The codec is read-only with respect to plans: it can write and hash an AST, but it cannot parse bytes
 * back into a plan or AST.
 */
public final class QueryAstCodec {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /** Canonical UTF-8 bytes of one Query AST subtree. */
    public byte[] write(QueryAst node) {
        requirePresent(node, "node");
        return CANONICAL_JSON.write(wire(node));
    }

    /** Canonical JSON text of one Query AST subtree; the bytes are always UTF-8. */
    public String writeString(QueryAst node) {
        return new String(write(node), StandardCharsets.UTF_8);
    }

    /** Canonical content hash of one Query AST subtree. */
    public ContentHash hash(QueryAst node) {
        return ContentHash.sha256(write(node));
    }

    /** The canonical JSON projection of one Query AST subtree, for embedding in a larger plan document. */
    public static Map<String, Object> wire(QueryAst node) {
        requirePresent(node, "node");
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (node) {
            case QueryAst.Group group -> {
                wire.put("type", group.kind().name());
                List<Object> children = new ArrayList<>(group.children().size());
                group.children().forEach(child -> children.add(wire(child)));
                wire.put("children", children);
            }
            case QueryAst.Negation negation -> {
                wire.put("type", "NOT");
                wire.put("child", wire(negation.child()));
            }
            case QueryAst.Attribute attribute -> {
                wire.put("type", "ATTRIBUTE");
                wire.put("definition", QueryWire.definition(attribute.definition()));
                wire.put("operator", attribute.operator().name());
                if (attribute.operand() != null) {
                    wire.put("value", QueryWire.value(attribute.operand()));
                }
            }
            case QueryAst.Metric metric -> {
                wire.put("type", "METRIC");
                wire.put("definition", QueryWire.metricDefinition(metric.definition()));
                wire.put("window", QueryWire.window(metric.window()));
                if (!metric.scopeFilters().isEmpty()) {
                    List<Object> filters = new ArrayList<>(metric.scopeFilters().size());
                    metric.scopeFilters().forEach(filter -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("definition", QueryWire.definition(filter.definition()));
                        item.put("operator", filter.operator().name());
                        if (filter.value() != null) {
                            item.put("value", QueryWire.value(filter.value()));
                        }
                        filters.add(item);
                    });
                    wire.put("scopeFilters", filters);
                }
                wire.put("operator", metric.operator().name());
                if (metric.operand() != null) {
                    wire.put("value", QueryWire.value(metric.operand()));
                }
            }
            case QueryAst.MetricCalculation calculation -> {
                wire.put("type", "METRIC_EXPRESSION");
                wire.put("expression", metricExpression(calculation.expression()));
                wire.put("window", QueryWire.window(calculation.window()));
                wire.put("operator", calculation.operator().name());
                if (calculation.operand() != null) {
                    wire.put("value", QueryWire.value(calculation.operand()));
                }
            }
            case QueryAst.Exists exists -> {
                wire.put("type", "EXISTS");
                wire.put("relation", QueryWire.relation(exists.relation()));
                wire.put("window", QueryWire.window(exists.window()));
                wire.put("predicate", wire(exists.child()));
            }
            case QueryAst.AudienceReference reference -> {
                // Byte-for-byte the T07 canonical reference node, so an AST compiled from a canonical tree
                // serializes exactly as that tree does and the AST adds no semantic field of its own.
                wire.put("type", "AUDIENCE_REFERENCE");
                Map<String, Object> pin = new LinkedHashMap<>();
                pin.put("audienceId", reference.reference().audienceId());
                pin.put("versionId", reference.reference().versionId());
                pin.put("versionHash", reference.reference().versionHash().value());
                wire.put("reference", pin);
            }
        }
        return wire;
    }

    private static Map<String, Object> metricExpression(CanonicalMetricExpression expression) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (expression) {
            case CanonicalMetricExpression.MetricRef reference -> {
                wire.put("kind", "METRIC_REF");
                wire.put("definition", QueryWire.metricDefinition(reference.definition()));
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
        Map<String, Object> resultType = new LinkedHashMap<>();
        resultType.put("dataType", expression.resultType().dataType().name());
        resultType.put("unit", expression.resultType().unit().name());
        resultType.put("currencySemantics", expression.resultType().currencySemantics().name());
        wire.put("resultType", resultType);
        return wire;
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new QueryException(
                    QueryErrorCodes.QUERY_INPUT_MISSING, invariant,
                    "A Query AST codec boundary requires a non-null input.");
        }
    }
}
