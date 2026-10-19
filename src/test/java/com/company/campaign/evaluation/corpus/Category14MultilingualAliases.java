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
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attrNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.exists;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.lifetime;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.str;
import static com.company.campaign.metadata.domain.ComparisonOperator.CONTAINS;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;

/**
 * Category 14 — zh-CN, en-US, mixed language, aliases and bounded recoverable typos.
 *
 * <p>Aliases and small typos resolve; a typo outside the bounded alias set asks instead. That boundary is
 * the point of the category: recovery must not become guessing.
 */
final class Category14MultilingualAliases {

    private Category14MultilingualAliases() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("alias-zh-tier", "会员等级是金卡的会员")
                        .subcase("alias-zh-tier")
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_TIER", "EQUAL", "会员等级", text("GOLD"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_ALIAS_TIER")
                        .build(),
                c("alias-zh-tier-card", "卡级为银卡的会员")
                        .subcase("alias-zh-card-level")
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_TIER", "EQUAL", "卡级", text("SILVER"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("SILVER")))
                        .claims("CLAIM_ALIAS_CARD_LEVEL")
                        .build(),
                c("alias-zh-city", "所在城市是上海的会员")
                        .subcase("alias-zh-city")
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_CITY", "EQUAL", "所在城市", text("上海"))
                        .sel("member.city")
                        .ast(attr("n-city", "member.city", EQUAL, str("上海")))
                        .claims("CLAIM_ALIAS_CITY")
                        .build(),
                c("alias-zh-order-count", "订单数量超过3的会员")
                        .subcase("alias-zh-order-count")
                        .risk(RiskLevel.MEDIUM)
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "订单数量", number(3))
                        .sel("member.order_count")
                        .ast(attr("n-count", "member.order_count", GREATER_THAN, integer(3)))
                        .claims("CLAIM_ALIAS_ORDER_COUNT")
                        .build(),
                c("alias-zh-net-spend", "净消费金额超过500元的会员")
                        .subcase("alias-zh-net-spend")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_GROSS_AMOUNT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-001"), List.of("m-008"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "净消费金额", number(500))
                        .sel("order.net_paid_amount").rej("order.gross_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_ALIAS_NET_SPEND")
                        .build(),
                c("alias-en-city", "members whose city name is Shanghai")
                        .subcase("alias-en-city")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_CITY", "EQUAL", "city name", text("上海"))
                        .sel("member.city")
                        .ast(attr("n-city", "member.city", EQUAL, str("上海")))
                        .claims("CLAIM_ALIAS_CITY_EN")
                        .build(),
                c("alias-en-active", "active members only")
                        .subcase("alias-en-status")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_STATUS", "EQUAL", "active members", text("ACTIVE"))
                        .sel("member.status")
                        .ast(attr("n-status", "member.status", EQUAL, enumValue("ACTIVE")))
                        .claims("CLAIM_ALIAS_ACTIVE")
                        .build(),
                c("mixed-language", "上海的金卡会员 gold tier")
                        .subcase("mixed-language")
                        .locales(CorpusV1Support.mixed())
                        .risk(RiskLevel.MEDIUM)
                        .slot("c1", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .slot("c2", "MEMBER_TIER", "EQUAL", "gold tier", text("GOLD"))
                        .sel("member.city", "member.tier")
                        .ast(all("n-root",
                                attr("n-city", "member.city", EQUAL, str("上海")),
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))))
                        .claims("CLAIM_MIXED_LANGUAGE")
                        .build(),
                c("typo-zh-tier", "会员等及是金卡的会员")
                        .subcase("recoverable-typo-zh")
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_TIER", "EQUAL", "会员等及", text("GOLD"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_TYPO_RECOVERED")
                        .build(),
                c("typo-en-memberz", "gold memberz in Shanghai")
                        .subcase("recoverable-typo-en")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_TIER", "EQUAL", "gold", text("GOLD"))
                        .slot("c2", "MEMBER_CITY", "EQUAL", "Shanghai", text("上海"))
                        .sel("member.tier", "member.city")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-city", "member.city", EQUAL, str("上海"))))
                        .claims("CLAIM_TYPO_RECOVERED_EN")
                        .build(),
                c("typo-zh-state", "会元状态是活跃的会员")
                        .subcase("recoverable-typo-status")
                        .risk(RiskLevel.LOW)
                        .slot("c1", "MEMBER_STATUS", "EQUAL", "会元状态", text("ACTIVE"))
                        .sel("member.status")
                        .ast(attr("n-status", "member.status", EQUAL, enumValue("ACTIVE")))
                        .claims("CLAIM_TYPO_STATUS")
                        .build(),
                c("alias-zh-nickname", "有昵称记录的会员")
                        .subcase("alias-zh-nickname-null")
                        .risk(RiskLevel.MEDIUM)
                        .slot("c1", "MEMBER_NICKNAME", "IS_NOT_NULL", "昵称", null)
                        .sel("member.nickname")
                        .ast(attrNull("n-nickname", "member.nickname", IS_NOT_NULL))
                        .claims("CLAIM_NICKNAME_PRESENT")
                        .build(),
                c("alias-en-channel", "members with an online order channel")
                        .subcase("alias-en-channel-exists")
                        .locales(CorpusV1Support.enOnly())
                        .risk(RiskLevel.HIGH).forbid("ASSUME_LIFETIME_SCOPE")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_EXISTS_SAME_ROW)
                        .members(List.of("m-001"), List.of("m-006"))
                        .slot("c1", "ORDER_CHANNEL", "EXISTS", "online order channel", text("online"))
                        .sel("order.channel")
                        .ast(exists("n-root", "member.orders", lifetime(),
                                attr("n-channel", "order.channel", EQUAL, enumValue("ONLINE"))))
                        .claims("CLAIM_CHANNEL_ALIAS_EN")
                        .build(),
                c("mixed-alias-spend", "net spend 净消费金额 超过1000的会员")
                        .subcase("mixed-alias-spend")
                        .locales(CorpusV1Support.mixed())
                        .risk(RiskLevel.HIGH).forbid("ASSUME_GROSS_AMOUNT")
                        .fixtures(CorpusV1Support.PRIMARY_ORDERS, CorpusV1Support.COUNTER_CURRENCY)
                        .members(List.of("m-002"), List.of("m-009"))
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "净消费金额", number(1000))
                        .sel("order.net_paid_amount").rej("order.gross_amount")
                        .ast(metric("n-root", "order.net_paid_amount", lifetime(),
                                GREATER_THAN, money("1000.00", "CNY")))
                        .claims("CLAIM_MIXED_ALIAS_SPEND")
                        .build(),
                c("unrecoverable-typo", "会元等鸡是金卡")
                        .subcase("unrecoverable-typo-clarification")
                        .risk(RiskLevel.HIGH).disposition(Disposition.NEEDS_INPUT)
                        .forbid("ASSUME_TYPO_MEANS_TIER")
                        .ask("SELECT_CONCEPT")
                        .slot("c1", "UNKNOWN_CONCEPT", "AMBIGUOUS", "会元等鸡", text("AMBIGUOUS"))
                        .validate("UNRECOVERABLE_TERM")
                        .claims("CLAIM_TERM_UNRECOVERABLE")
                        .build());
    }
}