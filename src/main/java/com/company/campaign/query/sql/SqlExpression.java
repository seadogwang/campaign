package com.company.campaign.query.sql;

import com.company.campaign.metadata.domain.AggregateFunction;

import java.util.List;

/**
 * The closed, immutable SQL expression AST.
 *
 * <p>It is sealed and permits exactly the compiler-owned SELECT constructs this task needs. Every leaf is
 * either a validated {@link SqlIdentifier} reference, a typed {@link SqlParameter} marker or a nested
 * {@link SqlSelect}; there is no node that carries SQL text, no raw-fragment constructor, no visitor callback
 * and no unsealed escape hatch. A caller may build a node, but it can only ever build one of these shapes, so
 * no caller — DSL, model, request or catalog — can introduce a table, column, function, operator or fragment
 * the compiler did not choose.
 *
 * <p>Grouping is preserved structurally: {@link And} and {@link Or} keep their exact nested children in order
 * and are never flattened, {@link Not} keeps exactly one child and is never pushed down, and the dedicated
 * NULL, list, range and string nodes keep those forms distinct from a scalar comparison.
 */
public sealed interface SqlExpression permits
        SqlExpression.BindParameter,
        SqlExpression.Column,
        SqlExpression.And,
        SqlExpression.Or,
        SqlExpression.Not,
        SqlExpression.IsNull,
        SqlExpression.IsNotNull,
        SqlExpression.Comparison,
        SqlExpression.InList,
        SqlExpression.Between,
        SqlExpression.Like,
        SqlExpression.Coalesce,
        SqlExpression.Case,
        SqlExpression.Multiply,
        SqlExpression.Add,
        SqlExpression.Subtract,
        SqlExpression.Divide,
        SqlExpression.CastDecimal,
        SqlExpression.AddDays,
        SqlExpression.Aggregate,
        SqlExpression.Exists,
        SqlExpression.ScalarSubquery {

    /** One typed parameter marker; the renderer emits {@code ?} and never the value. */
    record BindParameter(SqlParameter parameter) implements SqlExpression {
        public BindParameter {
            if (parameter == null) {
                throw invalid("bindParameter");
            }
        }
    }

    /** A qualified reference to one validated catalog column under one compiler-owned alias. */
    record Column(SqlIdentifier alias, SqlIdentifier column) implements SqlExpression {
        public Column {
            if (alias == null || column == null) {
                throw invalid("columnReference");
            }
        }
    }

    /** An {@code AND} of one or more operands, kept in exact order and never flattened. */
    record And(List<SqlExpression> operands) implements SqlExpression {
        public And {
            operands = requireOperands(operands, "andOperands");
        }
    }

    /** An {@code OR} of one or more operands, kept in exact order and never flattened. */
    record Or(List<SqlExpression> operands) implements SqlExpression {
        public Or {
            operands = requireOperands(operands, "orOperands");
        }
    }

    /** A unary {@code NOT} with exactly one child; never rewritten or pushed down. */
    record Not(SqlExpression operand) implements SqlExpression {
        public Not {
            requirePresent(operand, "notOperand");
        }
    }

    /** An explicit {@code IS NULL}; never folded into a comparison. */
    record IsNull(SqlExpression operand) implements SqlExpression {
        public IsNull {
            requirePresent(operand, "isNullOperand");
        }
    }

    /** An explicit {@code IS NOT NULL}; never folded into a comparison. */
    record IsNotNull(SqlExpression operand) implements SqlExpression {
        public IsNotNull {
            requirePresent(operand, "isNotNullOperand");
        }
    }

    /** One compiler-owned scalar comparison; {@code NOT_EQUAL} stays {@code <>}. */
    record Comparison(SqlExpression left, SqlComparisonOperator operator, SqlExpression right)
            implements SqlExpression {
        public Comparison {
            requirePresent(left, "comparisonLeft");
            requirePresent(right, "comparisonRight");
            if (operator == null) {
                throw invalid("comparisonOperator");
            }
        }
    }

    /** A value {@code IN}/{@code NOT IN} a bounded option list; both stay NULL-excluding. */
    record InList(SqlExpression value, List<SqlExpression> options, boolean negated) implements SqlExpression {
        public InList {
            requirePresent(value, "inListValue");
            options = requireOperands(options, "inListOptions");
        }
    }

    /** A value range; the renderer emits {@code BETWEEN} only for the inclusive-both form. */
    record Between(
            SqlExpression value,
            SqlExpression lower,
            SqlExpression upper,
            boolean includeLower,
            boolean includeUpper) implements SqlExpression {
        public Between {
            requirePresent(value, "betweenValue");
            requirePresent(lower, "betweenLower");
            requirePresent(upper, "betweenUpper");
        }
    }

    /**
     * A wildcard operation whose pattern is always a bind-parameter expression and whose escape is
     * compiler-owned.
     *
     * <p>The pattern is carried as an ordinary {@link BindParameter} node rather than as a bare
     * {@link SqlParameter}, so the renderer visits it through exactly the same counted compiler path as every
     * other value and no bind parameter can be created outside the bounded parameter counter.
     */
    record Like(SqlExpression value, SqlExpression pattern, char escape) implements SqlExpression {
        public Like {
            requirePresent(value, "likeValue");
            if (!(pattern instanceof BindParameter)) {
                throw invalid("likePattern");
            }
            if (escape == '%' || escape == '_' || Character.isLetterOrDigit(escape) || Character.isWhitespace(escape)) {
                throw invalid("likeEscape");
            }
        }
    }

    /** {@code COALESCE} over one or more operands, for declared empty-set ZERO semantics. */
    record Coalesce(List<SqlExpression> operands) implements SqlExpression {
        public Coalesce {
            operands = requireOperands(operands, "coalesceOperands");
        }
    }

    /**
     * A compiler-owned searched {@code CASE} over one operand with parallel, index-aligned {@code WHEN}/{@code
     * THEN} arms and an optional {@code ELSE}.
     *
     * <p>It exists only so an exact currency conversion can select one pinned rate per source currency without
     * ever reading an unpinned database value: every arm operand is a bind parameter or a catalog column and no
     * rate, currency or snapshot value enters SQL text. A null fallback means no {@code ELSE} arm, so an
     * uncovered operand yields SQL {@code NULL} and fails the comparison rather than defaulting to a rate.
     */
    record Case(
            SqlExpression operand,
            List<SqlExpression> whens,
            List<SqlExpression> thens,
            SqlExpression fallback) implements SqlExpression {
        public Case {
            requirePresent(operand, "caseOperand");
            whens = requireOperands(whens, "caseWhens");
            thens = requireOperands(thens, "caseThens");
            if (whens.size() != thens.size()) {
                throw invalid("caseArms");
            }
        }
    }

    /** An exact decimal multiplication, used only for currency conversion. */
    record Multiply(SqlExpression left, SqlExpression right) implements SqlExpression {
        public Multiply {
            requirePresent(left, "multiplyLeft");
            requirePresent(right, "multiplyRight");
        }
    }

    /** Exact arithmetic addition over two or more typed operands. */
    record Add(List<SqlExpression> operands) implements SqlExpression {
        public Add {
            operands = requireOperands(operands, "addOperands");
            if (operands.size() < 2) {
                throw invalid("addOperands");
            }
        }
    }

    /** An exact decimal subtraction, used only for refund treatment. */
    record Subtract(SqlExpression left, SqlExpression right) implements SqlExpression {
        public Subtract {
            requirePresent(left, "subtractLeft");
            requirePresent(right, "subtractRight");
        }
    }

    /** Decimal division whose denominator is excluded when equal to a typed zero bind parameter. */
    record Divide(SqlExpression left, SqlExpression right, BindParameter zero) implements SqlExpression {
        public Divide {
            requirePresent(left, "divideLeft");
            requirePresent(right, "divideRight");
            requirePresent(zero, "divideZero");
            if (zero.parameter().type() != SqlParameterType.DECIMAL) {
                throw invalid("divideZero");
            }
        }
    }

    /** Compiler-owned conversion to PostgreSQL NUMERIC, used to prevent integer division. */
    record CastDecimal(SqlExpression operand) implements SqlExpression {
        public CastDecimal {
            requirePresent(operand, "castDecimalOperand");
        }
    }

    /** Adds a parameterized whole-day offset to a temporal expression. */
    record AddDays(SqlExpression temporal, SqlExpression days) implements SqlExpression {
        public AddDays {
            requirePresent(temporal, "addDaysTemporal");
            requirePresent(days, "addDaysOffset");
            if (!(days instanceof BindParameter parameter)
                    || parameter.parameter().type() != SqlParameterType.INTEGER) {
                throw invalid("addDaysOffset");
            }
        }
    }

    /** One exact aggregate projection over a correlated subquery. */
    record Aggregate(AggregateFunction function, boolean distinct, SqlExpression argument)
            implements SqlExpression {
        public Aggregate {
            if (function == null) {
                throw invalid("aggregateFunction");
            }
            switch (function) {
                case COUNT -> {
                    if (distinct || argument != null) {
                        throw invalid("aggregateShape");
                    }
                }
                case COUNT_DISTINCT -> {
                    if (!distinct || argument == null) {
                        throw invalid("aggregateShape");
                    }
                }
                default -> {
                    if (distinct || argument == null) {
                        throw invalid("aggregateShape");
                    }
                }
            }
        }
    }

    /** A correlated {@code EXISTS} whose subquery owns its child predicates under one relation scope. */
    record Exists(SqlSelect subquery) implements SqlExpression {
        public Exists {
            requirePresent(subquery, "existsSubquery");
        }
    }

    /** A correlated scalar subquery, always wrapped in parentheses by the renderer. */
    record ScalarSubquery(SqlSelect subquery) implements SqlExpression {
        public ScalarSubquery {
            requirePresent(subquery, "scalarSubquery");
        }
    }

    private static List<SqlExpression> requireOperands(List<SqlExpression> operands, String invariant) {
        if (operands == null || operands.isEmpty()) {
            throw invalid(invariant);
        }
        for (SqlExpression operand : operands) {
            if (operand == null) {
                throw invalid(invariant);
            }
        }
        return List.copyOf(operands);
    }

    private static void requirePresent(SqlExpression expression, String invariant) {
        if (expression == null) {
            throw invalid(invariant);
        }
    }

    private static void requirePresent(SqlSelect subquery, String invariant) {
        if (subquery == null) {
            throw invalid(invariant);
        }
    }

    private static SqlException invalid(String invariant) {
        return new SqlException(SqlErrorCodes.SQL_AST_INVALID, invariant,
                "A closed SQL AST node requires a well-formed, non-null input.");
    }
}
