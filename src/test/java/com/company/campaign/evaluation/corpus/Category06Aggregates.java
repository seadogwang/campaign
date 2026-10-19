package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.fixed;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metricNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_THAN;

/**
 * Category 6 — COUNT, SUM, AVG, MIN, MAX and zero-row behavior.
 *
 * <p>The zero-row and empty-aggregate cases are the point of the category: COUNT returns zero while
 * AVG/MIN/MAX return NULL. A counterexample fixture with an empty order set makes a mistaken reading
 * visible in the member set rather than hidden in an aggregate value.
 */
final class Category06Aggregates {

    private Category06Aggregates() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("count-gt", "累计订单数超过3的会员")
                        .subcase("count-greater-than")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001", "m-004"), List.of("m-012"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "超过3", number(3))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", lifetime(), GREATER_THAN, integer(3)))
                        .claims("CLAIM_COUNT_ABOVE_THREE")
                        .build(),
                c("count-zero", "没有任何订单的会员")
                        .subcase("count-zero-row")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_COUNT_IS_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-001"))
                        .slot("c1", "ORDER_COUNT", "EQUAL", "没有任何订单", number(0))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", lifetime(), EQUAL, integer(0)))
                        .claims("CLAIM_ZERO_ORDERS")
                        .build(),
                c("sum-gt", "累计消费超过1000元的会员")
                        .subcase("sum-greater-than")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-001", "m-002"), List.of("m-010"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "超过1000", number(1000))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_SUM_ABOVE_THOUSAND")
                        .build(),
                c("avg-lt", "平均客单价低于200元的会员")
                        .subcase("average-less-than")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_AVERAGE_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-003", "m-005"), List.of("m-012"))
                        .slot("c1", "AVG_SPEND", "LESS_THAN", "低于200", number(200))
                        .sel("order.avg_paid_amount")
                        .ast(metric("n-root", "order.avg_paid_amount", lifetime(),
                                LESS_THAN, money("200.00", "CNY")))
                        .claims("CLAIM_AVERAGE_UNDER_TWO_HUNDRED")
                        .build(),
                c("min-gte", "最小订单金额不低于10元的会员")
                        .subcase("minimum-greater-or-equal")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_MIN_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-002", "m-006"), List.of("m-012"))
                        .slot("c1", "MIN_SPEND", "GREATER_OR_EQUAL", "不低于10", number(10))
                        .sel("order.min_paid_amount")
                        .ast(metric("n-root", "order.min_paid_amount", lifetime(),
                                GREATER_OR_EQUAL, money("10.00", "CNY")))
                        .claims("CLAIM_MIN_AT_LEAST_TEN")
                        .build(),
                c("max-lte", "最大订单金额不超过999元的会员")
                        .subcase("maximum-less-or-equal")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001", "m-008"), List.of("m-012"))
                        .slot("c1", "MAX_SPEND", "LESS_OR_EQUAL", "不超过999", number(999))
                        .sel("order.max_paid_amount")
                        .ast(metric("n-root", "order.max_paid_amount", lifetime(),
                                LESS_OR_EQUAL, money("999.00", "CNY")))
                        .claims("CLAIM_MAX_AT_MOST_999")
                        .build(),
                c("count-between", "订单数在1到5之间的会员")
                        .subcase("count-between")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-004", "m-007"), List.of("m-012"))
                        .slot("c1", "ORDER_COUNT", "BETWEEN", "1到5", number(1))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", lifetime(), BETWEEN,
                                range(integer(1), integer(5), true, true)))
                        .claims("CLAIM_COUNT_IN_RANGE")
                        .build(),
                c("avg-null", "没有订单所以平均客单价为空的会员")
                        .subcase("average-empty-set-null")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_AVERAGE_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-001"))
                        .slot("c1", "AVG_SPEND", "IS_NULL", "平均客单价为空", null)
                        .sel("order.avg_paid_amount")
                        .ast(metricNull("n-root", "order.avg_paid_amount", lifetime(), IS_NULL))
                        .claims("CLAIM_AVERAGE_NULL")
                        .build(),
                c("min-null", "没有订单所以最低消费为空的会员")
                        .subcase("minimum-empty-set-null")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_MIN_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-002"))
                        .slot("c1", "MIN_SPEND", "IS_NULL", "最低消费为空", null)
                        .sel("order.min_paid_amount")
                        .ast(metricNull("n-root", "order.min_paid_amount", lifetime(), IS_NULL))
                        .claims("CLAIM_MIN_NULL")
                        .build(),
                c("max-null", "没有订单所以最高消费为空的会员")
                        .subcase("maximum-empty-set-null")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_MAX_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-003"))
                        .slot("c1", "MAX_SPEND", "IS_NULL", "最高消费为空", null)
                        .sel("order.max_paid_amount")
                        .ast(metricNull("n-root", "order.max_paid_amount", lifetime(), IS_NULL))
                        .claims("CLAIM_MAX_NULL")
                        .build(),
                c("sum-zero", "消费额为零的会员")
                        .subcase("sum-zero-empty-set")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_SUM_IS_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-001"))
                        .slot("c1", "NET_SPEND", "EQUAL", "消费额为零", number(0))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(), EQUAL,
                                money("0", "CNY")))
                        .claims("CLAIM_SUM_ZERO")
                        .build(),
                c("event-count-gt", "事件次数超过5的会员")
                        .subcase("event-count")
                        .fixtures(CorpusV1Support.PRIMARY_EVENTS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-002", "m-009"), List.of("m-012"))
                        .slot("c1", "EVENT_COUNT", "GREATER_THAN", "超过5", number(5))
                        .sel("event.count")
                        .ast(metric("n-root", "event.count", lifetime(), GREATER_THAN, integer(5)))
                        .claims("CLAIM_EVENT_COUNT_ABOVE_FIVE")
                        .build(),
                c("count-and-sum", "至少1单且累计消费超过500元的会员")
                        .subcase("count-and-sum")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ONE_AGGREGATE_ONLY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001"), List.of("m-012"))
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "至少1单", number(1))
                        .slot("c2", "NET_SPEND", "GREATER_THAN", "超过500", number(500))
                        .sel("order.count", "order.net_paid_amount")
                        .ast(all("n-root",
                                metric("n-count", "order.count", lifetime(), GREATER_OR_EQUAL, integer(1)),
                                metric("n-sum", "order.net_paid_amount", lifetime(),
                                        GREATER_THAN, money("500.00", "CNY"))))
                        .claims("CLAIM_COUNT_AND_SUM")
                        .build(),
                c("max-fixed", "2026年上半年单笔最高不超过500元的会员")
                        .subcase("maximum-fixed-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-005"), List.of("m-002"))
                        .slot("c1", "MAX_SPEND", "LESS_OR_EQUAL", "不超过500", number(500))
                        .sel("order.max_paid_amount")
                        .ast(metric("n-root", "order.max_paid_amount",
                                fixed("2026-01-01T00:00:00+08:00", "2026-07-01T00:00:00+08:00"),
                                LESS_OR_EQUAL, money("500.00", "CNY")))
                        .claims("CLAIM_HALF_YEAR_MAX")
                        .build(),
                c("avg-ambiguity", "平均消费的会员")
                        .subcase("average-definition-ambiguity")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_NET_PAID_AVERAGE", "ASSUME_GROSS_AVERAGE")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "AVG_SPEND", "AMBIGUOUS", "平均消费", null)
                        .sel("order.avg_paid_amount").rej("order.gross_amount")
                        .claims("CLAIM_AVERAGE_AMBIGUOUS")
                        .build());
    }
}