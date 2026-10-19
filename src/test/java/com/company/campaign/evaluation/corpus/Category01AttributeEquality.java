package com.company.campaign.evaluation.corpus;

import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.ENDS_WITH;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.IN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NULL;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.NOT_IN;
import static com.company.campaign.metadata.domain.ComparisonOperator.STARTS_WITH;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attrNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.bool;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.date;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.listOf;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.str;

/**
 * Category 1 — attribute equality, IN/NOT IN, ranges, string operators, enum and NULL-safe variants.
 *
 * <p>Fifteen distinct sub-cases across the operator families the category names, each with its own
 * utterance and grounded Intent slot. The boundary and range cases carry a counterexample fixture so a
 * wrong inclusivity reading yields a different member set.
 */
final class Category01AttributeEquality {

    private Category01AttributeEquality() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("eq-enum", "Gold会员")
                        .subcase("equality-enum")
                        .risk(RiskLevel.MEDIUM)
                        .slot("c1", "MEMBER_TIER", "EQUAL", "Gold", text("GOLD"))
                        .sel("member.tier").rej("member.status")
                        .ast(attr("n-tier", "member.tier", EQUAL,
                                enumValue("GOLD")))
                        .claims("CLAIM_TIER_EQUALS_GOLD")
                        .build(),
                c("neq-enum", "不是金卡的会员")
                        .subcase("inequality-enum")
                        .slot("c1", "MEMBER_TIER", "NOT_EQUAL", "不是金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", NOT_EQUAL,
                                enumValue("GOLD")))
                        .claims("CLAIM_TIER_NOT_GOLD")
                        .build(),
                c("in-enum", "金卡或银卡的会员")
                        .subcase("set-membership-enum")
                        .slot("c1", "MEMBER_TIER", "IN", "金卡或银卡", text("GOLD,SILVER"))
                        .sel("member.tier").rej("member.status")
                        .ast(attr("n-tier", "member.tier", IN,
                                listOf(enumValue("GOLD"), enumValue("SILVER"))))
                        .claims("CLAIM_TIER_IN_SET")
                        .build(),
                c("not-in-enum", "既不是金卡也不是银卡的会员")
                        .subcase("set-non-membership-enum")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_TIER_IS_EXCLUDED")
                        .slot("c1", "MEMBER_TIER", "NOT_IN", "不是金卡也不是银卡", text("GOLD,SILVER"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", NOT_IN,
                                listOf(enumValue("GOLD"), enumValue("SILVER"))))
                        .claims("CLAIM_TIER_NOT_IN_SET")
                        .build(),
                c("in-string", "上海或北京的会员")
                        .subcase("set-membership-string")
                        .slot("c1", "MEMBER_CITY", "IN", "上海或北京", text("上海,北京"))
                        .sel("member.city").rej("member.name")
                        .ast(attr("n-city", "member.city", IN,
                                listOf(str("上海"), str("北京"))))
                        .claims("CLAIM_CITY_IN_SET")
                        .build(),
                c("contains-string", "城市名包含“海”的会员")
                        .subcase("string-contains")
                        .slot("c1", "MEMBER_CITY", "CONTAINS", "海", text("海"))
                        .sel("member.city")
                        .ast(attr("n-city", "member.city", CONTAINS,
                                str("海")))
                        .claims("CLAIM_CITY_CONTAINS")
                        .build(),
                c("not-contains-string", "城市不含“县”的会员")
                        .subcase("string-not-contains")
                        .slot("c1", "MEMBER_CITY", "NOT_CONTAINS", "不含“县”", text("县"))
                        .sel("member.city")
                        .ast(attr("n-city", "member.city",
                                NOT_CONTAINS,
                                str("县")))
                        .claims("CLAIM_CITY_NOT_CONTAINS")
                        .build(),
                c("starts-with-name", "姓张的会员")
                        .subcase("string-prefix")
                        .slot("c1", "MEMBER_NAME", "STARTS_WITH", "姓张", text("张"))
                        .sel("member.name")
                        .ast(attr("n-name", "member.name", STARTS_WITH,
                                str("张")))
                        .claims("CLAIM_NAME_PREFIX")
                        .build(),
                c("ends-with-city", "城市以“州”结尾的会员")
                        .subcase("string-suffix")
                        .slot("c1", "MEMBER_CITY", "ENDS_WITH", "结尾", text("州"))
                        .sel("member.city")
                        .ast(attr("n-city", "member.city", ENDS_WITH,
                                str("州")))
                        .claims("CLAIM_CITY_SUFFIX")
                        .build(),
                c("between-int", "订单数在3到9之间的会员")
                        .subcase("range-inclusive-integer")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-001", "m-004", "m-009"), List.of("m-010", "m-012"))
                        .slot("c1", "ORDER_COUNT", "BETWEEN", "3到9", number(3))
                        .sel("member.order_count").rej("member.loyalty_points")
                        .ast(attr("n-count", "member.order_count",
                                BETWEEN,
                                range(integer(3), integer(9), true, true)))
                        .claims("CLAIM_ORDER_COUNT_BETWEEN")
                        .build(),
                c("range-open-upper", "积分在1000到5000之间但不含5000的会员")
                        .subcase("range-half-open-integer")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-005"), List.of("m-006"))
                        .slot("c1", "LOYALTY_POINTS", "BETWEEN", "1000到5000", number(1000))
                        .sel("member.loyalty_points").rej("member.order_count")
                        .ast(attr("n-points", "member.loyalty_points",
                                BETWEEN,
                                range(integer(1000), integer(5000), true, false)))
                        .claims("CLAIM_LOYALTY_RANGE")
                        .build(),
                c("is-null-enum", "等级为空的会员")
                        .subcase("null-safe-enum")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_MEANS_SILVER")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-007"), List.of())
                        .slot("c1", "MEMBER_TIER", "IS_NULL", "等级为空", null)
                        .sel("member.tier")
                        .ast(attrNull("n-tier", "member.tier",
                                IS_NULL))
                        .claims("CLAIM_TIER_IS_NULL")
                        .build(),
                c("is-not-null-enum", "有等级记录的会员")
                        .subcase("null-safe-enum-present")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NULL_IS_INCLUDED")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_NULL)
                        .members(List.of("m-001", "m-003", "m-008"), List.of("m-011"))
                        .slot("c1", "MEMBER_TIER", "IS_NOT_NULL", "有等级记录", null)
                        .sel("member.tier")
                        .ast(attrNull("n-tier", "member.tier",
                                IS_NOT_NULL))
                        .claims("CLAIM_TIER_IS_PRESENT")
                        .build(),
                c("bool-equal", "订阅了邮件的会员")
                        .subcase("boolean-equality")
                        .slot("c1", "EMAIL_OPT_IN", "EQUAL", "订阅了邮件", text("true"))
                        .sel("member.email_opt_in")
                        .ast(attr("n-opt-in", "member.email_opt_in",
                                EQUAL, bool(true)))
                        .claims("CLAIM_OPT_IN_TRUE")
                        .build(),
                c("date-equal", "2026-03-15入会的会员")
                        .subcase("date-equality")
                        .slot("c1", "JOIN_DATE", "EQUAL", "2026-03-15", text("2026-03-15"))
                        .sel("member.join_date")
                        .ast(attr("n-join", "member.join_date",
                                EQUAL,
                                date("2026-03-15")))
                        .claims("CLAIM_JOIN_DATE")
                        .build());
    }
}