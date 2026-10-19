package com.company.campaign.segment.validation;

import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.AudienceReferencePredicate;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pre-order location index and deterministic JSON-path builder for one editable predicate tree.
 *
 * <p>Node identity is the T05 stable {@code nodeId}, which is unique across the whole tree, so a location can
 * be recovered for any node without threading a path through every stage. The traversal index is what makes
 * issue ordering follow the <em>original</em> tree rather than an alphabetical path sort: without it,
 * {@code children[10]} would sort before {@code children[2]}.
 *
 * <p>Paths are derived from the original editable document shape — {@code $.predicate.children[0]} for a
 * group child, {@code $.predicate.child} for a NOT, {@code $.predicate.predicate} for an EXISTS body — and
 * never from the canonical form.
 */
final class TreeIndex {

    static final String ROOT_PATH = "$.predicate";

    private final Map<String, Location> byNodeId = new LinkedHashMap<>();
    private int nextIndex;

    private TreeIndex() {
    }

    /** The location of one node: its pre-order index, its JSON path and its stable node ID. */
    record Location(int index, String path, String nodeId) {
    }

    static TreeIndex of(Predicate root) {
        TreeIndex index = new TreeIndex();
        index.walk(root, ROOT_PATH);
        return index;
    }

    /** The location of a node that must exist in this tree. */
    Location location(String nodeId) {
        Location location = byNodeId.get(nodeId);
        if (location == null) {
            // Defensive: the index covers the exact tree being validated, so this never happens in practice.
            throw new IllegalStateException("node is not part of the indexed predicate tree");
        }
        return location;
    }

    /** The path of a group's direct child by position. */
    String childPath(String parentNodeId, int childIndex) {
        return location(parentNodeId).path() + ".children[" + childIndex + "]";
    }

    /** The path of a NOT node's single child. */
    String notChildPath(String parentNodeId) {
        return location(parentNodeId).path() + ".child";
    }

    /** The path of an EXISTS node's nested predicate. */
    String existsChildPath(String parentNodeId) {
        return location(parentNodeId).path() + ".predicate";
    }

    /** The path of a metric's or EXISTS node's declared window. */
    String windowPath(String nodeId) {
        return location(nodeId).path() + ".window";
    }

    /** The path of an attribute's or metric's present operand. */
    String valuePath(String nodeId) {
        return location(nodeId).path() + ".value";
    }

    static String scopeFilterKey(String metricNodeId, String filterId) {
        return metricNodeId + "/scopeFilters/" + filterId;
    }

    static String metricExpressionRefKey(String nodeId, String expressionPath) {
        return nodeId + "/expression/" + expressionPath;
    }

    /** The path of one operand inside a LIST value. */
    String itemPath(String nodeId, int itemIndex) {
        return valuePath(nodeId) + ".items[" + itemIndex + "]";
    }

    /** The path of one bound inside a RANGE value. */
    String boundPath(String nodeId, String bound) {
        return valuePath(nodeId) + "." + bound;
    }

    private void walk(Predicate node, String path) {
        byNodeId.put(node.nodeId(), new Location(nextIndex++, path, node.nodeId()));
        switch (node) {
            case GroupPredicate group -> {
                for (int childIndex = 0; childIndex < group.children().size(); childIndex++) {
                    walk(group.children().get(childIndex), childPath(node.nodeId(), childIndex));
                }
            }
            case NotPredicate not -> walk(not.child(), notChildPath(node.nodeId()));
            case ExistsPredicate exists -> walk(exists.predicate(), existsChildPath(node.nodeId()));
            case AttributePredicate ignored -> {
            }
            case MetricPredicate metric -> {
                for (int filterIndex = 0; filterIndex < metric.scopeFilters().size(); filterIndex++) {
                    MetricScopeFilter filter = metric.scopeFilters().get(filterIndex);
                    String key = scopeFilterKey(metric.nodeId(), filter.filterId());
                    byNodeId.put(key, new Location(
                            nextIndex++, path + ".scopeFilters[" + filterIndex + "]", key));
                }
                java.util.List<MetricScopeFilter> selectorFilters = switch (metric.window()) {
                    case com.company.campaign.segment.dsl.window.SelectedEventWindow selected ->
                            selected.selectorScopeFilters();
                    case com.company.campaign.segment.dsl.window.RelativeToEventWindow relative ->
                            relative.selectorScopeFilters();
                    default -> java.util.List.of();
                };
                for (int filterIndex = 0; filterIndex < selectorFilters.size(); filterIndex++) {
                    MetricScopeFilter filter = selectorFilters.get(filterIndex);
                    String key = scopeFilterKey(metric.nodeId(), filter.filterId());
                    byNodeId.put(key, new Location(
                            nextIndex++, path + ".window.selectorScopeFilters[" + filterIndex + "]", key));
                }
            }
            case MetricExpressionPredicate calculation ->
                    walkMetricExpression(calculation.nodeId(), calculation.expression(), path + ".expression", "root");
            // A reference leaf owns no nested node, no window, no operand and no scope filter, so it adds only
            // its own location to the index.
            case AudienceReferencePredicate ignored -> {
            }
        }
    }

    private void walkMetricExpression(
            String nodeId, MetricExpression expression, String jsonPath, String expressionPath) {
        switch (expression) {
            case MetricExpression.MetricRef ignored -> {
                String key = metricExpressionRefKey(nodeId, expressionPath);
                byNodeId.put(key, new Location(nextIndex++, jsonPath, key));
            }
            case MetricExpression.Add add -> {
                for (int index = 0; index < add.operands().size(); index++) {
                    walkMetricExpression(
                            nodeId, add.operands().get(index), jsonPath + ".operands[" + index + "]",
                            expressionPath + "." + index);
                }
            }
            case MetricExpression.Subtract subtract -> {
                walkMetricExpression(nodeId, subtract.left(), jsonPath + ".left", expressionPath + ".left");
                walkMetricExpression(nodeId, subtract.right(), jsonPath + ".right", expressionPath + ".right");
            }
            case MetricExpression.Multiply multiply -> {
                walkMetricExpression(nodeId, multiply.left(), jsonPath + ".left", expressionPath + ".left");
                walkMetricExpression(nodeId, multiply.right(), jsonPath + ".right", expressionPath + ".right");
            }
            case MetricExpression.Divide divide -> {
                walkMetricExpression(nodeId, divide.left(), jsonPath + ".left", expressionPath + ".left");
                walkMetricExpression(nodeId, divide.right(), jsonPath + ".right", expressionPath + ".right");
            }
        }
    }
}
