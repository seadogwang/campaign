package com.company.campaign.query.ast;

import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;

import java.util.ArrayList;
import java.util.List;

/**
 * The one deterministic compiler from an accepted T07 canonical predicate tree to the typed Query AST.
 *
 * <p>It maps each canonical node to exactly one AST node and does nothing else: no De Morgan rewrite, no
 * group distribution or flattening, no predicate merge, no nested-group collapse, no EXISTS-to-join lowering,
 * no {@code NOT_EQUAL}-to-{@code NOT(EQUAL)} rewrite and no NULL default. Because the canonical tree is
 * already normalized, compiling it repeatedly produces an equal AST every time.
 *
 * <p>The compiler is immutable and thread-safe; it holds no state between calls.
 */
public final class QueryAstCompiler {

    /** Compiles the predicate root of one canonical segment. */
    public static QueryAst compile(CanonicalSegment segment) {
        if (segment == null) {
            throw new QueryException(
                    QueryErrorCodes.QUERY_INPUT_MISSING, "canonicalSegment",
                    "A canonical segment is required to compile the Query AST.");
        }
        return compile(segment.predicate());
    }

    /** Compiles one canonical predicate node and every descendant, one-for-one. */
    public static QueryAst compile(CanonicalNode node) {
        if (node == null) {
            throw new QueryException(
                    QueryErrorCodes.QUERY_INPUT_MISSING, "canonicalNode",
                    "A canonical predicate node is required to compile the Query AST.");
        }
        return switch (node) {
            case CanonicalNode.Group group -> new QueryAst.Group(
                    group.kind() == CanonicalNode.GroupKind.ALL ? QueryAst.GroupKind.ALL : QueryAst.GroupKind.ANY,
                    compileAll(group.children()));
            case CanonicalNode.Negation negation -> new QueryAst.Negation(compile(negation.child()));
            case CanonicalNode.Attribute attribute -> new QueryAst.Attribute(
                    attribute.definition(), attribute.operator(), attribute.value());
            case CanonicalNode.Metric metric -> new QueryAst.Metric(
                    metric.definition(), metric.window(), metric.scopeFilters(), metric.operator(), metric.value());
            case CanonicalNode.MetricCalculation calculation -> new QueryAst.MetricCalculation(
                    calculation.expression(), calculation.window(), calculation.operator(), calculation.value());
            case CanonicalNode.Exists exists -> new QueryAst.Exists(
                    exists.relation(), exists.window(), compile(exists.child()));
            // The reference compiles to the one closed AST leaf that carries its exact immutable pin. It is
            // never expanded, inlined or replaced by the referenced predicate: resolving, reauthorizing and
            // freezing the referenced material is the planner's job, and the AST stays a faithful projection of
            // the canonical tree it came from.
            case CanonicalNode.AudienceReference reference -> new QueryAst.AudienceReference(reference.reference());
        };
    }

    private static List<QueryAst> compileAll(List<CanonicalNode> children) {
        List<QueryAst> compiled = new ArrayList<>(children.size());
        for (CanonicalNode child : children) {
            compiled.add(compile(child));
        }
        return compiled;
    }

    private QueryAstCompiler() {
    }
}
