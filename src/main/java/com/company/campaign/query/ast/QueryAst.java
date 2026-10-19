package com.company.campaign.query.ast;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;
import com.company.campaign.segment.canonical.CanonicalMetricExpression;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.util.List;

/**
 * Closed, immutable typed Query AST compiled one-for-one from an accepted T07 canonical predicate tree.
 *
 * <p>It preserves the exact structure of the canonical tree and rewrites nothing: an {@code ALL}/{@code ANY}
 * group keeps its nested children in canonical order, a unary {@code NOT} keeps exactly one child, an
 * {@code EXISTS} keeps its child predicate under that one relation boundary, and {@code NOT_EQUAL} is never
 * turned into {@code NOT(EQUAL)} nor is a NULL operand replaced by a default.
 *
 * <p>It reuses the immutable T07 canonical value, definition, metric-definition, relation-reference and
 * window types rather than declaring a second synonymous model, so a query node can only ever carry an
 * already-normalized operand and an already-validated definition. It carries no editable {@code nodeId} and
 * never accepts a T05 DSL type.
 *
 * <p>A metric or relation window stays the canonical window <em>identity</em> here; resolving its concrete
 * boundaries from {@code asOf} and the timezone is the planner's job, not the AST's.
 */
public sealed interface QueryAst
        permits QueryAst.Group, QueryAst.Negation, QueryAst.Attribute, QueryAst.Metric,
        QueryAst.MetricCalculation, QueryAst.Exists, QueryAst.AudienceReference {

    /** The commutative group kinds, kept exactly as the canonical tree declared them. */
    enum GroupKind {
        ALL,
        ANY
    }

    /** An {@code ALL}/{@code ANY} group whose child order and nesting are preserved exactly. */
    record Group(GroupKind kind, List<QueryAst> children) implements QueryAst {
        public Group {
            requirePresent(kind, "groupKind");
            if (children == null || children.isEmpty()) {
                throw invalid("groupChildren", "A query group requires at least one child.");
            }
            for (QueryAst child : children) {
                requirePresent(child, "groupChildren");
            }
            children = List.copyOf(children);
        }
    }

    /** A unary {@code NOT} with exactly one child; the child is never rewritten. */
    record Negation(QueryAst child) implements QueryAst {
        public Negation {
            requirePresent(child, "negationChild");
        }
    }

    /** A typed attribute comparison; the operand is null exactly for {@code IS_NULL}/{@code IS_NOT_NULL}. */
    record Attribute(CanonicalDefinition definition, ComparisonOperator operator, CanonicalValue operand)
            implements QueryAst {
        public Attribute {
            requirePresent(definition, "attributeDefinition");
            requirePresent(operator, "operator");
            requireOperatorOperand(operator, operand);
        }
    }

    /** A typed metric aggregate comparison over its canonical window identity. */
    record Metric(
            CanonicalMetricDefinition definition,
            CanonicalWindow window,
            List<CanonicalMetricFilter> scopeFilters,
            ComparisonOperator operator,
            CanonicalValue operand) implements QueryAst {
        public Metric {
            requirePresent(definition, "metricDefinition");
            requirePresent(window, "metricWindow");
            scopeFilters = scopeFilters == null ? List.of() : List.copyOf(scopeFilters);
            for (CanonicalMetricFilter filter : scopeFilters) {
                requirePresent(filter, "metricScopeFilter");
            }
            requirePresent(operator, "operator");
            requireOperatorOperand(operator, operand);
        }

        public Metric(
                CanonicalMetricDefinition definition,
                CanonicalWindow window,
                ComparisonOperator operator,
                CanonicalValue operand) {
            this(definition, window, List.of(), operator, operand);
        }
    }

    /** A typed arithmetic expression over atomic Metrics under one common canonical window. */
    record MetricCalculation(
            CanonicalMetricExpression expression,
            CanonicalWindow window,
            ComparisonOperator operator,
            CanonicalValue operand) implements QueryAst {
        public MetricCalculation {
            requirePresent(expression, "metricExpression");
            requirePresent(window, "metricWindow");
            requirePresent(operator, "operator");
            requireOperatorOperand(operator, operand);
        }
    }

    /** A relation-scoped existence check that keeps its child under one relation boundary. */
    record Exists(CanonicalRelationReference relation, CanonicalWindow window, QueryAst child)
            implements QueryAst {
        public Exists {
            requirePresent(relation, "relation");
            requirePresent(window, "relationWindow");
            requirePresent(child, "existsChild");
        }
    }

    /**
     * A leaf that pins one exact immutable Audience Version.
     *
     * <p>It carries the immutable pin and nothing else. It is never expanded into the referenced predicate and
     * never replaced by it: the referenced material stays a separately identified, hash-bound dependency that
     * the planner resolves, reauthorizes and freezes on its own, so the AST keeps exactly the shape the T07
     * canonical tree declared. No owner, tenant, program, DataScope, availability or dependency fact appears
     * here, and there is no mutable pointer field, so "whatever audience X currently is" is unrepresentable.
     *
     * <p>It reuses the accepted T07 {@link CanonicalAudienceVersionRef} rather than declaring a second
     * synonymous pin, so an AST reference and a canonical reference can never drift apart.
     */
    record AudienceReference(com.company.campaign.segment.canonical.CanonicalAudienceVersionRef reference)
            implements QueryAst {
        public AudienceReference {
            requirePresent(reference, "audienceVersionReference");
        }
    }

    private static void requireOperatorOperand(ComparisonOperator operator, CanonicalValue operand) {
        if (operator.isNullOperator()) {
            if (operand != null) {
                throw invalid("nullOperatorValue", "A NULL comparison operator must not carry an operand.");
            }
            return;
        }
        if (operand == null) {
            throw invalid("missingOperatorValue", "A non-NULL comparison operator requires an operand.");
        }
        switch (operator) {
            case IN, NOT_IN -> {
                if (!(operand instanceof CanonicalValue.ListValue)) {
                    throw invalid("listOperatorValue", "A LIST operator requires a canonical LIST operand.");
                }
            }
            case BETWEEN -> {
                if (!(operand instanceof CanonicalValue.RangeValue)) {
                    throw invalid("rangeOperatorValue", "A RANGE operator requires a canonical RANGE operand.");
                }
            }
            default -> {
                if (operand instanceof CanonicalValue.ListValue || operand instanceof CanonicalValue.RangeValue) {
                    throw invalid("scalarOperatorValue", "A scalar operator requires a scalar operand.");
                }
            }
        }
    }

    private static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw invalid(invariant, "A query AST boundary requires a non-null input.");
        }
    }

    private static QueryException invalid(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_AST_INVALID, invariant, message);
    }
}
