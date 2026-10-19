package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;

/**
 * Category 7 — explicit, missing and mixed currency, and conversion ambiguity.
 *
 * <p>Currency is never inferred from the reporting context and never silently summed across currencies.
 * A missing, mixed or convertible amount becomes a clarification or an unsupported request rather than a
 * guess, which is what the forbidden assumptions record.
 */
final class Category07Currency {

    private Category07Currency() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("explicit-cny", "人民币消费超过500元的会员")
                        .subcase("explicit-currency-cny")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_REPORTING_CURRENCY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-001"), List.of("m-008"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "人民币", text("CNY"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_CNY_SPEND")
                        .build(),
                c("explicit-usd", "美元消费超过100美元的会员")
                        .subcase("explicit-currency-usd")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_REPORTING_CURRENCY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-004"), List.of("m-001"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "美元", text("USD"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("100.00", "USD")))
                        .claims("CLAIM_USD_SPEND")
                        .build(),
                c("missing-currency", "消费超过500的会员")
                        .subcase("missing-currency-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_REPORTING_CURRENCY")
                        .ask("SELECT_CURRENCY")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "消费超过500", number(500))
                        .sel("order.net_paid_amount")
                        .claims("CLAIM_CURRENCY_MISSING")
                        .build(),
                c("mixed-currency", "混合币种消费超过500的会员")
                        .subcase("mixed-currency-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_MIXED_CURRENCIES_ARE_ADDABLE")
                        .ask("SELECT_CURRENCY_POLICY")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "混合币种", number(500))
                        .sel("order.net_paid_amount").rej("order.single_currency_amount")
                        .claims("CLAIM_MIXED_CURRENCY")
                        .build(),
                c("conversion-ambiguity", "折算成人民币后消费超过500的会员")
                        .subcase("conversion-policy-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_CONVERSION_RATE")
                        .ask("SELECT_CONVERSION_POLICY")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "折算成人民币", number(500))
                        .sel("order.net_paid_amount")
                        .claims("CLAIM_CONVERSION_UNCERTAIN")
                        .build(),
                c("single-currency-explicit", "单币种人民币消费超过1000元的会员")
                        .subcase("single-currency-metric")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONVERSION_ALLOWED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-002"), List.of("m-009"))
                        .slot("c1", "SINGLE_CURRENCY_SPEND", "GREATER_THAN", "单币种", number(1000))
                        .sel("order.single_currency_amount")
                        .ast(metric("n-root", "order.single_currency_amount", lifetime(),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_SINGLE_CURRENCY_SPEND")
                        .build(),
                c("attribute-currency-missing", "订单金额超过100的会员")
                        .subcase("attribute-currency-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_REPORTING_CURRENCY")
                        .ask("SELECT_CURRENCY")
                        .slot("c1", "ORDER_AMOUNT", "GREATER_THAN", "订单金额", number(100))
                        .sel("order.amount")
                        .claims("CLAIM_ORDER_AMOUNT_CURRENCY_MISSING")
                        .build(),
                c("money-range-cny", "消费在100到500元之间的会员")
                        .subcase("money-range-cny")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_RANGE_CURRENCY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-003"), List.of("m-007"))
                        .slot("c1", "NET_SPEND", "BETWEEN", "100到500元", number(100))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(), BETWEEN,
                                range(money("100.00", "CNY"), money("500.00", "CNY"), true, true)))
                        .claims("CLAIM_CNY_RANGE")
                        .build(),
                c("money-between-open", "消费高于100元但低于500元的会员")
                        .subcase("money-half-open")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-005"), List.of("m-002"))
                        .slot("c1", "NET_SPEND", "BETWEEN", "高于100元", number(100))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(), BETWEEN,
                                range(money("100.00", "CNY"), money("500.00", "CNY"), false, false)))
                        .claims("CLAIM_CNY_STRICT_RANGE")
                        .build(),
                c("mixed-currency-unsupported", "把美元订单和人民币订单金额直接相加的会员")
                        .subcase("mixed-currency-unsupported")
                        .risk(RiskLevel.HIGH).disposition(Disposition.UNSUPPORTED)
                        .forbid("ASSUME_CROSS_CURRENCY_ADDITION")
                        .slot("c1", "CROSS_CURRENCY_SUM", "UNSUPPORTED", "直接相加", text("相加"))
                        .claims("CLAIM_CROSS_CURRENCY_UNSUPPORTED")
                        .build(),
                c("currency-sum", "人民币累计消费超过100元的会员")
                        .subcase("currency-with-sum")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-001", "m-006"), List.of("m-010"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "人民币", text("CNY"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("100.00", "CNY")))
                        .claims("CLAIM_CNY_SUM")
                        .build(),
                c("range-mixed-currency", "消费介于100元到200美元之间的会员")
                        .subcase("range-with-mixed-currency")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_RANGE_CURRENCY", "ASSUME_CROSS_CURRENCY_COMPARISON")
                        .ask("SELECT_CURRENCY")
                        .slot("c1", "NET_SPEND", "BETWEEN", "100元到200美元", number(100))
                        .sel("order.net_paid_amount")
                        .claims("CLAIM_RANGE_CURRENCY_MISMATCH")
                        .build(),
                c("zero-amount-cny", "人民币消费为零的会员")
                        .subcase("zero-amount-explicit-currency")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_SUM_IS_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-012"), List.of("m-001"))
                        .slot("c1", "NET_SPEND", "EQUAL", "消费为零", number(0))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(), EQUAL,
                                money("0", "CNY")))
                        .claims("CLAIM_CNY_ZERO")
                        .build(),
                c("lifetime-currency", "人民币累计消费超过1000元的会员")
                        .subcase("lifetime-explicit-currency")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-002", "m-004"), List.of("m-009"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "累计消费", number(1000))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_CNY_LIFETIME")
                        .build(),
                c("currency-en", "members who spent over $100")
                        .subcase("ambiguous-currency-symbol-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_DOLLAR_MEANS_USD")
                        .ask("SELECT_CURRENCY")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "$100", number(100))
                        .sel("order.net_paid_amount")
                        .claims("CLAIM_CURRENCY_SYMBOL_AMBIGUOUS")
                        .build());
    }
}