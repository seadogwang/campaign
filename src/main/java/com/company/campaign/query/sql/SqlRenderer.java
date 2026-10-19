package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.AggregateFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The one deterministic PostgreSQL renderer for the closed SQL AST.
 *
 * <p>It walks the AST depth-first and left-to-right, emits exactly one {@code SELECT} statement and appends
 * every visited {@link SqlParameter} to an ordered list in that same walk order. Because a
 * {@code BindParameter} node always renders a single {@code ?} marker and never its value, adversarial
 * operands — quotes, backslashes, percent signs, underscores, comment markers, semicolons, SQL keywords —
 * cannot change the SQL text at all. Only validated catalog identifiers, compiler-owned aliases, keywords,
 * operators and functions enter the text.
 *
 * <p>Every binary or nested node is wrapped in parentheses, so the exact grouping of the plan is preserved
 * and no operator-precedence reasoning can regroup it. The renderer is package-private and read-only: it is
 * not a public escape hatch and it cannot parse text back into an AST.
 */
final class SqlRenderer {

    private SqlRenderer() {
    }

    /** One rendered statement plus its ordered bind parameters. */
    record Rendered(String sql, List<SqlParameter> parameters) {
    }

    /** Renders one {@code SELECT} and its complete ordered parameter list. */
    static Rendered render(SqlSelect select) {
        if (select == null) {
            throw new SqlException(SqlErrorCodes.SQL_AST_INVALID, "sqlSelect",
                    "A SQL render boundary requires a non-null SELECT.");
        }
        StringBuilder sql = new StringBuilder();
        List<SqlParameter> parameters = new ArrayList<>();
        renderSelect(select, sql, parameters);
        return new Rendered(sql.toString(), List.copyOf(parameters));
    }

    private static void renderSelect(SqlSelect select, StringBuilder sql, List<SqlParameter> parameters) {
        sql.append("SELECT ");
        renderExpression(select.projection(), sql, parameters);
        sql.append(" FROM ").append(select.from().render()).append(" AS ").append(select.alias().quoted());
        sql.append(" WHERE ");
        renderExpression(select.where(), sql, parameters);
        if (!select.orderBy().isEmpty()) {
            sql.append(" ORDER BY ");
            for (int index = 0; index < select.orderBy().size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                SqlSelect.OrderBy order = select.orderBy().get(index);
                renderExpression(order.expression(), sql, parameters);
                sql.append(' ').append(order.direction().name());
            }
        }
        if (select.limit() != null) {
            sql.append(" LIMIT ").append(select.limit());
        }
    }

    private static void renderExpression(
            SqlExpression expression, StringBuilder sql, List<SqlParameter> parameters) {
        switch (expression) {
            case SqlExpression.BindParameter parameter -> {
                sql.append('?');
                parameters.add(parameter.parameter());
            }
            case SqlExpression.Column column ->
                    sql.append(column.alias().quoted()).append('.').append(column.column().quoted());
            case SqlExpression.And and -> renderJunction(" AND ", and.operands(), sql, parameters);
            case SqlExpression.Or or -> renderJunction(" OR ", or.operands(), sql, parameters);
            case SqlExpression.Not not -> {
                if (not.operand() instanceof SqlExpression.Exists exists) {
                    sql.append("(NOT EXISTS (");
                    renderSelect(exists.subquery(), sql, parameters);
                    sql.append("))");
                } else {
                    sql.append("(NOT ");
                    renderExpression(not.operand(), sql, parameters);
                    sql.append(')');
                }
            }
            case SqlExpression.IsNull isNull -> {
                sql.append('(');
                renderExpression(isNull.operand(), sql, parameters);
                sql.append(" IS NULL)");
            }
            case SqlExpression.IsNotNull isNotNull -> {
                sql.append('(');
                renderExpression(isNotNull.operand(), sql, parameters);
                sql.append(" IS NOT NULL)");
            }
            case SqlExpression.Comparison comparison -> {
                sql.append('(');
                renderExpression(comparison.left(), sql, parameters);
                sql.append(' ').append(comparison.operator().symbol()).append(' ');
                renderExpression(comparison.right(), sql, parameters);
                sql.append(')');
            }
            case SqlExpression.InList inList -> {
                sql.append('(');
                renderExpression(inList.value(), sql, parameters);
                sql.append(inList.negated() ? " NOT IN (" : " IN (");
                renderList(inList.options(), sql, parameters);
                sql.append("))");
            }
            case SqlExpression.Between between -> renderBetween(between, sql, parameters);
            case SqlExpression.Like like -> {
                sql.append('(');
                renderExpression(like.value(), sql, parameters);
                sql.append(" LIKE ");
                // The pattern is an ordinary bind-parameter node, so it is recorded by the same walk that
                // records every other parameter and can never bypass the compiler's bounded counter.
                renderExpression(like.pattern(), sql, parameters);
                sql.append(" ESCAPE '").append(like.escape()).append("')");
            }
            case SqlExpression.Coalesce coalesce -> {
                sql.append("COALESCE(");
                renderList(coalesce.operands(), sql, parameters);
                sql.append(')');
            }
            case SqlExpression.Case caseExpression -> renderCase(caseExpression, sql, parameters);
            case SqlExpression.Multiply multiply -> {
                sql.append('(');
                renderExpression(multiply.left(), sql, parameters);
                sql.append(" * ");
                renderExpression(multiply.right(), sql, parameters);
                sql.append(')');
            }
            case SqlExpression.Add add -> renderArithmeticList(" + ", add.operands(), sql, parameters);
            case SqlExpression.Subtract subtract -> {
                sql.append('(');
                renderExpression(subtract.left(), sql, parameters);
                sql.append(" - ");
                renderExpression(subtract.right(), sql, parameters);
                sql.append(')');
            }
            case SqlExpression.Divide divide -> {
                sql.append('(');
                renderExpression(divide.left(), sql, parameters);
                sql.append(" / NULLIF(");
                renderExpression(divide.right(), sql, parameters);
                sql.append(", ");
                renderExpression(divide.zero(), sql, parameters);
                sql.append("))");
            }
            case SqlExpression.CastDecimal cast -> {
                sql.append("CAST(");
                renderExpression(cast.operand(), sql, parameters);
                sql.append(" AS NUMERIC)");
            }
            case SqlExpression.AddDays addDays -> {
                sql.append('(');
                renderExpression(addDays.temporal(), sql, parameters);
                sql.append(" + (");
                renderExpression(addDays.days(), sql, parameters);
                sql.append(" * INTERVAL '1 day'))");
            }
            case SqlExpression.Aggregate aggregate -> renderAggregate(aggregate, sql, parameters);
            case SqlExpression.Exists exists -> {
                sql.append("EXISTS (");
                renderSelect(exists.subquery(), sql, parameters);
                sql.append(')');
            }
            case SqlExpression.ScalarSubquery subquery -> {
                sql.append('(');
                renderSelect(subquery.subquery(), sql, parameters);
                sql.append(')');
            }
        }
    }

    private static void renderBetween(
            SqlExpression.Between between, StringBuilder sql, List<SqlParameter> parameters) {
        sql.append('(');
        renderExpression(between.value(), sql, parameters);
        if (between.includeLower() && between.includeUpper()) {
            sql.append(" BETWEEN ");
            renderExpression(between.lower(), sql, parameters);
            sql.append(" AND ");
            renderExpression(between.upper(), sql, parameters);
        } else {
            sql.append(between.includeLower() ? " >= " : " > ");
            renderExpression(between.lower(), sql, parameters);
            sql.append(" AND ");
            renderExpression(between.value(), sql, parameters);
            sql.append(between.includeUpper() ? " <= " : " < ");
            renderExpression(between.upper(), sql, parameters);
        }
        sql.append(')');
    }

    private static void renderCase(
            SqlExpression.Case caseExpression, StringBuilder sql, List<SqlParameter> parameters) {
        sql.append("(CASE ");
        renderExpression(caseExpression.operand(), sql, parameters);
        List<SqlExpression> whens = caseExpression.whens();
        List<SqlExpression> thens = caseExpression.thens();
        for (int index = 0; index < whens.size(); index++) {
            sql.append(" WHEN ");
            renderExpression(whens.get(index), sql, parameters);
            sql.append(" THEN ");
            renderExpression(thens.get(index), sql, parameters);
        }
        if (caseExpression.fallback() != null) {
            sql.append(" ELSE ");
            renderExpression(caseExpression.fallback(), sql, parameters);
        }
        sql.append(" END)");
    }

    private static void renderAggregate(
            SqlExpression.Aggregate aggregate, StringBuilder sql, List<SqlParameter> parameters) {
        if (aggregate.function() == AggregateFunction.COUNT && !aggregate.distinct()) {
            sql.append("COUNT(*)");
            return;
        }
        sql.append(aggregate.function() == AggregateFunction.COUNT_DISTINCT
                ? "COUNT" : aggregate.function().name());
        sql.append('(');
        if (aggregate.distinct()) {
            sql.append("DISTINCT ");
        }
        renderExpression(aggregate.argument(), sql, parameters);
        sql.append(')');
    }

    private static void renderJunction(
            String separator, List<SqlExpression> operands, StringBuilder sql, List<SqlParameter> parameters) {
        sql.append('(');
        for (int index = 0; index < operands.size(); index++) {
            if (index > 0) {
                sql.append(separator);
            }
            renderExpression(operands.get(index), sql, parameters);
        }
        sql.append(')');
    }

    private static void renderArithmeticList(
            String separator,
            List<SqlExpression> operands,
            StringBuilder sql,
            List<SqlParameter> parameters) {
        sql.append('(');
        for (int index = 0; index < operands.size(); index++) {
            if (index > 0) {
                sql.append(separator);
            }
            renderExpression(operands.get(index), sql, parameters);
        }
        sql.append(')');
    }

    private static void renderList(List<SqlExpression> operands, StringBuilder sql, List<SqlParameter> parameters) {
        for (int index = 0; index < operands.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            renderExpression(operands.get(index), sql, parameters);
        }
    }
}
