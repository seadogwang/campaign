package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
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
import static com.company.campaign.evaluation.corpus.CorpusV1Support.bool;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.not;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.str;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;

/**
 * Category 2 — AND, OR, nested groups and genuinely ambiguous precedence.
 *
 * <p>Twelve cases resolve to a specific grouping; three are deliberately ambiguous and must ask for the
 * grouping rather than pick one, which is why they carry question codes and forbidden assumptions.
 */
final class Category02BooleanGroups {

    private Category02BooleanGroups() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("and-two", "金卡会员并且订阅了邮件")
                        .subcase("and-flat")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-001"), List.of("m-004", "m-007"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "EMAIL_OPT_IN", "EQUAL", "订阅了邮件", text("true"))
                        .sel("member.tier", "member.email_opt_in")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true))))
                        .claims("CLAIM_AND_TIER_OPT_IN")
                        .build(),
                c("or-two", "金卡会员或者银卡会员")
                        .subcase("or-flat")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "MEMBER_TIER", "EQUAL", "银卡", text("SILVER"))
                        .sel("member.tier")
                        .ast(any("n-root",
                                attr("n-gold", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-silver", "member.tier", EQUAL, enumValue("SILVER"))))
                        .claims("CLAIM_OR_TIER")
                        .build(),
                c("and-nested-or", "城市为上海或北京的金卡会员")
                        .subcase("and-with-nested-or")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LEFT_TO_RIGHT_PRECEDENCE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-002"), List.of("m-005", "m-008"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "IN", "上海或北京", text("上海,北京"))
                        .sel("member.tier", "member.city")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                any("n-city",
                                        attr("n-sh", "member.city", EQUAL, str("上海")),
                                        attr("n-bj", "member.city", EQUAL, str("北京")))))
                        .claims("CLAIM_AND_NESTED_OR")
                        .build(),
                c("or-nested-and", "金卡会员，或者是上海的订阅会员")
                        .subcase("or-with-nested-and")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_FLAT_OR")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-003", "m-006"), List.of("m-009"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .sel("member.tier", "member.city", "member.email_opt_in")
                        .ast(any("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                all("n-shanghai-member",
                                        attr("n-city", "member.city", EQUAL, str("上海")),
                                        attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true)))))
                        .claims("CLAIM_OR_NESTED_AND")
                        .build(),
                c("triple-nest", "（金卡或银卡）并且（上海或北京）并且订阅了邮件")
                        .subcase("triple-nesting")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LEFT_TO_RIGHT_PRECEDENCE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-001", "m-008"), List.of("m-010"))
                        .slot("c1", "MEMBER_TIER", "IN", "金卡或银卡", text("GOLD,SILVER"))
                        .slot("c2", "MEMBER_CITY", "IN", "上海或北京", text("上海,北京"))
                        .sel("member.tier", "member.city", "member.email_opt_in")
                        .ast(all("n-root",
                                any("n-tier",
                                        attr("n-gold", "member.tier", EQUAL, enumValue("GOLD")),
                                        attr("n-silver", "member.tier", EQUAL, enumValue("SILVER"))),
                                any("n-city",
                                        attr("n-sh", "member.city", EQUAL, str("上海")),
                                        attr("n-bj", "member.city", EQUAL, str("北京"))),
                                attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true))))
                        .claims("CLAIM_TRIPLE_NESTING")
                        .build(),
                c("and-three-flat", "活跃、上海、订阅邮件三项都满足的会员")
                        .subcase("and-three")
                        .slot("c1", "MEMBER_STATUS", "EQUAL", "活跃", text("ACTIVE"))
                        .slot("c2", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .sel("member.status", "member.city", "member.email_opt_in")
                        .ast(all("n-root",
                                attr("n-status", "member.status", EQUAL, enumValue("ACTIVE")),
                                attr("n-city", "member.city", EQUAL, str("上海")),
                                attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true))))
                        .claims("CLAIM_AND_THREE")
                        .build(),
                c("or-three-flat", "金卡、银卡或白金卡会员")
                        .subcase("or-three")
                        .slot("c1", "MEMBER_TIER", "IN", "金卡、银卡或白金卡", text("GOLD,SILVER,PLATINUM"))
                        .sel("member.tier")
                        .ast(any("n-root",
                                attr("n-gold", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-silver", "member.tier", EQUAL, enumValue("SILVER")),
                                attr("n-platinum", "member.tier", EQUAL, enumValue("PLATINUM"))))
                        .claims("CLAIM_OR_THREE")
                        .build(),
                c("deep-mixed", "上海的活跃会员，或者是订阅了邮件的金卡会员")
                        .subcase("deep-mixed-nesting")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LEFT_TO_RIGHT_PRECEDENCE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-002", "m-004"), List.of("m-006", "m-011"))
                        .slot("c1", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .slot("c2", "MEMBER_STATUS", "EQUAL", "活跃", text("ACTIVE"))
                        .slot("c3", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .sel("member.city", "member.status", "member.tier", "member.email_opt_in")
                        .ast(any("n-root",
                                all("n-local-active",
                                        attr("n-city", "member.city", EQUAL, str("上海")),
                                        attr("n-status", "member.status", EQUAL, enumValue("ACTIVE"))),
                                all("n-subscribed-gold",
                                        attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true)),
                                        attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))))
                        .claims("CLAIM_DEEP_MIXED")
                        .build(),
                c("ambiguous-precedence-zh", "金卡会员并且上海或北京")
                        .subcase("ambiguous-precedence")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_LEFT_TO_RIGHT_PRECEDENCE", "ASSUME_AND_BINDS_TIGHTER")
                        .ask("SELECT_GROUPING")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "IN", "上海或北京", text("上海,北京"))
                        .claims("CLAIM_GROUPING_UNCERTAIN")
                        .build(),
                c("ambiguous-and-or-zh", "上海的活跃会员或者金卡会员")
                        .subcase("ambiguous-and-or")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_AND_BINDS_TIGHTER")
                        .ask("SELECT_GROUPING")
                        .slot("c1", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .slot("c2", "MEMBER_STATUS", "EQUAL", "活跃", text("活跃"))
                        .claims("CLAIM_GROUPING_UNCERTAIN")
                        .build(),
                c("ambiguous-en", "gold members or silver members and Shanghai")
                        .subcase("ambiguous-precedence-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_AND_BINDS_TIGHTER")
                        .ask("SELECT_GROUPING")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "gold", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "EQUAL", "Shanghai", text("上海"))
                        .claims("CLAIM_GROUPING_UNCERTAIN")
                        .build(),
                c("nested-not-in-group", "不是金卡但是在上海的会员")
                        .subcase("group-with-not")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_TIER_IS_EXCLUDED")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-003", "m-005"), List.of("m-009"))
                        .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "不是金卡", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .sel("member.tier", "member.city")
                        .ast(all("n-root",
                                not("n-not-gold", attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))),
                                attr("n-city", "member.city", EQUAL, str("上海"))))
                        .claims("CLAIM_AND_NOT_TIER")
                        .build(),
                c("or-with-exists", "金卡会员或者最近90天有订单的会员")
                        .subcase("or-with-exists")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EXISTS_IS_OUTER_OR")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001", "m-007"), List.of("m-012"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "RECENT_ORDER", "EXISTS", "最近90天有订单", text("90"))
                        .sel("member.tier", "order.count")
                        .ast(any("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                exists("n-orders", "member.orders", rolling(90, days()),
                                        metric("n-count", "order.count", rolling(90, days()),
                                                GREATER_THAN, integer(0)))))
                        .claims("CLAIM_OR_TIER_OR_RECENT_ORDER")
                        .build(),
                c("and-with-metric", "金卡会员并且最近90天订单数大于3")
                        .subcase("and-with-metric")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_WINDOW_IS_LIFETIME")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001"), List.of("m-004", "m-010"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "ORDER_COUNT", "GREATER_THAN", "订单数大于3", number(3))
                        .sel("member.tier", "order.count")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                metric("n-count", "order.count", rolling(90, days()), GREATER_THAN, integer(3))))
                        .claims("CLAIM_AND_TIER_AND_RECENT_COUNT")
                        .build(),
                c("all-with-null", "有城市记录且订阅了邮件的会员")
                        .subcase("and-with-null-safe")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_CITY_MATCHES")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-002", "m-006"), List.of("m-011"))
                        .slot("c1", "MEMBER_CITY", "IS_NOT_NULL", "有城市记录", null)
                        .slot("c2", "EMAIL_OPT_IN", "EQUAL", "订阅了邮件", text("true"))
                        .sel("member.city", "member.email_opt_in")
                        .ast(all("n-root",
                                attrNull("n-city", "member.city", IS_NOT_NULL),
                                attr("n-opt-in", "member.email_opt_in", EQUAL, bool(true))))
                        .claims("CLAIM_AND_CITY_PRESENT_OPT_IN")
                        .build());
    }
}