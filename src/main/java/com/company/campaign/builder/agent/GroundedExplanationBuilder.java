package com.company.campaign.builder.agent;

import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.preview.PreviewConditionStatistic;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.ExistsPredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.MetricExpressionPredicate;
import com.company.campaign.segment.dsl.predicate.MetricPredicate;
import com.company.campaign.segment.dsl.predicate.MetricScopeFilter;
import com.company.campaign.segment.dsl.predicate.NotPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.resolution.ClauseResolution;
import com.company.campaign.segment.resolution.ResolutionResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the one deterministic grounded explanation from accepted evidence.
 *
 * <p>It reads only the accepted resolution, the accepted DSL document and the safe Preview card, and produces a
 * closed list of typed claims. It never calls a model, never reads free text and never invents a fact: every
 * claim cites accepted clause, node or definition identifiers, a semantic operator/value/window summary derived
 * from the accepted document, or a safe Preview statistic. Ordering and identifiers are derived solely from the
 * evidence, so identical evidence always yields byte-identical claims.
 */
public final class GroundedExplanationBuilder {

    private GroundedExplanationBuilder() {
    }

    /** Builds the grounded explanation for one fully accepted run. */
    public static GroundedExplanation build(
            ResolutionResult resolution, Predicate document, SegmentPreviewCard card) {
        if (resolution == null || document == null || card == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.EXPLANATION_FAILED, "A grounded explanation requires accepted evidence");
        }
        List<Draft> drafts = new ArrayList<>();
        List<ClauseResolution> clauses = new ArrayList<>(resolution.clauses());
        clauses.sort(Comparator.comparing(ClauseResolution::clauseId));
        for (ClauseResolution clause : clauses) {
            if (clause.accepted() != null) {
                drafts.add(new Draft(ExplanationClaimKind.CLAUSE_DEFINITION, List.of(clause.clauseId()), List.of(),
                        List.of(clause.accepted()), null, null, null, null, null));
            }
        }
        collect(document, drafts);
        if (card.conditionStatistics() != null) {
            List<PreviewConditionStatistic> statistics = new ArrayList<>(card.conditionStatistics());
            statistics.sort(Comparator.comparing(PreviewConditionStatistic::path));
            for (PreviewConditionStatistic statistic : statistics) {
                drafts.add(new Draft(ExplanationClaimKind.PREVIEW_STATISTIC, List.of(), List.of(), List.of(), null,
                        null, null, statistic.path(),
                        "true=" + statistic.trueCount() + ";false=" + statistic.falseCount()
                                + ";unknown=" + statistic.unknownCount()));
            }
        }
        List<ExplanationClaim> claims = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            claims.add(new ExplanationClaim(
                    "claim-" + (index + 1), draft.kind(), draft.clauseIds(), draft.nodeIds(), draft.definitionIds(),
                    draft.operator(), draft.valueSummary(), draft.windowSummary(), draft.previewStatisticPath(),
                    draft.previewStatisticSummary()));
        }
        return GroundedExplanation.publish(claims);
    }

    private static String operatorName(ComparisonOperator operator) {
        return operator == null ? null : operator.name();
    }

    private static void collect(Predicate predicate, List<Draft> drafts) {
        if (predicate instanceof AttributePredicate attribute) {
            drafts.add(new Draft(ExplanationClaimKind.ATTRIBUTE_CONDITION, List.of(), List.of(attribute.nodeId()),
                    List.of(attribute.attributeId()), operatorName(attribute.operator()),
                    SegmentValueSummaries.condition(attribute.condition()), null, null, null));
            return;
        }
        if (predicate instanceof MetricPredicate metric) {
            drafts.add(new Draft(ExplanationClaimKind.METRIC_CONDITION, List.of(), List.of(metric.nodeId()),
                    List.of(metric.metricId()), operatorName(metric.operator()),
                    SegmentValueSummaries.condition(metric.condition()),
                    SegmentValueSummaries.window(metric.window()), null, null));
            for (MetricScopeFilter filter : metric.scopeFilters()) {
                drafts.add(new Draft(ExplanationClaimKind.METRIC_CONDITION, List.of(), List.of(filter.filterId()),
                        List.of(filter.attributeId()), operatorName(filter.operator()),
                        SegmentValueSummaries.condition(filter.condition()), null, null, null));
            }
            return;
        }
        if (predicate instanceof MetricExpressionPredicate expression) {
            drafts.add(new Draft(ExplanationClaimKind.METRIC_CONDITION, List.of(), List.of(expression.nodeId()),
                    List.of(), operatorName(expression.operator()),
                    SegmentValueSummaries.condition(expression.condition()),
                    SegmentValueSummaries.window(expression.window()), null, null));
            return;
        }
        if (predicate instanceof ExistsPredicate exists) {
            drafts.add(new Draft(ExplanationClaimKind.RELATION_CONDITION, List.of(), List.of(exists.nodeId()),
                    List.of(exists.relationId()), null, null, SegmentValueSummaries.window(exists.window()),
                    null, null));
            collect(exists.predicate(), drafts);
            return;
        }
        if (predicate instanceof NotPredicate not) {
            drafts.add(new Draft(ExplanationClaimKind.LOGICAL_GROUP, List.of(), List.of(not.nodeId()), List.of(),
                    "NOT", null, null, null, null));
            collect(not.child(), drafts);
            return;
        }
        if (predicate instanceof GroupPredicate group) {
            drafts.add(new Draft(ExplanationClaimKind.LOGICAL_GROUP, List.of(), List.of(group.nodeId()), List.of(),
                    group.type().name(), null, null, null, null));
            for (Predicate child : group.children()) {
                collect(child, drafts);
            }
        }
    }

    /** One claim before its deterministic identifier is assigned. */
    private record Draft(
            ExplanationClaimKind kind,
            List<String> clauseIds,
            List<String> nodeIds,
            List<String> definitionIds,
            String operator,
            String valueSummary,
            String windowSummary,
            String previewStatisticPath,
            String previewStatisticSummary) {
    }
}