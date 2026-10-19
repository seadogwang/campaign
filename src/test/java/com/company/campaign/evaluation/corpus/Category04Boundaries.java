package com.company.campaign.evaluation.corpus;

import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.date;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.range;
import static com.company.campaign.metadata.domain.ComparisonOperator.BETWEEN;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.LESS_THAN;

/**
 * Category 4 — ordering operators and BETWEEN inclusivity at exact boundaries.
 *
 * <p>Each case is paired with a counterexample fixture built so that an off-by-one inclusivity reading
 * returns a different member set. Where wording could be read either way, the forbidden assumption names
 * the wrong reading instead of leaving it implicit.
 */
final class Category04Boundaries {

    private Category04Boundaries() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("gt", "积分大于1000的会员")
                        .subcase("greater-than")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-003", "m-006"), List.of("m-005"))
                        .slot("c1", "LOYALTY_POINTS", "GREATER_THAN", "大于1000", number(1000))
                        .sel("member.loyalty_points")
                        .ast(attr("n-root", "member.loyalty_points", GREATER_THAN, integer(1000)))
                        .claims("CLAIM_POINTS_ABOVE_1000")
                        .build(),
                c("gte", "积分至少1000的会员")
                        .subcase("greater-or-equal")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-003", "m-005"), List.of("m-009"))
                        .slot("c1", "LOYALTY_POINTS", "GREATER_OR_EQUAL", "至少1000", number(1000))
                        .sel("member.loyalty_points")
                        .ast(attr("n-root", "member.loyalty_points", GREATER_OR_EQUAL, integer(1000)))
                        .claims("CLAIM_POINTS_AT_LEAST_1000")
                        .build(),
                c("lt", "订单数少于5的会员")
                        .subcase("less-than")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-004"), List.of("m-007"))
                        .slot("c1", "ORDER_COUNT", "LESS_THAN", "少于5", number(5))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", LESS_THAN, integer(5)))
                        .claims("CLAIM_ORDER_COUNT_UNDER_FIVE")
                        .build(),
                c("lte", "订单数最多5的会员")
                        .subcase("less-or-equal")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-007"), List.of("m-010"))
                        .slot("c1", "ORDER_COUNT", "LESS_OR_EQUAL", "最多5", number(5))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", LESS_OR_EQUAL, integer(5)))
                        .claims("CLAIM_ORDER_COUNT_AT_MOST_FIVE")
                        .build(),
                c("between-inclusive", "订单数在1到9之间（含两端）的会员")
                        .subcase("between-inclusive")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-001", "m-009"), List.of("m-011"))
                        .slot("c1", "ORDER_COUNT", "BETWEEN", "1到9", number(1))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", BETWEEN,
                                range(integer(1), integer(9), true, true)))
                        .claims("CLAIM_ORDER_COUNT_INCLUSIVE_RANGE")
                        .build(),
                c("between-exclusive", "订单数严格在1到9之间的会员")
                        .subcase("between-exclusive")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_BOUNDARY_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-003", "m-006"), List.of("m-001", "m-009"))
                        .slot("c1", "ORDER_COUNT", "BETWEEN", "严格在1到9之间", number(1))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", BETWEEN,
                                range(integer(1), integer(9), false, false)))
                        .claims("CLAIM_ORDER_COUNT_STRICT_RANGE")
                        .build(),
                c("between-half-open", "订单数在1到9之间（不含9）的会员")
                        .subcase("between-half-open")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-001", "m-006"), List.of("m-009"))
                        .slot("c1", "ORDER_COUNT", "BETWEEN", "1到9之间", number(1))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", BETWEEN,
                                range(integer(1), integer(9), true, false)))
                        .claims("CLAIM_ORDER_COUNT_HALF_OPEN_RANGE")
                        .build(),
                c("interval-gte-lt", "积分在1000以上但低于5000的会员")
                        .subcase("interval-lower-inclusive-upper-exclusive")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-003", "m-005"), List.of("m-006"))
                        .slot("c1", "LOYALTY_POINTS", "GREATER_OR_EQUAL", "1000以上", number(1000))
                        .slot("c2", "LOYALTY_POINTS", "LESS_THAN", "低于5000", number(5000))
                        .sel("member.loyalty_points")
                        .ast(all("n-root",
                                attr("n-lower", "member.loyalty_points", GREATER_OR_EQUAL, integer(1000)),
                                attr("n-upper", "member.loyalty_points", LESS_THAN, integer(5000))))
                        .claims("CLAIM_POINTS_HALF_OPEN_INTERVAL")
                        .build(),
                c("exact-equal", "订单数正好3的会员")
                        .subcase("exact-boundary-equality")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_NEAR_BOUNDARY_MATCHES")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-004"), List.of("m-003", "m-005"))
                        .slot("c1", "ORDER_COUNT", "EQUAL", "正好3", number(3))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", EQUAL, integer(3)))
                        .claims("CLAIM_ORDER_COUNT_EXACT_THREE")
                        .build(),
                c("gt-zero", "下过单的会员")
                        .subcase("greater-than-zero")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ZERO_ROWS_MATCH")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001", "m-004"), List.of("m-012"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "下过单", number(0))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", GREATER_THAN, integer(0)))
                        .claims("CLAIM_HAS_ANY_ORDER")
                        .build(),
                c("lte-max", "积分不超过10000的会员")
                        .subcase("less-or-equal-upper-max")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_UPPER_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-010"), List.of("m-011"))
                        .slot("c1", "LOYALTY_POINTS", "LESS_OR_EQUAL", "不超过10000", number(10000))
                        .sel("member.loyalty_points")
                        .ast(attr("n-root", "member.loyalty_points", LESS_OR_EQUAL, integer(10000)))
                        .claims("CLAIM_POINTS_AT_MOST_MAX")
                        .build(),
                c("single-point", "积分正好1000的会员")
                        .subcase("single-point-closed-range")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_EMPTY_RANGE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-005"), List.of("m-003", "m-006"))
                        .slot("c1", "LOYALTY_POINTS", "EQUAL", "正好1000", number(1000))
                        .sel("member.loyalty_points")
                        .ast(attr("n-root", "member.loyalty_points", BETWEEN,
                                range(integer(1000), integer(1000), true, true)))
                        .claims("CLAIM_POINTS_EXACTLY_1000")
                        .build(),
                c("gte-one", "至少有1单的会员")
                        .subcase("greater-or-equal-one")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ZERO_ROWS_MATCH")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EMPTY)
                        .members(List.of("m-001", "m-004"), List.of("m-012"))
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "至少有1单", number(1))
                        .sel("member.order_count")
                        .ast(attr("n-root", "member.order_count", GREATER_OR_EQUAL, integer(1)))
                        .claims("CLAIM_AT_LEAST_ONE_ORDER")
                        .build(),
                c("points-0-1000", "积分在0到1000之间的会员")
                        .subcase("lower-bound-zero")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ZERO_IS_MISSING")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-002", "m-004"), List.of("m-008"))
                        .slot("c1", "LOYALTY_POINTS", "BETWEEN", "0到1000", number(0))
                        .sel("member.loyalty_points")
                        .ast(attr("n-root", "member.loyalty_points", BETWEEN,
                                range(integer(0), integer(1000), true, true)))
                        .claims("CLAIM_POINTS_ZERO_TO_THOUSAND")
                        .build(),
                c("date-between", "2026年第一季度入会的会员")
                        .subcase("date-between-boundaries")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_QUARTER_END_EXCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_MEMBERS, CorpusV1Support.COUNTER_BOUNDARY)
                        .members(List.of("m-001", "m-003"), List.of("m-004"))
                        .slot("c1", "JOIN_DATE", "BETWEEN", "2026年第一季度", text("2026"))
                        .sel("member.join_date")
                        .ast(attr("n-root", "member.join_date", BETWEEN,
                                range(date("2026-01-01"), date("2026-03-31"), true, true)))
                        .claims("CLAIM_JOINED_FIRST_QUARTER")
                        .build());
    }
}