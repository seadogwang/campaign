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
import static com.company.campaign.evaluation.corpus.CorpusV1Support.dateTime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.fixed;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.not;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;

/**
 * Category 9 — two EXISTS nodes that may match different related rows.
 *
 * <p>The counterexample fixture provides one row that satisfies both nodes, so an implementation that
 * accidentally merges the two nodes into a single same-row scope returns a different member set.
 */
final class Category09ExistsDifferentRows {

    private Category09ExistsDifferentRows() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("two-relations", "有已支付订单并且有登录事件的会员")
                        .subcase("two-exists-different-relations")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "LOGIN_EVENT", "EXISTS", "登录事件", text("登录"))
                        .sel("order.status", "event.type")
                        .ast(all("n-root",
                                exists("n-orders", "member.orders", lifetime(),
                                        attr("n-order-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-events", "member.events", lifetime(),
                                        attr("n-event-type", "event.type", EQUAL, enumValue("LOGIN")))))
                        .claims("CLAIM_ORDERS_AND_EVENTS")
                        .build(),
                c("same-relation-two-nodes", "既有已支付订单又有线上订单的会员")
                        .subcase("two-exists-same-relation")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001", "m-004"), List.of("m-008"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "ONLINE_ORDER", "EXISTS", "线上订单", text("线上"))
                        .sel("order.status", "order.channel")
                        .ast(all("n-root",
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-online", "member.orders", lifetime(),
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")))))
                        .claims("CLAIM_PAID_AND_ONLINE")
                        .build(),
                c("two-exists-lifetime", "有过退款订单也有过已完成订单的会员")
                        .subcase("two-exists-lifetime-windows")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-003"), List.of("m-002"))
                        .slot("c1", "REFUNDED_ORDER", "EXISTS", "退款订单", text("退款"))
                        .slot("c2", "COMPLETED_ORDER", "EXISTS", "已完成订单", text("已完成"))
                        .sel("order.status")
                        .ast(all("n-root",
                                exists("n-refunded", "member.orders", lifetime(),
                                        attr("n-refund-status", "order.status", EQUAL, enumValue("REFUNDED"))),
                                exists("n-completed", "member.orders", lifetime(),
                                        attr("n-complete-status", "order.status", EQUAL,
                                                enumValue("COMPLETED")))))
                        .claims("CLAIM_REFUNDED_AND_COMPLETED")
                        .build(),
                c("three-exists", "有已支付订单、有登录事件并且有高价值档案的会员")
                        .subcase("three-exists-nodes")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-009"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "VIP_PROFILE", "EXISTS", "高价值档案", text("高价值"))
                        .sel("order.status", "event.type", "account.segment")
                        .ast(all("n-root",
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-login", "member.events", lifetime(),
                                        attr("n-type", "event.type", EQUAL, enumValue("LOGIN"))),
                                exists("n-profile", "member.profile", lifetime(),
                                        attr("n-segment", "account.segment", EQUAL, enumValue("VIP")))))
                        .claims("CLAIM_THREE_EXISTS")
                        .build(),
                c("exists-windows-different", "最近7天有已支付订单、历史上有过退款的会员")
                        .subcase("exists-different-windows")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ONE_WINDOW_FOR_ALL_NODES")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-002"), List.of("m-005"))
                        .slot("c1", "RECENT_PAID", "EXISTS", "最近7天", text("7"))
                        .slot("c2", "REFUNDED", "EXISTS", "有过退款", text("退款"))
                        .sel("order.status")
                        .ast(all("n-root",
                                exists("n-recent-paid", "member.orders", rolling(7, days()),
                                        attr("n-recent-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-ever-refunded", "member.orders", lifetime(),
                                        attr("n-ever-status", "order.status", EQUAL, enumValue("REFUNDED")))))
                        .claims("CLAIM_RECENT_PAID_EVER_REFUNDED")
                        .build(),
                c("orders-and-profile", "有已支付订单并且有高价值档案的会员")
                        .subcase("exists-orders-and-profile")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-004"), List.of("m-006"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "VIP_PROFILE", "EXISTS", "高价值档案", text("高价值"))
                        .sel("order.status", "account.segment")
                        .ast(all("n-root",
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-profile", "member.profile", lifetime(),
                                        attr("n-segment", "account.segment", EQUAL, enumValue("VIP")))))
                        .claims("CLAIM_PAID_AND_VIP_PROFILE")
                        .build(),
                c("merge-counterexample", "既有线上订单又有已支付订单的会员")
                        .subcase("accidental-merge-counterexample")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001", "m-007"), List.of("m-010"))
                        .slot("c1", "ONLINE_ORDER", "EXISTS", "线上订单", text("线上"))
                        .slot("c2", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .sel("order.channel", "order.status")
                        .ast(all("n-root",
                                exists("n-online", "member.orders", lifetime(),
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE"))),
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_ONLINE_AND_PAID_SEPARATELY")
                        .build(),
                c("exists-and-not-exists", "有已支付订单但没有退款订单的会员")
                        .subcase("exists-and-not-exists")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_EXISTS_IS_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-002"), List.of("m-003"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "NO_REFUND", "NOT_EXISTS", "没有退款订单", text("没有退款"))
                        .sel("order.status")
                        .ast(all("n-root",
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID"))),
                                not("n-no-refund", exists("n-refunded", "member.orders", lifetime(),
                                        attr("n-refund-status", "order.status", EQUAL,
                                                enumValue("REFUNDED"))))))
                        .claims("CLAIM_PAID_WITHOUT_REFUND")
                        .build(),
                c("exists-in-any", "有已支付订单或者有登录事件的会员")
                        .subcase("exists-inside-any")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ANY_MERGES_NODES")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001", "m-005"), List.of("m-012"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .slot("c2", "LOGIN_EVENT", "EXISTS", "登录事件", text("登录"))
                        .sel("order.status", "event.type")
                        .ast(any("n-root",
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-login", "member.events", lifetime(),
                                        attr("n-type", "event.type", EQUAL, enumValue("LOGIN")))))
                        .claims("CLAIM_PAID_OR_LOGIN")
                        .build(),
                c("exists-two-amounts", "有超过100元的订单并且有超过500元的订单的会员")
                        .subcase("exists-two-amount-thresholds")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_THRESHOLDS_MERGE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-004"), List.of("m-008"))
                        .slot("c1", "ORDER_AMOUNT", "GREATER_THAN", "超过100元", number(100))
                        .slot("c2", "ORDER_AMOUNT", "GREATER_THAN", "超过500元", number(500))
                        .sel("order.amount")
                        .ast(all("n-root",
                                exists("n-over-hundred", "member.orders", lifetime(),
                                        attr("n-amount-low", "order.amount", GREATER_THAN,
                                                money("100.00", "CNY"))),
                                exists("n-over-five-hundred", "member.orders", lifetime(),
                                        attr("n-amount-high", "order.amount", GREATER_THAN,
                                                money("500.00", "CNY")))))
                        .claims("CLAIM_TWO_AMOUNT_THRESHOLDS")
                        .build(),
                c("exists-money-and-status", "有超过100元的订单并且有已支付订单的会员")
                        .subcase("exists-money-and-status")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CONDITIONS_MERGE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-002"), List.of("m-011"))
                        .slot("c1", "ORDER_AMOUNT", "GREATER_THAN", "超过100元", number(100))
                        .slot("c2", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .sel("order.amount", "order.status")
                        .ast(all("n-root",
                                exists("n-amount", "member.orders", lifetime(),
                                        attr("n-big", "order.amount", GREATER_THAN, money("100.00", "CNY"))),
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-paid-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_AMOUNT_AND_PAID_SEPARATELY")
                        .build(),
                c("exists-two-nested-groups", "有线上已支付订单且有登录或浏览事件的会员")
                        .subcase("exists-two-nested-groups")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "ONLINE_PAID_ORDER", "EXISTS", "线上已支付订单", text("线上"))
                        .slot("c2", "ENGAGEMENT_EVENT", "EXISTS", "登录或浏览", text("登录"))
                        .sel("order.status", "order.channel", "event.type")
                        .ast(all("n-root",
                                exists("n-orders", "member.orders", lifetime(),
                                        all("n-same-row",
                                                attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE")),
                                                attr("n-status", "order.status", EQUAL, enumValue("PAID")))),
                                exists("n-events", "member.events", lifetime(),
                                        any("n-engagement",
                                                attr("n-login", "event.type", EQUAL, enumValue("LOGIN")),
                                                attr("n-view", "event.type", EQUAL, enumValue("VIEW"))))))
                        .claims("CLAIM_ONLINE_PAID_AND_ENGAGED")
                        .build(),
                c("exists-date-and-status", "有2026年之后支付的订单并且有退款订单的会员")
                        .subcase("exists-date-and-status")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-003"), List.of("m-007"))
                        .slot("c1", "RECENT_PAYMENT", "EXISTS", "2026年之后", text("2026"))
                        .slot("c2", "REFUNDED_ORDER", "EXISTS", "退款订单", text("退款"))
                        .sel("order.status")
                        .ast(all("n-root",
                                exists("n-paid-at", "member.orders", lifetime(),
                                        attr("n-paid-date", "order.paid_at", GREATER_THAN,
                                                dateTime("2026-01-01T00:00:00+08:00"))),
                                exists("n-refunded", "member.orders", lifetime(),
                                        attr("n-refund-status", "order.status", EQUAL,
                                                enumValue("REFUNDED")))))
                        .claims("CLAIM_RECENT_PAYMENT_AND_REFUND")
                        .build(),
                c("two-exists-with-attribute", "金卡会员且有线上订单和已支付订单的会员")
                        .subcase("two-exists-with-outer-attribute")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_NODES_ARE_MERGED")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SPLIT_ROW)
                        .members(List.of("m-001"), List.of("m-004"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "ONLINE_ORDER", "EXISTS", "线上订单", text("线上"))
                        .slot("c3", "PAID_ORDER", "EXISTS", "已支付订单", text("已支付"))
                        .sel("member.tier", "order.channel", "order.status")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                exists("n-online", "member.orders", lifetime(),
                                        attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE"))),
                                exists("n-paid", "member.orders", lifetime(),
                                        attr("n-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_GOLD_ONLINE_PAID")
                        .build(),
                c("exists-lifetime-and-fixed", "历史上有已支付订单且2026年1月有订单的会员")
                        .subcase("exists-lifetime-and-fixed")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ONE_WINDOW_FOR_ALL_NODES")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-005"), List.of("m-002"))
                        .slot("c1", "EVER_PAID", "EXISTS", "历史上有已支付订单", text("历史上"))
                        .slot("c2", "JANUARY_ORDER", "EXISTS", "2026年1月有订单", text("2026"))
                        .sel("order.status")
                        .ast(all("n-root",
                                exists("n-ever-paid", "member.orders", lifetime(),
                                        attr("n-ever-status", "order.status", EQUAL, enumValue("PAID"))),
                                exists("n-january", "member.orders",
                                        fixed("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00"),
                                        attr("n-jan-status", "order.status", EQUAL, enumValue("PAID")))))
                        .claims("CLAIM_EVER_PAID_AND_JANUARY")
                        .build());
    }
}