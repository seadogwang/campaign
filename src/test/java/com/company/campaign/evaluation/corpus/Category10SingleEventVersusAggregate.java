package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metricNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL;

/**
 * Category 10 — single-event versus aggregate metric ambiguity.
 *
 * <p>A latest-order amount is one event; a maximum or sum is an aggregate over rows. The ambiguous cases
 * ask which definition is meant instead of silently choosing one, and the resolved cases pin the
 * definition they selected and reject the plausible alternative.
 */
final class Category10SingleEventVersusAggregate {

    private Category10SingleEventVersusAggregate() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("latest-single-event", "最近一单消费超过100元的会员")
                        .subcase("single-event-latest")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_AGGREGATE_OVER_ALL_ORDERS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "GREATER_THAN", "最近一单", number(100))
                        .sel("order.latest_paid_amount").rej("order.max_paid_amount")
                        .ast(metric("n-root", "order.latest_paid_amount", lifetime(),
                                GREATER_THAN, money("100.00", "CNY")))
                        .claims("CLAIM_LATEST_ORDER_AMOUNT")
                        .build(),
                c("max-aggregate", "历史最大单笔消费超过500元的会员")
                        .subcase("aggregate-maximum")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_SINGLE_LATEST_EVENT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002"), List.of("m-008"))
                        .slot("c1", "MAX_ORDER_AMOUNT", "GREATER_THAN", "历史最大单笔", number(500))
                        .sel("order.max_paid_amount").rej("order.latest_paid_amount")
                        .ast(metric("n-root", "order.max_paid_amount", lifetime(),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_MAX_ORDER_AMOUNT")
                        .build(),
                c("ambiguity-zh", "消费金额超过500的会员")
                        .subcase("spend-definition-ambiguity")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_NET_PAID_SUM", "ASSUME_SINGLE_LATEST_EVENT")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "SPEND_AMOUNT", "AMBIGUOUS", "消费金额", number(500))
                        .sel("order.net_paid_amount").rej("order.gross_amount", "order.latest_paid_amount")
                        .claims("CLAIM_SPEND_DEFINITION_UNCERTAIN")
                        .build(),
                c("ambiguity-en", "members whose spend is over 500")
                        .subcase("spend-definition-ambiguity-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_NET_PAID_SUM", "ASSUME_SINGLE_LATEST_EVENT")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "SPEND_AMOUNT", "AMBIGUOUS", "spend", number(500))
                        .sel("order.net_paid_amount").rej("order.gross_amount", "order.latest_paid_amount")
                        .claims("CLAIM_SPEND_DEFINITION_UNCERTAIN")
                        .build(),
                c("single-event-window", "最近90天最近一单金额超过100元的会员")
                        .subcase("single-event-with-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_WINDOW_IS_LIFETIME", "ASSUME_AGGREGATE_OVER_ALL_ORDERS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-003"), List.of("m-009"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "GREATER_THAN", "最近90天", number(100))
                        .sel("order.latest_paid_amount").rej("order.max_paid_amount")
                        .ast(metric("n-root", "order.latest_paid_amount", rolling(90, days()),
                                GREATER_THAN, money("100.00", "CNY")))
                        .claims("CLAIM_RECENT_LATEST_AMOUNT")
                        .build(),
                c("aggregate-sum-window", "最近90天累计消费超过500元的会员")
                        .subcase("aggregate-sum-with-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_SINGLE_LATEST_EVENT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-004"), List.of("m-002"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "累计消费", number(500))
                        .sel("order.net_paid_amount").rej("order.latest_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", rolling(90, days()),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_RECENT_AGGREGATE_SPEND")
                        .build(),
                c("single-event-currency", "最近一单消费超过100美元的会员")
                        .subcase("single-event-with-currency")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_REPORTING_CURRENCY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-005"), List.of("m-001"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "GREATER_THAN", "美元", number(100))
                        .sel("order.latest_paid_amount").rej("order.avg_paid_amount")
                        .ast(metric("n-root", "order.latest_paid_amount", lifetime(),
                                GREATER_THAN, money("100.00", "USD")))
                        .claims("CLAIM_LATEST_USD")
                        .build(),
                c("ambiguity-order-amount", "订单金额超过300的会员")
                        .subcase("order-amount-definition-ambiguity")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_SINGLE_ORDER_AMOUNT", "ASSUME_AGGREGATE_SUM")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "ORDER_AMOUNT", "AMBIGUOUS", "订单金额", number(300))
                        .sel("order.gross_amount").rej("order.net_paid_amount", "order.max_paid_amount")
                        .claims("CLAIM_ORDER_AMOUNT_UNCERTAIN")
                        .build(),
                c("explicit-latest", "上一单消费超过200元的会员")
                        .subcase("explicit-latest-order")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_AGGREGATE_OVER_ALL_ORDERS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-006"), List.of("m-004"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "GREATER_THAN", "上一单", number(200))
                        .sel("order.latest_paid_amount").rej("order.max_paid_amount")
                        .ast(metric("n-root", "order.latest_paid_amount", lifetime(),
                                GREATER_THAN, money("200.00", "CNY")))
                        .claims("CLAIM_PREVIOUS_ORDER_AMOUNT")
                        .build(),
                c("explicit-total", "累计消费超过1000元的会员")
                        .subcase("explicit-aggregate-total")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_SINGLE_LATEST_EVENT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001", "m-008"), List.of("m-011"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "累计消费", number(1000))
                        .sel("order.net_paid_amount").rej("order.latest_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_LIFETIME_TOTAL")
                        .build(),
                c("ambiguity-recent-spend", "最近消费的会员")
                        .subcase("recent-spend-ambiguity")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_SINGLE_LATEST_EVENT", "ASSUME_RECENT_WINDOW_SUM")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "RECENT_SPEND", "AMBIGUOUS", "最近消费", text("最近"))
                        .sel("order.latest_paid_amount").rej("order.net_paid_amount")
                        .claims("CLAIM_RECENT_SPEND_UNCERTAIN")
                        .build(),
                c("single-event-equal", "最近一单金额正好199元的会员")
                        .subcase("single-event-exact-value")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_AGGREGATE_OVER_ALL_ORDERS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-007"), List.of("m-003"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "EQUAL", "正好199", number(199))
                        .sel("order.latest_paid_amount").rej("order.min_paid_amount")
                        .ast(metric("n-root", "order.latest_paid_amount", lifetime(), EQUAL,
                                money("199.00", "CNY")))
                        .claims("CLAIM_LATEST_EXACT")
                        .build(),
                c("aggregate-between", "最大单笔消费在100到500元之间的会员")
                        .subcase("aggregate-range")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_SINGLE_LATEST_EVENT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-010"), List.of("m-005"))
                        .slot("c1", "MAX_ORDER_AMOUNT", "BETWEEN", "100到500元", number(100))
                        .sel("order.max_paid_amount").rej("order.latest_paid_amount")
                        .ast(metric("n-root", "order.max_paid_amount", lifetime(), BETWEEN,
                                range(money("100.00", "CNY"), money("500.00", "CNY"), true, true)))
                        .claims("CLAIM_MAX_IN_RANGE")
                        .build(),
                c("ambiguity-multiple-candidates", "消费超过100的会员")
                        .subcase("multiple-spend-candidates")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_ANY_CANDIDATE", "ASSUME_GROSS_AMOUNT")
                        .ask("SELECT_SPEND_DEFINITION")
                        .slot("c1", "SPEND_AMOUNT", "AMBIGUOUS", "消费", number(100))
                        .sel("order.net_paid_amount").rej("order.gross_amount", "order.latest_paid_amount",
                                "order.avg_paid_amount")
                        .claims("CLAIM_MULTIPLE_SPEND_CANDIDATES")
                        .build(),
                c("single-event-null", "没有订单所以最近一单金额为空的会员")
                        .subcase("single-event-empty")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_LATEST_IS_ZERO")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-001"))
                        .slot("c1", "LATEST_ORDER_AMOUNT", "IS_NULL", "最近一单金额为空", null)
                        .sel("order.latest_paid_amount")
                        .ast(metricNull("n-root", "order.latest_paid_amount", lifetime(), IS_NULL))
                        .claims("CLAIM_LATEST_NULL")
                        .build());
    }
}