package com.company.campaign.evaluation.corpus;

import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.any;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attrNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.dateTime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.fixed;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.not;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;

/**
 * Category 8 — multiple conditions inside one EXISTS, and the same-row reading.
 *
 * <p>Each EXISTS scopes its conditions to one related row. The counterexample fixture splits the
 * conditions across different rows, so treating the conditions as independently satisfied returns a
 * different member set and the mistake becomes visible.
 */
final class Category08ExistsSameRow {

    private Category08ExistsSameRow() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("exists-single", "有已支付订单的会员")
                        .subcase("exists-single-condition")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("PAID"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-status", "order.status", EQUAL, enumValue("PAID"))))
                        .claims("CLAIM_HAS_PAID_ORDER")
                        .build(),
                c("exists-two-conditions", "有线上支付的订单的会员")
                        .subcase("exists-two-conditions-same-row")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MAY_SPLIT_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001", "m-004"), List.of("m-008"))
                        .slot("c1", "ONLINE_PAID_ORDER", "EXISTS", "线上支付的订单", text("线上"))
                        .sel("order.status", "order.channel")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")),
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")))))
                        .claims("CLAIM_ONLINE_PAID_ORDER")
                        .build(),
                c("exists-window", "最近7天有已支付订单的会员")
                        .subcase("exists-with-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_SCOPE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002"), List.of("m-009"))
                        .slot("c1", "RECENT_PAID_ORDER", "EXISTS", "最近7天", text("7"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", rolling(7, days()),
                                attr("n-status", "order.status", EQUAL, enumValue("PAID"))))
                        .claims("CLAIM_RECENT_PAID_ORDER")
                        .build(),
                c("exists-any-inside", "有已支付或已完成订单的会员")
                        .subcase("exists-with-any-inside")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ANY_CROSSES_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-001", "m-005"), List.of("m-011"))
                        .slot("c1", "SETTLED_ORDER", "EXISTS", "已支付或已完成", text("已支付或已完成"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                any("n-settled",
                                        attr("n-paid", "order.status", EQUAL, enumValue("PAID")),
                                        attr("n-completed", "order.status", EQUAL, enumValue("COMPLETED")))))
                        .claims("CLAIM_SETTLED_ORDER")
                        .build(),
                c("exists-refunded-amount", "有退款且金额超过100元的订单的会员")
                        .subcase("exists-same-row-amount-and-status")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MAY_SPLIT_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-003"), List.of("m-007"))
                        .slot("c1", "REFUNDED_ORDER", "EXISTS", "退款", text("退款"))
                        .slot("c2", "ORDER_AMOUNT", "GREATER_THAN", "超过100元", number(100))
                        .sel("order.status", "order.amount")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        attr("n-status", "order.status", EQUAL, enumValue("REFUNDED")),
                                        attr("n-amount", "order.amount", GREATER_THAN, money("100.00", "CNY")))))
                        .claims("CLAIM_REFUNDED_ABOVE_AMOUNT")
                        .build(),
                c("exists-null-operator", "有渠道记录的订单的会员")
                        .subcase("exists-null-operator")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_CHANNEL_MATCHES")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-002", "m-010"), List.of("m-012"))
                        .slot("c1", "ORDER_CHANNEL", "IS_NOT_NULL", "有渠道记录", null)
                        .sel("order.channel")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attrNull("n-channel", "order.channel", IS_NOT_NULL)))
                        .claims("CLAIM_ORDER_CHANNEL_PRESENT")
                        .build(),
                c("exists-lifetime-completed", "有过已完成订单的会员")
                        .subcase("exists-lifetime-completed")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-004"), List.of("m-012"))
                        .slot("c1", "COMPLETED_ORDER", "EXISTS", "有过已完成订单", text("已完成"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-status", "order.status", EQUAL, enumValue("COMPLETED"))))
                        .claims("CLAIM_EVER_COMPLETED")
                        .build(),
                c("exists-fixed", "2026年1月有已支付订单的会员")
                        .subcase("exists-with-fixed-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-005"), List.of("m-002"))
                        .slot("c1", "JANUARY_PAID_ORDER", "EXISTS", "2026年1月", text("2026"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders",
                                fixed("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00"),
                                attr("n-status", "order.status", EQUAL, enumValue("PAID"))))
                        .claims("CLAIM_JANUARY_PAID_ORDER")
                        .build(),
                c("exists-three-conditions", "有线上、已支付且金额不少于100元的订单的会员")
                        .subcase("exists-three-conditions")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MAY_SPLIT_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-004", "m-008"))
                        .slot("c1", "MATCHING_ORDER", "EXISTS", "线上、已支付", text("线上"))
                        .sel("order.status", "order.channel", "order.amount")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")),
                                        attr("n-amount", "order.amount", GREATER_OR_EQUAL,
                                                money("100.00", "CNY")))))
                        .claims("CLAIM_THREE_CONDITION_ORDER")
                        .build(),
                c("exists-amount-range", "有金额在100到500元之间的订单的会员")
                        .subcase("exists-amount-range")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_RANGE_CURRENCY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-002", "m-006"), List.of("m-010"))
                        .slot("c1", "ORDER_AMOUNT", "BETWEEN", "100到500元", number(100))
                        .sel("order.amount")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-amount", "order.amount", BETWEEN,
                                        range(money("100.00", "CNY"), money("500.00", "CNY"), true, true))))
                        .claims("CLAIM_ORDER_AMOUNT_RANGE")
                        .build(),
                c("exists-date", "有2026年9月之后支付订单的会员")
                        .subcase("exists-date-condition")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LOCAL_TIMEZONE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-003"), List.of("m-007"))
                        .slot("c1", "PAID_AT", "GREATER_THAN", "2026年9月之后", text("2026"))
                        .sel("order.paid_at")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-paid-at", "order.paid_at", GREATER_THAN,
                                        dateTime("2026-09-01T00:00:00+08:00"))))
                        .claims("CLAIM_PAID_AFTER_SEPTEMBER")
                        .build(),
                c("exists-negated-inside", "有未取消且金额超过50元的订单的会员")
                        .subcase("exists-with-negation-inside")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_DE_MORGAN_REWRITE_ALLOWED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-004"), List.of("m-009"))
                        .slot("c1", "ACTIVE_ORDER", "EXISTS", "未取消", text("未取消"))
                        .slot("c2", "ORDER_AMOUNT", "GREATER_THAN", "超过50元", number(50))
                        .sel("order.status", "order.amount")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        not("n-not-cancelled", attr("n-status", "order.status", EQUAL,
                                                enumValue("CANCELLED"))),
                                        attr("n-amount", "order.amount", GREATER_THAN, money("50.00", "CNY")))))
                        .claims("CLAIM_ACTIVE_ORDER_ABOVE_AMOUNT")
                        .build(),
                c("exists-same-row-counterexample", "有同一单既线上又已支付的会员")
                        .subcase("exists-same-row-counterexample")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MAY_SPLIT_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-005", "m-011"))
                        .slot("c1", "ONLINE_PAID_ORDER", "EXISTS", "同一单既线上又已支付", text("同一单"))
                        .sel("order.status", "order.channel")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_SAME_ROW_ONLINE_PAID")
                        .build(),
                c("exists-two-of-three", "有同时满足线上和金额不少于200元两个条件的订单的会员")
                        .subcase("exists-two-conditions-of-three")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MAY_SPLIT_ROWS")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-002"), List.of("m-007"))
                        .slot("c1", "MATCHING_ORDER", "EXISTS", "线上", text("线上"))
                        .slot("c2", "ORDER_AMOUNT", "GREATER_OR_EQUAL", "不少于200元", number(200))
                        .sel("order.channel", "order.amount")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                all("n-same-row",
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")),
                                        attr("n-amount", "order.amount", GREATER_OR_EQUAL,
                                                money("200.00", "CNY")))))
                        .claims("CLAIM_ONLINE_ABOVE_TWO_HUNDRED")
                        .build(),
                c("exists-in-group", "金卡会员，或者有已支付订单的会员")
                        .subcase("exists-inside-any-group")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_IS_OUTER_ANY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-001", "m-008"), List.of("m-012"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .sel("member.tier", "order.status")
                        .ast(any("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                exists("n-orders", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_TIER_OR_PAID_ORDER")
                        .build());
    }
}