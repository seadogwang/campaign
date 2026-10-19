package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.domain.ComparisonOperator;

import java.util.List;
import java.util.ArrayList;

/**
 * Closed, immutable canonical semantic predicate tree.
 *
 * <p>It is a semantic artifact, not an editable replacement: every node has lost its stable {@code nodeId},
 * because a node ID is an edit identity rather than meaning. Direct children of a commutative {@code ALL} or
 * {@code ANY} are ordered by child semantic hash, while {@code NOT}, {@code EXISTS} and RANGE roles keep their
 * exact structure: no group is flattened, no De Morgan rewrite is applied, no predicate is distributed and
 * no two EXISTS nodes are merged.
 *
 * <p>A NULL operator carries a {@code null} value, exactly as the editable document does, so
 * {@code IS_NULL} can never be confused with a comparison against an invented default. Every constructor
 * proves the operator/value shape and the operand's compatibility with the definition, and a group owns the
 * canonical child order, so a caller cannot pin a state the canonicalizer would never emit.
 */
public sealed interface CanonicalNode
        permits CanonicalNode.Group, CanonicalNode.Negation, CanonicalNode.Attribute, CanonicalNode.Metric,
        CanonicalNode.MetricCalculation, CanonicalNode.Exists, CanonicalNode.AudienceReference {

    /** The commutative group kinds whose direct children the canonicalizer may order. */
    enum GroupKind {
        ALL,
        ANY
    }

    /** An {@code ALL}/{@code ANY} group with at least one child and canonical child order. */
    record Group(GroupKind kind, List<CanonicalNode> children) implements CanonicalNode {
        public Group {
            CanonicalInvariants.requirePresent(kind, "groupKind");
            if (children == null || children.isEmpty()) {
                throw CanonicalInvariants.failure(
                        CanonicalErrorCodes.CANONICAL_GROUP_NOT_CANONICAL, "groupChildren",
                        "A canonical group requires at least one child.");
            }
            for (CanonicalNode child : children) {
                CanonicalInvariants.requirePresent(child, "groupChildren");
            }
            // The constructor owns the canonical child order and the duplicate-free guarantee, so a caller
            // cannot pin an unsorted or repeated group.
            children = CanonicalInvariants.orderedChildren(children);
        }
    }

    /** A unary {@code NOT}; its single child is never rewritten. */
    record Negation(CanonicalNode child) implements CanonicalNode {
        public Negation {
            CanonicalInvariants.requirePresent(child, "negationChild");
        }
    }

    /** An attribute comparison; {@code value} is null exactly for {@code IS_NULL}/{@code IS_NOT_NULL}. */
    record Attribute(CanonicalDefinition definition, ComparisonOperator operator, CanonicalValue value)
            implements CanonicalNode {
        public Attribute {
            CanonicalInvariants.requirePresent(definition, "attributeDefinition");
            CanonicalInvariants.requirePresent(operator, "operator");
            CanonicalInvariants.requireOperatorValue(operator, value);
            if (value != null) {
                CanonicalInvariants.requireValueKind(value, definition.dataType(), "attributeOperand");
            }
        }
    }

    /** A metric aggregate comparison over a canonical window. */
    record Metric(
            CanonicalMetricDefinition definition,
            CanonicalWindow window,
            List<CanonicalMetricFilter> scopeFilters,
            ComparisonOperator operator,
            CanonicalValue value) implements CanonicalNode {
        public Metric {
            CanonicalInvariants.requirePresent(definition, "metricDefinition");
            CanonicalInvariants.requirePresent(window, "window");
            scopeFilters = canonicalScopeFilters(scopeFilters);
            CanonicalInvariants.requirePresent(operator, "operator");
            CanonicalInvariants.requireOperatorValue(operator, value);
            if (value != null) {
                CanonicalInvariants.requireValueKind(value, definition.resultType(), "metricOperand");
            }
        }

        public Metric(
                CanonicalMetricDefinition definition,
                CanonicalWindow window,
                ComparisonOperator operator,
                CanonicalValue value) {
            this(definition, window, List.of(), operator, value);
        }

        private static List<CanonicalMetricFilter> canonicalScopeFilters(
                List<CanonicalMetricFilter> filters) {
            if (filters == null || filters.isEmpty()) {
                return List.of();
            }
            List<CanonicalMetricFilter> ordered = new ArrayList<>(filters.size());
            for (CanonicalMetricFilter filter : filters) {
                ordered.add(CanonicalInvariants.requirePresent(filter, "metricScopeFilters"));
            }
            ordered.sort(CanonicalMetricFilter::compareTo);
            for (int index = 1; index < ordered.size(); index++) {
                if (ordered.get(index - 1).compareTo(ordered.get(index)) == 0) {
                    throw CanonicalInvariants.failure(
                            CanonicalErrorCodes.CANONICAL_SCOPE_INVALID,
                            "metricScopeFilters",
                            "A canonical Metric must not repeat one row-scope filter.");
                }
            }
            return List.copyOf(ordered);
        }
    }

    /** A typed arithmetic expression over atomic Metrics under one shared window. */
    record MetricCalculation(
            CanonicalMetricExpression expression,
            CanonicalWindow window,
            ComparisonOperator operator,
            CanonicalValue value) implements CanonicalNode {
        public MetricCalculation {
            CanonicalInvariants.requirePresent(expression, "metricExpression");
            CanonicalInvariants.requirePresent(window, "window");
            CanonicalInvariants.requirePresent(operator, "operator");
            CanonicalInvariants.requireOperatorValue(operator, value);
            if (value != null) {
                CanonicalInvariants.requireValueKind(
                        value, expression.resultType().dataType(), "metricExpressionOperand");
            }
        }
    }

    /** A relation-scoped existence check that keeps its child predicate beneath its own boundary. */
    record Exists(CanonicalRelationReference relation, CanonicalWindow window, CanonicalNode child)
            implements CanonicalNode {
        public Exists {
            CanonicalInvariants.requirePresent(relation, "relation");
            CanonicalInvariants.requirePresent(window, "window");
            CanonicalInvariants.requirePresent(child, "existsChild");
            if (CanonicalInvariants.containsMetric(child)) {
                throw CanonicalInvariants.failure(CanonicalErrorCodes.CANONICAL_SCOPE_INVALID, "existsChild",
                        "A canonical EXISTS must not contain a metric aggregate.");
            }
        }
    }

    /**
     * A leaf that pins one exact immutable Audience Version.
     *
     * <p>It holds only the immutable pin. It is never expanded into the editable parent and never replaced by
     * the referenced predicate: the referenced material stays a separately identified, hash-bound dependency
     * that downstream planning resolves and reauthorizes on its own.
     */
    record AudienceReference(CanonicalAudienceVersionRef reference) implements CanonicalNode {
        public AudienceReference {
            CanonicalInvariants.requirePresent(reference, "audienceVersionReference");
        }
    }
}
