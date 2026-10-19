package com.company.campaign.evaluation.corpus;

import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attrNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.date;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.listOf;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.not;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.str;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL;

/**
 * Category 3 — unary NOT, NOT IN, NULL exclusion and negative range wording.
 *
 * <p>Every case states which NULL reading it refuses to make. A negative comparison is explicitly not
 * NULL-inclusive here: {@code NOT_EQUAL} means the value differs, not that NULL also passes.
 */
final class Category03NotNullNegative {

    private Category03NotNullNegative() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("not-equal", "非金卡会员")
                        .subcase("not-equal")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_EQUAL_INCLUDES_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-002", "m-005"), List.of("m-009"))
                        .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "非金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(not("n-root", attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))))
                        .claims("CLAIM_NOT_GOLD")
                        .build(),
                c("not-in", "不在金卡、银卡名单里的会员")
                        .subcase("not-in")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_IN_INCLUDES_NULL")
                        .slot("c1", "MEMBER_TIER", "NOT_IN", "不在金卡、银卡名单里", text("GOLD,SILVER"))
                        .sel("member.tier")
                        .ast(not("n-root", attr("n-tier", "member.tier", IN,
                                listOf(enumValue("GOLD"), enumValue("SILVER")))))
                        .claims("CLAIM_NOT_IN_TIERS")
                        .build(),
                c("not-group-and", "不是上海的金卡会员")
                        .subcase("not-of-group")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_DE_MORGAN_REWRITE_ALLOWED")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_GROUPING)
                        .members(List.of("m-003", "m-004"), List.of("m-008"))
                        .slot("c1", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .slot("c2", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .sel("member.city", "member.tier")
                        .ast(not("n-root", all("n-shanghai-gold",
                                attr("n-city", "member.city", EQUAL, str("上海")),
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))))
                        .claims("CLAIM_NOT_SHANGHAI_GOLD")
                        .build(),
                c("not-exists", "最近30天没有订单的会员")
                        .subcase("not-exists")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_RELATION_IS_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-006", "m-012"), List.of("m-001"))
                        .slot("c1", "RECENT_ORDER", "NOT_EXISTS", "最近30天没有订单", text("30"))
                        .sel("order.count")
                        .ast(not("n-root", exists("n-orders", "member.orders", rolling(30, days()),
                                metric("n-count", "order.count", rolling(30, days()), GREATER_THAN, integer(0)))))
                        .claims("CLAIM_NOT_RECENT_ORDERS")
                        .build(),
                c("not-metric", "最近90天订单数不大于3的会员")
                        .subcase("not-metric-comparison")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NEGATION_FLIPS_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-005"), List.of("m-001"))
                        .slot("c1", "ORDER_COUNT", "NOT_GREATER_THAN", "订单数不大于3", number(3))
                        .sel("order.count")
                        .ast(not("n-root", metric("n-count", "order.count", rolling(90, days()),
                                GREATER_THAN, integer(3))))
                        .claims("CLAIM_NOT_RECENT_COUNT")
                        .build(),
                c("not-in-string", "城市不在上海、北京之列的会员")
                        .subcase("not-in-string")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_IN_INCLUDES_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-003", "m-007"), List.of("m-010"))
                        .slot("c1", "MEMBER_CITY", "NOT_IN", "不在上海、北京之列", text("上海,北京"))
                        .sel("member.city")
                        .ast(not("n-root", attr("n-city", "member.city", IN,
                                listOf(str("上海"), str("北京")))))
                        .claims("CLAIM_NOT_IN_CITIES")
                        .build(),
                c("double-not", "不是非金卡的会员")
                        .subcase("double-negation")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_DOUBLE_NEGATION_IS_UNARY")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "非金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(not("n-root", not("n-inner",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))))
                        .claims("CLAIM_DOUBLE_NEGATION")
                        .build(),
                c("not-null", "等级不为空的会员")
                        .subcase("negated-null-test")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_MEANS_SILVER")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-001", "m-004"), List.of("m-008"))
                        .slot("c1", "MEMBER_TIER", "IS_NOT_NULL", "等级不为空", null)
                        .sel("member.tier")
                        .ast(not("n-root", attrNull("n-tier", "member.tier", IS_NULL)))
                        .claims("CLAIM_NOT_NULL_TIER")
                        .build(),
                c("not-between", "积分不在1000到5000之间的会员")
                        .subcase("negated-range")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NEGATED_RANGE_IS_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-011"), List.of("m-005"))
                        .slot("c1", "LOYALTY_POINTS", "NOT_BETWEEN", "不在1000到5000之间", number(1000))
                        .sel("member.loyalty_points")
                        .ast(not("n-root", attr("n-points", "member.loyalty_points", BETWEEN,
                                range(integer(1000), integer(5000), true, true))))
                        .claims("CLAIM_NOT_IN_POINTS_RANGE")
                        .build(),
                c("not-gte", "最近90天消费额不到500元的会员")
                        .subcase("negated-lower-bound")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NEGATION_FLIPS_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-003", "m-009"), List.of("m-001"))
                        .slot("c1", "NET_SPEND", "LESS_THAN", "不到500元", number(500))
                        .sel("order.net_paid_amount")
                        .ast(not("n-root", metric("n-spend", "order.net_paid_amount", rolling(90, days()),
                                GREATER_OR_EQUAL, money("500.00", "CNY"))))
                        .claims("CLAIM_NOT_AT_LEAST_FIVE_HUNDRED")
                        .build(),
                c("not-contains", "城市名不含“州”的会员")
                        .subcase("negated-contains")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_CONTAINS_INCLUDES_NULL")
                        .slot("c1", "MEMBER_CITY", "NOT_CONTAINS", "不含“州”", text("州"))
                        .sel("member.city")
                        .ast(not("n-root", attr("n-city", "member.city", CONTAINS, str("州"))))
                        .claims("CLAIM_NOT_CONTAINS_ZHOU")
                        .build(),
                c("not-null-and-not-equal", "有等级记录但不是金卡的会员")
                        .subcase("null-exclusion-with-negative-equality")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_EQUAL_INCLUDES_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-002", "m-006"), List.of("m-010"))
                        .slot("c1", "MEMBER_TIER", "IS_NOT_NULL", "有等级记录", null)
                        .slot("c2", "MEMBER_TIER", "NOT_EQUAL", "不是金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(all("n-root",
                                not("n-present", attrNull("n-tier-null", "member.tier", IS_NULL)),
                                not("n-gold", attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))))
                        .claims("CLAIM_PRESENT_AND_NOT_GOLD")
                        .build(),
                c("not-integer-in", "订单数不是1、2、3的会员")
                        .subcase("negated-integer-set")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_IN_INCLUDES_NULL")
                        .slot("c1", "ORDER_COUNT", "NOT_IN", "不是1、2、3", text("1,2,3"))
                        .sel("member.order_count")
                        .ast(not("n-root", attr("n-count", "member.order_count", IN,
                                listOf(integer(1), integer(2), integer(3)))))
                        .claims("CLAIM_NOT_SMALL_ORDER_COUNTS")
                        .build(),
                c("not-date-range", "入会时间不在2026-01-01到2026-06-30之间的会员")
                        .subcase("negated-date-range")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NEGATED_RANGE_IS_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-004", "m-007"), List.of("m-002"))
                        .slot("c1", "JOIN_DATE", "NOT_BETWEEN", "不在2026-01-01到2026-06-30之间",
                                text("2026-01-01"))
                        .sel("member.join_date")
                        .ast(not("n-root", attr("n-join", "member.join_date", BETWEEN,
                                range(date("2026-01-01"), date("2026-06-30"), true, true))))
                        .claims("CLAIM_NOT_IN_JOIN_RANGE")
                        .build(),
                c("not-eq-en", "members who are not gold tier")
                        .subcase("not-equal-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NOT_EQUAL_INCLUDES_NULL")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-003", "m-008"), List.of("m-011"))
                        .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "not gold tier", text("GOLD"))
                        .sel("member.tier")
                        .ast(not("n-root", attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))))
                        .claims("CLAIM_NOT_GOLD_EN")
                        .build());
    }
}