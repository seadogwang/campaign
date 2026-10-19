package com.company.campaign.query.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one deterministic canonical projection of the closed SQL AST onto hash material.
 *
 * <p>It is written the same way the accepted T07/T08 wire projections are: a fresh map tree is built on every
 * call, never a caller-owned collection, so hash material can never be mutated after the fact. Every node kind
 * and every component of every node is projected exactly once, so two ASTs that differ anywhere — in a
 * parameter type or value, an operator, an identifier, a grouping or a nesting — always project to different
 * bytes, while an AST always projects to the same bytes.
 *
 * <p>It builds maps explicitly rather than relying on reflective record serialization, because the AST is a
 * closed sealed hierarchy whose node kinds must remain visible in the canonical material and must never
 * depend on a serialization library's runtime type resolution.
 */
final class SqlWire {

    private SqlWire() {
    }

    /** The canonical projection of one {@code SELECT}. */
    static Map<String, Object> select(SqlSelect select) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("node", "SELECT");
        wire.put("projection", expression(select.projection()));
        wire.put("schema", select.from().schema().value());
        wire.put("table", select.from().table().value());
        wire.put("alias", select.alias().value());
        wire.put("where", expression(select.where()));
        List<Object> orderBy = new ArrayList<>(select.orderBy().size());
        select.orderBy().forEach(order -> orderBy.add(Map.of(
                "expression", expression(order.expression()),
                "direction", order.direction().name())));
        wire.put("orderBy", orderBy);
        wire.put("limit", select.limit());
        return wire;
    }

    /** The canonical projection of one expression node. */
    static Map<String, Object> expression(SqlExpression expression) {
        Map<String, Object> wire = new LinkedHashMap<>();
        switch (expression) {
            case SqlExpression.BindParameter parameter -> {
                wire.put("node", "BIND_PARAMETER");
                wire.put("parameter", parameter(parameter.parameter()));
            }
            case SqlExpression.Column column -> {
                wire.put("node", "COLUMN");
                wire.put("alias", column.alias().value());
                wire.put("column", column.column().value());
            }
            case SqlExpression.And and -> junction(wire, "AND", and.operands());
            case SqlExpression.Or or -> junction(wire, "OR", or.operands());
            case SqlExpression.Not not -> unary(wire, "NOT", not.operand());
            case SqlExpression.IsNull isNull -> unary(wire, "IS_NULL", isNull.operand());
            case SqlExpression.IsNotNull isNotNull -> unary(wire, "IS_NOT_NULL", isNotNull.operand());
            case SqlExpression.Comparison comparison -> {
                wire.put("node", "COMPARISON");
                wire.put("operator", comparison.operator().name());
                wire.put("left", expression(comparison.left()));
                wire.put("right", expression(comparison.right()));
            }
            case SqlExpression.InList inList -> {
                wire.put("node", "IN_LIST");
                wire.put("negated", inList.negated());
                wire.put("value", expression(inList.value()));
                wire.put("options", expressions(inList.options()));
            }
            case SqlExpression.Between between -> {
                wire.put("node", "BETWEEN");
                wire.put("value", expression(between.value()));
                wire.put("lower", expression(between.lower()));
                wire.put("upper", expression(between.upper()));
                wire.put("includeLower", between.includeLower());
                wire.put("includeUpper", between.includeUpper());
            }
            case SqlExpression.Like like -> {
                wire.put("node", "LIKE");
                wire.put("value", expression(like.value()));
                wire.put("pattern", expression(like.pattern()));
                wire.put("escape", String.valueOf(like.escape()));
            }
            case SqlExpression.Coalesce coalesce -> {
                wire.put("node", "COALESCE");
                wire.put("operands", expressions(coalesce.operands()));
            }
            case SqlExpression.Case caseExpression -> {
                wire.put("node", "CASE");
                wire.put("operand", expression(caseExpression.operand()));
                wire.put("whens", expressions(caseExpression.whens()));
                wire.put("thens", expressions(caseExpression.thens()));
                wire.put("fallback", caseExpression.fallback() == null
                        ? null : expression(caseExpression.fallback()));
            }
            case SqlExpression.Multiply multiply -> binary(wire, "MULTIPLY", multiply.left(), multiply.right());
            case SqlExpression.Add add -> junction(wire, "ADD", add.operands());
            case SqlExpression.Subtract subtract -> binary(wire, "SUBTRACT", subtract.left(), subtract.right());
            case SqlExpression.Divide divide -> {
                wire.put("node", "DIVIDE");
                wire.put("left", expression(divide.left()));
                wire.put("right", expression(divide.right()));
                wire.put("zero", expression(divide.zero()));
            }
            case SqlExpression.CastDecimal cast -> unary(wire, "CAST_DECIMAL", cast.operand());
            case SqlExpression.AddDays addDays -> {
                wire.put("node", "ADD_DAYS");
                wire.put("temporal", expression(addDays.temporal()));
                wire.put("days", expression(addDays.days()));
            }
            case SqlExpression.Aggregate aggregate -> {
                wire.put("node", "AGGREGATE");
                wire.put("function", aggregate.function().name());
                wire.put("distinct", aggregate.distinct());
                wire.put("argument", aggregate.argument() == null ? null : expression(aggregate.argument()));
            }
            case SqlExpression.Exists exists -> {
                wire.put("node", "EXISTS");
                wire.put("select", select(exists.subquery()));
            }
            case SqlExpression.ScalarSubquery subquery -> {
                wire.put("node", "SCALAR_SUBQUERY");
                wire.put("select", select(subquery.subquery()));
            }
        }
        return wire;
    }

    private static void junction(Map<String, Object> wire, String node, List<SqlExpression> operands) {
        wire.put("node", node);
        wire.put("operands", expressions(operands));
    }

    private static void unary(Map<String, Object> wire, String node, SqlExpression operand) {
        wire.put("node", node);
        wire.put("operand", expression(operand));
    }

    private static void binary(
            Map<String, Object> wire, String node, SqlExpression left, SqlExpression right) {
        wire.put("node", node);
        wire.put("left", expression(left));
        wire.put("right", expression(right));
    }

    private static List<Object> expressions(List<SqlExpression> expressions) {
        List<Object> wire = new ArrayList<>(expressions.size());
        for (SqlExpression expression : expressions) {
            wire.add(expression(expression));
        }
        return wire;
    }

    private static Map<String, Object> parameter(SqlParameter parameter) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("type", parameter.type().name());
        wire.put("value", parameter.canonicalValue());
        return wire;
    }
}
