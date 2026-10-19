package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EventTimeSemantics;
import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationEventTimeCapability;
import com.company.campaign.segment.canonical.CanonicalAudienceVersionRef;
import com.company.campaign.segment.canonical.CanonicalDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricDefinition;
import com.company.campaign.segment.canonical.CanonicalMetricFilter;
import com.company.campaign.segment.canonical.CanonicalNode;
import com.company.campaign.segment.canonical.CanonicalRelationReference;
import com.company.campaign.segment.canonical.CanonicalSegment;
import com.company.campaign.segment.canonical.CanonicalValue;
import com.company.campaign.segment.canonical.CanonicalWindow;
import com.company.campaign.segment.canonical.CanonicalizerVersions;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
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
import com.company.campaign.segment.dsl.predicate.TypedValueCondition;
import com.company.campaign.segment.dsl.window.FixedWindow;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.segment.dsl.window.RollingWindow;
import com.company.campaign.segment.dsl.window.RelativeToAttributeWindow;
import com.company.campaign.segment.dsl.window.TimeWindow;
import com.company.campaign.segment.dsl.metric.MetricExpression;
import com.company.campaign.segment.canonical.CanonicalMetricExpression;
import com.company.campaign.segment.canonical.CanonicalMetricExpressionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds the canonical semantic AST from a fully validated editable document.
 *
 * <p>It runs only when no blocking issue exists, so every referenced definition is resolved and every operand
 * is type-compatible. It rewrites nothing business-changing: it drops edit identity ({@code nodeId}), sorts
 * <em>direct</em> children of commutative {@code ALL}/{@code ANY} groups by child semantic hash, normalizes
 * values, and leaves grouping, negation, RANGE roles, window structure and every EXISTS boundary exactly as
 * the editable tree declared them.
 *
 * <p>Child order is sorted by the child's canonical hash with a full-value serialization tie-breaker, so two
 * semantically identical subtrees that differ only in declaration order produce identical bytes.
 */
final class SegmentCanonicalizer {

    private SegmentCanonicalizer() {
    }

    static CanonicalSegment canonicalize(SegmentDsl document, Map<String, MetadataDefinition> resolved) {
        CanonicalNode predicate = node(document.predicate(), resolved);
        return new CanonicalSegment(
                CanonicalizerVersions.CANONICALIZER_VERSION,
                document.schemaVersion(),
                document.subject(),
                document.metadata(),
                predicate);
    }

    static CanonicalNode node(Predicate predicate, Map<String, MetadataDefinition> resolved) {
        return switch (predicate) {
            case GroupPredicate group -> {
                CanonicalNode.GroupKind kind = group.type() == com.company.campaign.segment.dsl.predicate.PredicateType.ALL
                        ? CanonicalNode.GroupKind.ALL
                        : CanonicalNode.GroupKind.ANY;
                List<CanonicalNode> children = new ArrayList<>(group.children().size());
                group.children().forEach(child -> children.add(node(child, resolved)));
                // The canonical group constructor owns the child order and duplicate-free guarantee.
                yield new CanonicalNode.Group(kind, children);
            }
            case NotPredicate not -> new CanonicalNode.Negation(node(not.child(), resolved));
            case AttributePredicate attribute -> {
                AttributeDefinition definition = (AttributeDefinition) resolved.get(attribute.nodeId());
                yield new CanonicalNode.Attribute(
                        definition(definition),
                        attribute.operator(),
                        value(attribute.operator(), attribute.condition(), definition.dataType(),
                                definition.stringSemantics()));
            }
            case MetricPredicate metric -> {
                MetricDefinition definition = (MetricDefinition) resolved.get(metric.nodeId());
                yield new CanonicalNode.Metric(
                        metricDefinition(definition),
                        window(metric.window(), metric.nodeId(), resolved),
                        metricFilters(metric, resolved),
                        metric.operator(),
                        value(metric.operator(), metric.condition(), definition.resultType(),
                                definition.stringSemantics()));
            }
            case MetricExpressionPredicate calculation -> {
                CanonicalMetricExpression expression = metricExpression(
                        calculation.nodeId(), calculation.expression(), "root", resolved);
                yield new CanonicalNode.MetricCalculation(
                        expression,
                        window(calculation.window(), calculation.nodeId(), resolved),
                        calculation.operator(),
                        value(calculation.operator(), calculation.condition(),
                                expression.resultType().dataType(), null));
            }
            case ExistsPredicate exists -> {
                RelationDefinition relation = (RelationDefinition) resolved.get(exists.nodeId());
                yield new CanonicalNode.Exists(
                        relation(relation), window(exists.window(), null, resolved), node(exists.predicate(), resolved));
            }
            // The reference keeps only its immutable pin: the resolved owner, effective scope, availability and
            // dependency facts stay outside the canonical AST, exactly as they are outside the editable one.
            case AudienceReferencePredicate reference -> new CanonicalNode.AudienceReference(
                    new CanonicalAudienceVersionRef(
                            reference.audienceId(), reference.versionId(), reference.versionHash()));
        };
    }

    private static CanonicalMetricExpression metricExpression(
            String nodeId,
            MetricExpression expression,
            String path,
            Map<String, MetadataDefinition> resolved) {
        return switch (expression) {
            case MetricExpression.MetricRef ignored -> {
                MetricDefinition definition = (MetricDefinition) resolved.get(
                        TreeIndex.metricExpressionRefKey(nodeId, path));
                yield new CanonicalMetricExpression.MetricRef(
                        metricDefinition(definition), MetricExpressionTypes.reference(definition));
            }
            case MetricExpression.Add add -> {
                List<CanonicalMetricExpression> operands = new ArrayList<>(add.operands().size());
                CanonicalMetricExpressionType resultType = null;
                for (int index = 0; index < add.operands().size(); index++) {
                    CanonicalMetricExpression operand = metricExpression(
                            nodeId, add.operands().get(index), path + "." + index, resolved);
                    operands.add(operand);
                    resultType = resultType == null
                            ? operand.resultType()
                            : MetricExpressionTypes.addOrSubtract(resultType, operand.resultType());
                }
                yield new CanonicalMetricExpression.Add(operands, resultType);
            }
            case MetricExpression.Subtract subtract -> {
                CanonicalMetricExpression left = metricExpression(
                        nodeId, subtract.left(), path + ".left", resolved);
                CanonicalMetricExpression right = metricExpression(
                        nodeId, subtract.right(), path + ".right", resolved);
                yield new CanonicalMetricExpression.Subtract(
                        left, right, MetricExpressionTypes.addOrSubtract(left.resultType(), right.resultType()));
            }
            case MetricExpression.Multiply multiply -> {
                CanonicalMetricExpression left = metricExpression(
                        nodeId, multiply.left(), path + ".left", resolved);
                CanonicalMetricExpression right = metricExpression(
                        nodeId, multiply.right(), path + ".right", resolved);
                yield new CanonicalMetricExpression.Multiply(
                        left, right, MetricExpressionTypes.multiply(left.resultType(), right.resultType()));
            }
            case MetricExpression.Divide divide -> {
                CanonicalMetricExpression left = metricExpression(
                        nodeId, divide.left(), path + ".left", resolved);
                CanonicalMetricExpression right = metricExpression(
                        nodeId, divide.right(), path + ".right", resolved);
                yield new CanonicalMetricExpression.Divide(
                        left, right, divide.zeroDenominator(),
                        MetricExpressionTypes.divide(left.resultType(), right.resultType()));
            }
        };
    }

    private static List<CanonicalMetricFilter> metricFilters(
            MetricPredicate metric, Map<String, MetadataDefinition> resolved) {
        return metricFilters(metric.scopeFilters(), metric.nodeId(), resolved);
    }

    private static List<CanonicalMetricFilter> metricFilters(
            List<MetricScopeFilter> source,
            String nodeId,
            Map<String, MetadataDefinition> resolved) {
        List<CanonicalMetricFilter> filters = new ArrayList<>(source.size());
        for (MetricScopeFilter filter : source) {
            AttributeDefinition definition = (AttributeDefinition) resolved.get(
                    TreeIndex.scopeFilterKey(nodeId, filter.filterId()));
            filters.add(new CanonicalMetricFilter(
                    definition(definition),
                    filter.operator(),
                    value(filter.operator(), filter.condition(), definition.dataType(),
                            definition.stringSemantics())));
        }
        return filters;
    }

    private static CanonicalValue value(
            ComparisonOperator operator,
            ConditionValue condition,
            com.company.campaign.metadata.domain.SemanticDataType dataType,
            com.company.campaign.metadata.domain.StringSemantics stringSemantics) {
        if (operator.isNullOperator()) {
            return null;
        }
        return SegmentValueRules.canonicalValue(
                dataType, stringSemantics, ((TypedValueCondition) condition).value());
    }

    private static CanonicalDefinition definition(AttributeDefinition definition) {
        return new CanonicalDefinition(
                definition.kind(),
                definition.id(),
                definition.subject(),
                definition.dataType(),
                definition.nullSemantics(),
                definition.currencySemantics(),
                definition.stringSemantics());
    }

    private static CanonicalMetricDefinition metricDefinition(MetricDefinition definition) {
        return new CanonicalMetricDefinition(
                definition.id(),
                definition.subject(),
                definition.resultType(),
                definition.nullSemantics(),
                definition.currencySemantics(),
                definition.stringSemantics(),
                definition.aggregation(),
                definition.emptySet().behavior(),
                definition.emptySet().value(),
                definition.eventTimeSemantics(),
                definition.windowBoundarySemantics());
    }

    private static CanonicalRelationReference relation(RelationDefinition definition) {
        RelationEventTimeCapability capability = definition.eventTimeCapability();
        EventTimeSemantics eventTimeSemantics = capability == null ? null : capability.semantics();
        String eventTimeField = capability == null ? null : capability.eventTimeField();
        return new CanonicalRelationReference(
                definition.id(),
                definition.sourceEntity(),
                definition.targetEntity(),
                definition.cardinality(),
                definition.scope().joinKind(),
                definition.scope().sourceJoinField(),
                definition.scope().targetJoinField(),
                definition.targetNullSemantics(),
                definition.emptyRelationSemantics(),
                eventTimeSemantics,
                eventTimeField);
    }

    private static CanonicalWindow window(
            TimeWindow window, String metricNodeId, Map<String, MetadataDefinition> resolved) {
        return switch (window) {
            case RollingWindow rolling -> new CanonicalWindow.RollingWindow(rollingDays(rolling));
            case LifetimeWindow ignored -> new CanonicalWindow.LifetimeWindow();
            case FixedWindow fixed -> new CanonicalWindow.FixedWindow(
                    SegmentValueRules.canonicalInstant(fixed.start()),
                    SegmentValueRules.canonicalInstant(fixed.end()));
            case RelativeToAttributeWindow relative -> new CanonicalWindow.RelativeToAttributeWindow(
                    relative.anchorDefinitionId(), offsetDays(relative.startOffset(), relative.unit()),
                    offsetDays(relative.endOffset(), relative.unit()));
            case com.company.campaign.segment.dsl.window.SelectedEventWindow selected ->
                    new CanonicalWindow.SelectedEventWindow(
                            selected.selectorId(), metricFilters(
                            selected.selectorScopeFilters(), metricNodeId, resolved));
            case com.company.campaign.segment.dsl.window.RelativeToEventWindow relative ->
                    new CanonicalWindow.RelativeToEventWindow(
                            relative.selectorId(), metricFilters(
                            relative.selectorScopeFilters(), metricNodeId, resolved),
                            offsetDays(relative.startOffset(), relative.unit()),
                            offsetDays(relative.endOffset(), relative.unit()),
                            relative.excludeAnchorEvent());
        };
    }

    private static long offsetDays(int amount, com.company.campaign.segment.dsl.window.WindowUnit unit) {
        return unit == com.company.campaign.segment.dsl.window.WindowUnit.WEEK
                ? Math.multiplyExact((long) amount, 7L)
                : amount;
    }

    /**
     * Normalizes a declared rolling duration to whole days.
     *
     * <p>v1.3 fixes {@code DAY} at 24 hours and {@code WEEK} at exactly 7 days, so a week is normalized to
     * its seven-day equivalent. The multiplication is overflow-safe even though the T05 amount bound already
     * makes overflow impossible.
     */
    private static long rollingDays(RollingWindow rolling) {
        return switch (rolling.unit()) {
            case DAY -> rolling.amount();
            case WEEK -> Math.multiplyExact((long) rolling.amount(), 7L);
        };
    }
}
