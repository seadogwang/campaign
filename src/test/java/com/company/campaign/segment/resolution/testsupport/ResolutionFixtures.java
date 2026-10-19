package com.company.campaign.segment.resolution.testsupport;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.AggregateEmptySet;
import com.company.campaign.metadata.domain.AggregateFunction;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.EmptySetBehavior;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.NullSemantics;
import com.company.campaign.metadata.domain.RelationCardinality;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.company.campaign.metadata.domain.RelationJoinKind;
import com.company.campaign.metadata.domain.RelationScope;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.domain.TimeWindowKind;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.intent.IntentClause;
import com.company.campaign.segment.intent.IntentCurrency;
import com.company.campaign.segment.intent.IntentValue;
import com.company.campaign.segment.intent.IntentWindow;
import com.company.campaign.segment.intent.IntentWindowUnit;
import com.company.campaign.segment.intent.RegisteredDefaultSource;
import com.company.campaign.segment.intent.testsupport.IntentFixtures;
import com.company.campaign.segment.resolution.ResolutionPolicy;

import java.util.List;
import java.util.Map;

/**
 * Deterministic T10 resolver fixtures built on the immutable T04 Metadata fixtures. No clock, random value,
 * network or database is used.
 */
public final class ResolutionFixtures {

    public static final String DEFAULT_POLICY_VERSION = "resolution-policy/1.0";
    public static final String SPEND_CONCEPT = "SPEND";
    public static final String SPEND_DEFINITION_ID = "order.net_paid_amount";
    public static final String MEMBER_ORDERS_CONCEPT = "MEMBER_ORDERS";
    public static final String MEMBER_ORDERS_DEFINITION_ID = "member.orders";

    /** The standard policy mapping: the SPEND concept identifies the net-paid-amount metric exactly. */
    public static final Map<String, String> STANDARD_CONCEPT_MAPPINGS =
            Map.of(SPEND_CONCEPT, SPEND_DEFINITION_ID);

    private ResolutionFixtures() {
    }

    /** A policy with no registered reporting-currency default and the standard concept mapping. */
    public static ResolutionPolicy policy() {
        return ResolutionPolicy.publish(DEFAULT_POLICY_VERSION, null, STANDARD_CONCEPT_MAPPINGS);
    }

    /** A policy that consults the registered reporting-currency default and the standard concept mapping. */
    public static ResolutionPolicy policyWithCurrencyDefault() {
        return ResolutionPolicy.publish(
                DEFAULT_POLICY_VERSION, "reporting.currency", STANDARD_CONCEPT_MAPPINGS);
    }

    /** A policy with the given concept mapping and no reporting-currency default. */
    public static ResolutionPolicy policyWithConceptMapping(Map<String, String> conceptMappings) {
        return ResolutionPolicy.publish(DEFAULT_POLICY_VERSION, null, conceptMappings);
    }

    /** A policy with no concept mapping at all, so no clause can be grounded by concept alone. */
    public static ResolutionPolicy policyWithoutConceptMapping() {
        return ResolutionPolicy.publish(DEFAULT_POLICY_VERSION, null, Map.of());
    }

    /** The standard two-definition snapshot: one tier attribute and one spend metric. */
    public static MetadataSnapshot standardSnapshot() {
        return MetadataFixtures.standardSnapshot();
    }

    /** A snapshot with a second, semantically distinct spend metric. */
    public static MetadataSnapshot ambiguousSpendSnapshot() {
        return MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute()),
                List.of(
                        MetadataFixtures.netPaidAmountMetric(),
                        MetadataFixtures.aggregateMetric(
                                "order.gross_amount", SemanticDataType.MONEY, AggregateFunction.SUM,
                                AggregateEmptySet.zero(SemanticDataType.MONEY),
                                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED))),
                List.of(MetadataFixtures.ordersRelation()));
    }

    /** A snapshot with three semantically distinct spend metrics, so the option set has three entries. */
    public static MetadataSnapshot threeMetricSnapshot() {
        return MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute()),
                List.of(
                        MetadataFixtures.netPaidAmountMetric(),
                        MetadataFixtures.aggregateMetric(
                                "order.gross_amount", SemanticDataType.MONEY, AggregateFunction.SUM,
                                AggregateEmptySet.zero(SemanticDataType.MONEY),
                                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED)),
                        MetadataFixtures.aggregateMetric(
                                "order.settled_amount", SemanticDataType.MONEY, AggregateFunction.SUM,
                                AggregateEmptySet.zero(SemanticDataType.MONEY),
                                List.of(TimeWindowKind.ROLLING, TimeWindowKind.LIFETIME, TimeWindowKind.FIXED))),
                List.of(MetadataFixtures.ordersRelation()));
    }

    /** A snapshot with no attribute and no metric: every definition is absent from the permitted catalog. */
    public static MetadataSnapshot relationsOnlySnapshot() {
        return MetadataFixtures.snapshot(
                List.of(), List.of(), List.of(MetadataFixtures.ordersRelation()));
    }

    /** A second, semantically distinct relation over the same entities, with a distinct declared scope. */
    public static RelationDefinition memberProfileRelation() {
        return new RelationDefinition(
                "member.profile",
                "Member Profile",
                List.of("profile"),
                SubjectKind.MEMBER,
                MetadataFixtures.memberVersion(),
                SubjectKind.ORDER,
                MetadataFixtures.orderVersion(),
                RelationCardinality.ONE_TO_MANY,
                new RelationScope(RelationJoinKind.GLOBAL, null, null),
                null,
                NullSemantics.UNKNOWN_VALUE,
                EmptySetBehavior.NULL,
                List.of("member profile"),
                List.of("member orders"),
                null);
    }

    /** A snapshot holding two permitted relations and nothing else. */
    public static MetadataSnapshot twoRelationSnapshot() {
        return MetadataFixtures.snapshot(
                List.of(), List.of(), List.of(MetadataFixtures.ordersRelation(), memberProfileRelation()));
    }

    /** A snapshot with an attribute but no relation at all, so a relation clause has no permitted match. */
    public static MetadataSnapshot noRelationSnapshot() {
        return MetadataFixtures.snapshot(List.of(MetadataFixtures.tierAttribute()), List.of(), List.of());
    }

    public static RegisteredDefaultSource registry() {
        return IntentFixtures.currencyRegistry();
    }

    public static RegisteredDefaultSource emptyRegistry() {
        return IntentFixtures.emptyRegistry();
    }

    /** A spend clause whose every slot matches the standard net-paid-amount metric. */
    public static IntentClause spendClause() {
        return new IntentClause(
                "c2", "SPEND", MetadataKind.METRIC, false, ComparisonOperator.GREATER_THAN,
                new IntentValue.MoneyValue("1000"),
                new IntentWindow.Rolling(90, IntentWindowUnit.DAY),
                new IntentCurrency.Explicit("CNY"),
                AggregateFunction.SUM, null,
                IntentFixtures.span("过去90天消费超过1000元", 7, 21), List.of(), List.of());
    }

    /** A tier clause whose every slot matches the standard tier attribute. */
    public static IntentClause tierClause() {
        return new IntentClause(
                "c1", "MEMBER_TIER", MetadataKind.ATTRIBUTE, false, ComparisonOperator.EQUAL,
                new IntentValue.EnumValue("Gold"), null, null, null, null,
                IntentFixtures.span("Gold会员", 0, 6), List.of(), List.of());
    }

    /**
     * A spend clause whose span matches no display name, alias or example: it is grounded only by the
     * policy's exact concept mapping, never by structure alone.
     */
    public static IntentClause spendClauseWithUnrelatedSpan() {
        IntentClause spend = spendClause();
        return new IntentClause(
                spend.clauseId(), spend.concept(), spend.definitionKind(), spend.negated(),
                spend.operator(), spend.value(), spend.window(), spend.currency(), spend.aggregation(),
                spend.relationScope(), IntentFixtures.span("随便说的一句话", 0, 7),
                spend.candidateHints(), spend.assumptions());
    }

    /** A relation clause grounded by the relation's exact "订单" alias, carrying no other slot. */
    public static IntentClause relationClause() {
        return new IntentClause(
                "c3", MEMBER_ORDERS_CONCEPT, MetadataKind.RELATION, false, null, null, null, null, null, null,
                IntentFixtures.span("订单", 0, 2), List.of(), List.of());
    }

    /** A relation clause whose span matches nothing, so only a concept mapping or hint can ground it. */
    public static IntentClause relationClauseWithUnrelatedSpan() {
        return new IntentClause(
                "c3", MEMBER_ORDERS_CONCEPT, MetadataKind.RELATION, false, null, null, null, null, null, null,
                IntentFixtures.span("随便说的一句话", 0, 7), List.of(), List.of());
    }

    /** The exact declared scope of the standard orders relation. */
    public static RelationScope ordersRelationScope() {
        return new RelationScope(RelationJoinKind.CORRELATED_BY_SOURCE_KEY, "member.id", "order.member_id");
    }
}