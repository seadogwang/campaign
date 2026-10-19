package com.company.campaign.query;

import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.query.ast.QueryAst;
import com.company.campaign.query.ast.QueryAstCodec;
import com.company.campaign.query.plan.ResolvedDependency;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.SemanticQueryPlanCodec;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 4-8: the AST preserves the exact predicate tree — boolean grouping, EXISTS scopes, NOT /
 * NOT_EQUAL / IS_NULL distinctions and aggregate/empty-set semantics — and never rewrites any of them.
 */
class QueryAstSemanticsTest {

    private static final QueryAstCodec AST_CODEC = new QueryAstCodec();
    private static final SemanticQueryPlanCodec PLAN_CODEC = new SemanticQueryPlanCodec();

    @Test
    void booleanGroupingIsPreservedAndChangesTheHash() {
        var a = SegmentValidationFixtures.tierEquals("n-a", "GOLD");
        var b = SegmentValidationFixtures.attribute("n-b", "member.name", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("alice")));
        var c = SegmentValidationFixtures.attribute("n-c", "member.age", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(18)));

        SemanticQueryPlan andOfOr = QueryPlanFixtures.plan(
                SegmentValidationFixtures.all("n-root", List.of(a, SegmentValidationFixtures.any("n-or", List.of(b, c)))));
        SemanticQueryPlan orOfAnd = QueryPlanFixtures.plan(
                SegmentValidationFixtures.any("n-root", List.of(SegmentValidationFixtures.all("n-and", List.of(a, b)), c)));

        assertThat(andOfOr.predicateRoot()).isNotEqualTo(orOfAnd.predicateRoot());
        assertThat(andOfOr.planHash()).isNotEqualTo(orOfAnd.planHash());
        assertThat(PLAN_CODEC.write(andOfOr)).isNotEqualTo(PLAN_CODEC.write(orOfAnd));

        QueryAst.Group inner = (QueryAst.Group) ((QueryAst.Group) andOfOr.predicateRoot()).children().get(1);
        assertThat(inner.kind()).isEqualTo(QueryAst.GroupKind.ANY);
        assertThat(inner.children()).hasSize(2);

        QueryAst.Group innerAll = (QueryAst.Group) ((QueryAst.Group) orOfAnd.predicateRoot()).children().get(0);
        assertThat(innerAll.kind()).isEqualTo(QueryAst.GroupKind.ALL);
    }

    @Test
    void oneExistsWithTwoPredicatesIsDistinctFromTwoExistsNodesOverTheSameRelation() {
        var amount = SegmentValidationFixtures.attribute("n-amount", "order.amount", ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("10", "CNY")));
        var channel = SegmentValidationFixtures.attribute("n-channel", "order.channel", ComparisonOperator.EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.enumValue("ONLINE")));

        SemanticQueryPlan oneScope = QueryPlanFixtures.plan(SegmentValidationFixtures.exists(
                "n-exists", "member.orders", SegmentValidationFixtures.lifetime(),
                SegmentValidationFixtures.all("n-kids", List.of(amount, channel))));
        SemanticQueryPlan twoScopes = QueryPlanFixtures.plan(SegmentValidationFixtures.all("n-root", List.of(
                SegmentValidationFixtures.exists("n-e1", "member.orders", SegmentValidationFixtures.lifetime(), amount),
                SegmentValidationFixtures.exists("n-e2", "member.orders", SegmentValidationFixtures.lifetime(), channel))));

        assertThat(QueryPlanFixtures.countNodes(oneScope.predicateRoot(), QueryAst.Exists.class)).isEqualTo(1);
        assertThat(QueryPlanFixtures.countNodes(twoScopes.predicateRoot(), QueryAst.Exists.class)).isEqualTo(2);
        assertThat(oneScope.predicateRoot()).isNotEqualTo(twoScopes.predicateRoot());
        assertThat(oneScope.planHash()).isNotEqualTo(twoScopes.planHash());

        // One EXISTS keeps exactly one relation dependency even though it appears twice in the other document.
        long relations = oneScope.dependencies().stream()
                .filter(ResolvedDependency.RelationDependency.class::isInstance)
                .count();
        assertThat(relations).isEqualTo(1);
    }

    @Test
    void notNotEqualAndIsNullRemainStructurallyDistinct() {
        var negatedEqual = QueryPlanFixtures.plan(SegmentValidationFixtures.not("n-1",
                SegmentValidationFixtures.attribute("n-eq", "member.name", ComparisonOperator.EQUAL,
                        SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x")))));
        var notEqual = QueryPlanFixtures.plan(SegmentValidationFixtures.attribute("n-2", "member.name",
                ComparisonOperator.NOT_EQUAL,
                SegmentValidationFixtures.value(SegmentValidationFixtures.stringValue("x"))));
        var isNull = QueryPlanFixtures.plan(QueryPlanFixtures.attributeNoValue(
                "n-3", "member.name", ComparisonOperator.IS_NULL));

        assertThat(negatedEqual.predicateRoot()).isInstanceOf(QueryAst.Negation.class);
        assertThat(notEqual.predicateRoot()).isInstanceOf(QueryAst.Attribute.class);
        assertThat(((QueryAst.Attribute) notEqual.predicateRoot()).operator())
                .isEqualTo(ComparisonOperator.NOT_EQUAL);

        QueryAst.Attribute nullAttribute = (QueryAst.Attribute) isNull.predicateRoot();
        assertThat(nullAttribute.operator()).isEqualTo(ComparisonOperator.IS_NULL);
        assertThat(nullAttribute.operand()).isNull();

        assertThat(negatedEqual.predicateRoot()).isNotEqualTo(notEqual.predicateRoot());
        assertThat(notEqual.predicateRoot()).isNotEqualTo(isNull.predicateRoot());
        assertThat(negatedEqual.planHash()).isNotEqualTo(notEqual.planHash());
        assertThat(notEqual.planHash()).isNotEqualTo(isNull.planHash());
    }

    @Test
    void countWithZeroEmptySetSemanticsStaysAnAggregateOverItsExactRelation() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-count", "order.count", SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.integerValue(0))));

        assertThat(plan.predicateRoot()).isInstanceOf(QueryAst.Metric.class);
        assertThat(QueryPlanFixtures.countNodes(plan.predicateRoot(), QueryAst.Exists.class)).isZero();

        ResolvedDependency.MetricDependency metric = plan.dependencies().stream()
                .filter(ResolvedDependency.MetricDependency.class::isInstance)
                .map(ResolvedDependency.MetricDependency.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(metric.definition().aggregation()).isEqualTo(AggregateFunction.COUNT);
        assertThat(metric.definition().emptySetBehavior()).isEqualTo(EmptySetBehavior.ZERO);
        assertThat(metric.definition().emptySetValue()).isNotNull();
        assertThat(metric.definition().emptySetValue().canonicalValue()).isEqualTo("0");
        assertThat(metric.relationId()).isEqualTo("member.orders");
        assertThat(plan.dependencies().stream()
                .anyMatch(ResolvedDependency.RelationDependency.class::isInstance)).isTrue();
    }

    @Test
    void aNullEmptySetBehaviorIsPreservedWithoutAnInventedValue() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.metric(
                "n-avg", "order.avg_amount", SegmentValidationFixtures.lifetime(), ComparisonOperator.GREATER_THAN,
                SegmentValidationFixtures.value(SegmentValidationFixtures.moneyValue("1", "CNY"))));

        ResolvedDependency.MetricDependency metric = plan.dependencies().stream()
                .filter(ResolvedDependency.MetricDependency.class::isInstance)
                .map(ResolvedDependency.MetricDependency.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(metric.definition().aggregation()).isEqualTo(AggregateFunction.AVG);
        assertThat(metric.definition().emptySetBehavior()).isEqualTo(EmptySetBehavior.NULL);
        assertThat(metric.definition().emptySetValue()).isNull();
        assertThat(metric.refundTreatment()).isEqualTo(com.company.campaign.metadata.domain.RefundTreatment.IGNORE);
    }

    @Test
    void theAstIsTheSingleSourceOfThePredicateStructure() {
        SemanticQueryPlan plan = QueryPlanFixtures.plan(SegmentValidationFixtures.validDocument());
        assertThat(AST_CODEC.hash(plan.predicateRoot())).isNotNull();
        assertThat(AST_CODEC.write(plan.predicateRoot()))
                .isEqualTo(new QueryAstCodec().write(plan.predicateRoot()));
    }
}