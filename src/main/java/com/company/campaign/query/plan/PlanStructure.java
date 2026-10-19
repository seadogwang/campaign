package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.canonical.CanonicalMetricExpression;

import java.util.ArrayList;
import java.util.List;

/**
 * The one shared structural traversal of a Query AST, used by both the planner and the plan's construction
 * boundary so the two can never disagree about which node sits at which deterministic path.
 *
 * <p>The structural path is a pre-order route from the predicate root ({@code r}) through group child indices
 * ({@code .0}, {@code .1}), a negation step ({@code .not}) and an EXISTS child step ({@code .exists}). It is a
 * pure function of the tree shape, never a runtime object identity.
 *
 * <p>It also owns the single canonical projection from a Query AST node back to the T07 canonical node. The
 * projection is closed, deterministic and null-hostile, and the AST already preserves the canonical structure
 * one-for-one, so projecting a compiled AST reproduces the accepted canonical bytes and hash exactly.
 */
final class PlanStructure {

    /** Every Attribute, Metric, EXISTS and Audience Reference site in one AST, under its structural path. */
    record Sites(
            List<AttributeSite> attributes,
            List<MetricSite> metrics,
            List<ExpressionMetricSite> expressionMetrics,
            List<ExistsSite> exists,
            List<ReferenceSite> references) {
    }

    /** One attribute comparison site. */
    record AttributeSite(String path, QueryAst.Attribute node) {
    }

    /** One metric aggregate site. */
    record MetricSite(String path, QueryAst.Metric node) {
    }

    /** One atomic Metric leaf inside a typed Metric expression. */
    record ExpressionMetricSite(
            String path,
            com.company.campaign.segment.canonical.CanonicalMetricDefinition definition,
            com.company.campaign.segment.canonical.CanonicalWindow window) {
    }

    /** One relation EXISTS site. */
    record ExistsSite(String path, QueryAst.Exists node) {
    }

    /**
     * One Existing Audience Reference site: its structural path, its exact pin and the subject scope it is
     * evaluated in.
     *
     * <p>The scope subject is derived by this walk from the tree shape — the plan's root subject at the root and
     * the relation's target entity beneath an {@code EXISTS}. It is never read from the node itself, so a
     * reference can never declare the subject it wishes to be judged against.
     */
    record ReferenceSite(String path, QueryAst.AudienceReference node, SubjectKind scopeSubject) {
    }

    /**
     * Collects every Attribute, Metric, EXISTS and Reference site in deterministic pre-order.
     *
     * @param rootSubject the plan's root subject, which every reference outside an {@code EXISTS} is judged in
     */
    static Sites collect(QueryAst root, SubjectKind rootSubject) {
        requirePresent(root, "predicateRoot");
        requirePresent(rootSubject, "predicateSubject");
        List<AttributeSite> attributes = new ArrayList<>();
        List<MetricSite> metrics = new ArrayList<>();
        List<ExpressionMetricSite> expressionMetrics = new ArrayList<>();
        List<ExistsSite> exists = new ArrayList<>();
        List<ReferenceSite> references = new ArrayList<>();
        walk(root, "r", rootSubject, attributes, metrics, expressionMetrics, exists, references);
        return new Sites(
                List.copyOf(attributes), List.copyOf(metrics), List.copyOf(expressionMetrics),
                List.copyOf(exists), List.copyOf(references));
    }

    private static void walk(
            QueryAst node,
            String path,
            SubjectKind subject,
            List<AttributeSite> attributes,
            List<MetricSite> metrics,
            List<ExpressionMetricSite> expressionMetrics,
            List<ExistsSite> exists,
            List<ReferenceSite> references) {
        switch (node) {
            case QueryAst.Group group -> {
                List<QueryAst> children = group.children();
                for (int index = 0; index < children.size(); index++) {
                    walk(children.get(index), path + "." + index, subject,
                            attributes, metrics, expressionMetrics, exists, references);
                }
            }
            case QueryAst.Negation negation ->
                    walk(negation.child(), path + ".not", subject,
                            attributes, metrics, expressionMetrics, exists, references);
            case QueryAst.Attribute attribute -> attributes.add(new AttributeSite(path, attribute));
            case QueryAst.Metric metric -> metrics.add(new MetricSite(path, metric));
            case QueryAst.MetricCalculation calculation -> collectExpressionMetrics(
                    calculation.expression(), calculation.window(), path + ".expression.root", expressionMetrics);
            case QueryAst.Exists existsNode -> {
                exists.add(new ExistsSite(path, existsNode));
                // Beneath a relation boundary every site is evaluated against the relation's target entity,
                // which is exactly the subject a reference there must be defined over.
                walk(existsNode.child(), path + ".exists", existsNode.relation().targetEntity(),
                        attributes, metrics, expressionMetrics, exists, references);
            }
            case QueryAst.AudienceReference reference -> references.add(new ReferenceSite(path, reference, subject));
        }
    }

    private static void collectExpressionMetrics(
            CanonicalMetricExpression expression,
            com.company.campaign.segment.canonical.CanonicalWindow window,
            String path,
            List<ExpressionMetricSite> sites) {
        switch (expression) {
            case CanonicalMetricExpression.MetricRef reference ->
                    sites.add(new ExpressionMetricSite(path, reference.definition(), window));
            case CanonicalMetricExpression.Add add -> {
                for (int index = 0; index < add.operands().size(); index++) {
                    collectExpressionMetrics(add.operands().get(index), window, path + "." + index, sites);
                }
            }
            case CanonicalMetricExpression.Subtract subtract -> {
                collectExpressionMetrics(subtract.left(), window, path + ".left", sites);
                collectExpressionMetrics(subtract.right(), window, path + ".right", sites);
            }
            case CanonicalMetricExpression.Multiply multiply -> {
                collectExpressionMetrics(multiply.left(), window, path + ".left", sites);
                collectExpressionMetrics(multiply.right(), window, path + ".right", sites);
            }
            case CanonicalMetricExpression.Divide divide -> {
                collectExpressionMetrics(divide.left(), window, path + ".left", sites);
                collectExpressionMetrics(divide.right(), window, path + ".right", sites);
            }
        }
    }

    /**
     * Projects one Query AST node back onto the equivalent T07 canonical node.
     *
     * <p>Because the AST retains the canonical definitions, windows, operators and operands unchanged and a
     * canonical group fixes its own child order, this projection reproduces the exact canonical bytes a
     * {@code CanonicalSegmentCodec} would write for the original predicate tree.
     */
    static CanonicalNode canonicalProjection(QueryAst node) {
        requirePresent(node, "queryAstNode");
        return switch (node) {
            case QueryAst.Group group -> {
                List<CanonicalNode> children = new ArrayList<>(group.children().size());
                for (QueryAst child : group.children()) {
                    children.add(canonicalProjection(child));
                }
                yield new CanonicalNode.Group(
                        group.kind() == QueryAst.GroupKind.ALL
                                ? CanonicalNode.GroupKind.ALL
                                : CanonicalNode.GroupKind.ANY,
                        children);
            }
            case QueryAst.Negation negation -> new CanonicalNode.Negation(canonicalProjection(negation.child()));
            case QueryAst.Attribute attribute ->
                    new CanonicalNode.Attribute(attribute.definition(), attribute.operator(), attribute.operand());
            case QueryAst.Metric metric ->
                    new CanonicalNode.Metric(
                            metric.definition(), metric.window(), metric.scopeFilters(),
                            metric.operator(), metric.operand());
            case QueryAst.MetricCalculation calculation -> new CanonicalNode.MetricCalculation(
                    calculation.expression(), calculation.window(), calculation.operator(), calculation.operand());
            case QueryAst.Exists exists ->
                    new CanonicalNode.Exists(exists.relation(), exists.window(), canonicalProjection(exists.child()));
            case QueryAst.AudienceReference reference ->
                    new CanonicalNode.AudienceReference(reference.reference());
        };
    }

    static void requirePresent(Object value, String invariant) {
        if (value == null) {
            throw new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant,
                    "A plan structural boundary requires a non-null input.");
        }
    }

    private PlanStructure() {
    }
}
