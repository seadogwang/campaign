package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.fixed;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.weeks;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;

/**
 * Category 5 — ROLLING, LIFETIME and FIXED windows, timezone offsets and exact boundaries.
 *
 * <p>Windows are never inferred: a metric over a bounded window without a stated window asks instead. The
 * counterexample fixture is evaluated at a different instant, so a wrong window reading changes the set.
 */
final class Category05TimeWindows {

    private Category05TimeWindows() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("rolling-90-day", "最近90天消费超过500元的会员")
                        .subcase("rolling-90-day")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001", "m-004"), List.of("m-009"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "最近90天", text("90"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", rolling(90, days()),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_RECENT_SPEND")
                        .build(),
                c("rolling-30-day", "最近30天至少下过一单的会员")
                        .subcase("rolling-30-day-count")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-006"), List.of("m-010"))
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "最近30天", number(1))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", rolling(30, days()),
                                GREATER_OR_EQUAL, integer(1)))
                        .claims("CLAIM_RECENT_ORDER_EXISTS")
                        .build(),
                c("rolling-1-week", "最近一周有订单的会员")
                        .subcase("rolling-1-week")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CALENDAR_WEEK")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-003", "m-011"), List.of("m-005"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "最近一周", number(0))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", rolling(1, weeks()),
                                GREATER_THAN, integer(0)))
                        .claims("CLAIM_LAST_WEEK_ORDER")
                        .build(),
                c("rolling-4-week", "最近4周消费超过1000元的会员")
                        .subcase("rolling-4-week")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_MONTH")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-008"), List.of("m-012"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "最近4周", text("4"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", rolling(4, weeks()),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_FOUR_WEEK_SPEND")
                        .build(),
                c("lifetime", "累计消费超过10000元的会员")
                        .subcase("lifetime-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "累计", text("10000"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("10000.00", "CNY")))
                        .claims("CLAIM_LIFETIME_SPEND")
                        .build(),
                c("fixed-window", "2026年1月下单的会员")
                        .subcase("fixed-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-004", "m-007"), List.of("m-002"))
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "2026年1月", text("2026"))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count",
                                fixed("2026-01-01T00:00:00+08:00", "2026-02-01T00:00:00+08:00"),
                                GREATER_OR_EQUAL, integer(1)))
                        .claims("CLAIM_FIXED_JANUARY")
                        .build(),
                c("fixed-window-tz", "2026年2月（UTC时间）有订单的会员")
                        .subcase("fixed-window-timezone-offset")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LOCAL_TIMEZONE_BOUNDARY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-003"), List.of("m-008"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "UTC时间", text("UTC"))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count",
                                fixed("2026-02-01T00:00:00Z", "2026-03-01T00:00:00Z"),
                                GREATER_THAN, integer(0)))
                        .claims("CLAIM_FIXED_FEBRUARY_UTC")
                        .build(),
                c("rolling-day-boundary", "昨天有订单的会员")
                        .subcase("rolling-day-exact-boundary")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CALENDAR_DAY")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-005", "m-009"), List.of("m-001"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "昨天", number(0))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", rolling(1, days()), GREATER_THAN, integer(0)))
                        .claims("CLAIM_YESTERDAY_ORDER")
                        .build(),
                c("rolling-week-boundary", "本周有订单的会员")
                        .subcase("rolling-week-exact-boundary")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_CALENDAR_WEEK")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-011"), List.of("m-004"))
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "本周", number(0))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count", rolling(7, days()), GREATER_THAN, integer(0)))
                        .claims("CLAIM_THIS_WEEK_ORDER")
                        .build(),
                c("window-and-attribute", "最近90天下过单的金卡会员")
                        .subcase("window-combined-with-attribute")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_WINDOW_APPLIES_TO_TIER")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-001"), List.of("m-007"))
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .slot("c2", "ORDER_COUNT", "GREATER_THAN", "最近90天", number(0))
                        .sel("member.tier", "order.count")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                metric("n-count", "order.count", rolling(90, days()),
                                        GREATER_THAN, integer(0))))
                        .claims("CLAIM_RECENT_ORDER_GOLD")
                        .build(),
                c("exists-rolling", "最近7天有已支付订单的会员")
                        .subcase("exists-with-rolling-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_SCOPE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-001", "m-004"), List.of("m-006"))
                        .slot("c1", "PAID_ORDER", "EXISTS", "最近7天", text("7"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", rolling(7, days()),
                                attr("n-status", "order.status", EQUAL, enumValue("PAID"))))
                        .claims("CLAIM_RECENT_PAID_ORDER")
                        .build(),
                c("exists-lifetime", "有过退款订单的会员")
                        .subcase("exists-with-lifetime-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_ROLLING_SCOPE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-003"), List.of("m-001"))
                        .slot("c1", "REFUNDED_ORDER", "EXISTS", "有过退款订单", text("退款"))
                        .sel("order.status")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-status", "order.status", EQUAL, enumValue("REFUNDED"))))
                        .claims("CLAIM_EVER_REFUNDED")
                        .build(),
                c("fixed-half-open", "2026年上半年（不含7月）下单的会员")
                        .subcase("fixed-window-half-open-boundary")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_END_INCLUSIVE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-002", "m-005"), List.of("m-010"))
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "2026年上半年", text("2026"))
                        .sel("order.count")
                        .ast(metric("n-root", "order.count",
                                fixed("2026-01-01T00:00:00+08:00", "2026-07-01T00:00:00+08:00"),
                                GREATER_OR_EQUAL, integer(1)))
                        .claims("CLAIM_FIRST_HALF_ORDERS")
                        .build(),
                c("rolling-en", "members who spent more than 100 in the last 30 days")
                        .subcase("rolling-window-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_WINDOW")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_WINDOW)
                        .members(List.of("m-004"), List.of("m-008"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "last 30 days", text("100"))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-root", "order.net_paid_amount", rolling(30, days()),
                                GREATER_THAN, money("100.00", "CNY")))
                        .claims("CLAIM_RECENT_SPEND_EN")
                        .build(),
                c("window-ambiguity", "消费超过500元的会员")
                        .subcase("missing-window-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_LIFETIME_WINDOW", "ASSUME_ROLLING_90_DAYS")
                        .ask("SELECT_WINDOW")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "消费超过500", text("500"))
                        .sel("order.net_paid_amount")
                        .claims("CLAIM_WINDOW_UNCERTAIN")
                        .build());
    }
}