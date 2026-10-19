package com.company.campaign.evaluation.corpus;

import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.number;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.all;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attr;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.attrNull;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.days;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.enumValue;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.integer;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.metric;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.money;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.rolling;
import static com.company.campaign.evaluation.corpus.CorpusV1Support.str;
import static com.company.campaign.metadata.domain.ComparisonOperator.EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_OR_EQUAL;
import static com.company.campaign.metadata.domain.ComparisonOperator.GREATER_THAN;
import static com.company.campaign.metadata.domain.ComparisonOperator.IS_NOT_NULL;

/**
 * Category 13 — add, change, delete and move refinements with stable target and scope references.
 *
 * <p>Every target is a stable node reference, never an array index, so a refinement survives a reordering
 * of the tree it edits. Each case states the operation, the target node and the enclosing scope.
 */
final class Category13Refinements {

    private Category13Refinements() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                c("add-tier-condition", "再加上只保留金卡会员的条件")
                        .subcase("add-node-condition")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.ADD_NODE, "n-tier", "n-root")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(all("n-root",
                                attr("n-city", "member.city", EQUAL, str("上海")),
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))))
                        .claims("CLAIM_ADD_TIER_CONDITION")
                        .build(),
                c("add-city-condition", "再加一个上海的条件")
                        .subcase("add-node-city")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.ADD_NODE, "n-city", "n-root")
                        .slot("c1", "MEMBER_CITY", "EQUAL", "上海", text("上海"))
                        .sel("member.city")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-city", "member.city", EQUAL, str("上海"))))
                        .claims("CLAIM_ADD_CITY_CONDITION")
                        .build(),
                c("add-null-condition", "再加一个等级不为空的条件")
                        .subcase("add-node-null-guard")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_NULL_MATCHES")
                        .refine(RefinementOperation.ADD_NODE, "n-tier-null", "n-root")
                        .slot("c1", "MEMBER_TIER", "IS_NOT_NULL", "等级不为空", null)
                        .sel("member.tier")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attrNull("n-tier-null", "member.tier", IS_NOT_NULL)))
                        .claims("CLAIM_ADD_NULL_GUARD")
                        .build(),
                c("add-metric-condition", "再加最近90天订单数大于3的条件")
                        .subcase("add-node-metric")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.ADD_NODE, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "订单数大于3", number(3))
                        .sel("order.count")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                metric("n-count", "order.count", rolling(90, days()),
                                        GREATER_THAN, integer(3))))
                        .claims("CLAIM_ADD_METRIC_CONDITION")
                        .build(),
                c("remove-city", "去掉城市这个条件")
                        .subcase("remove-node-city")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.REMOVE_NODE, "n-city", "n-root")
                        .slot("c1", "MEMBER_CITY", "EQUAL", "城市", text("上海"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_REMOVE_CITY_CONDITION")
                        .build(),
                c("remove-metric", "删除订单数条件")
                        .subcase("remove-node-metric")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.REMOVE_NODE, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "订单数条件", number(3))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_REMOVE_METRIC_CONDITION")
                        .build(),
                c("remove-null-guard", "移除等级为空的条件")
                        .subcase("remove-node-null-guard")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.REMOVE_NODE, "n-tier-null", "n-root")
                        .slot("c1", "MEMBER_TIER", "IS_NOT_NULL", "等级为空的条件", null)
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_REMOVE_NULL_GUARD")
                        .build(),
                c("remove-window", "去掉最近90天的限制")
                        .subcase("remove-node-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_LIFETIME_WINDOW")
                        .refine(RefinementOperation.REMOVE_NODE, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "最近90天", number(3))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")))
                        .claims("CLAIM_REMOVE_WINDOW")
                        .build(),
                c("change-tier-value", "把等级条件改成银卡")
                        .subcase("replace-field-value")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_VALUE_UNCHANGED")
                        .refine(RefinementOperation.REPLACE_FIELD, "n-tier", "n-root")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "银卡", text("SILVER"))
                        .sel("member.tier")
                        .ast(attr("n-tier", "member.tier", EQUAL, enumValue("SILVER")))
                        .claims("CLAIM_REPLACE_TIER_VALUE")
                        .build(),
                c("change-amount-value", "把金额门槛改成500元")
                        .subcase("replace-field-amount")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_VALUE_UNCHANGED")
                        .refine(RefinementOperation.REPLACE_FIELD, "n-spend", "n-root")
                        .slot("c1", "NET_SPEND", "GREATER_THAN", "500元", number(500))
                        .sel("order.net_paid_amount")
                        .ast(metric("n-spend", "order.net_paid_amount", rolling(90, days()),
                                GREATER_THAN, money("500.00", "CNY")))
                        .claims("CLAIM_REPLACE_AMOUNT")
                        .build(),
                c("change-window", "把时间窗改成30天")
                        .subcase("replace-field-window")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_WINDOW_UNCHANGED")
                        .refine(RefinementOperation.REPLACE_FIELD, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "30天", number(30))
                        .sel("order.count")
                        .ast(metric("n-count", "order.count", rolling(30, days()), GREATER_THAN, integer(3)))
                        .claims("CLAIM_REPLACE_WINDOW")
                        .build(),
                c("change-operator", "把大于改成大于等于")
                        .subcase("replace-field-operator")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH", "ASSUME_OPERATOR_UNCHANGED")
                        .refine(RefinementOperation.REPLACE_FIELD, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_OR_EQUAL", "大于等于", number(3))
                        .sel("order.count")
                        .ast(metric("n-count", "order.count", rolling(90, days()),
                                GREATER_OR_EQUAL, integer(3)))
                        .claims("CLAIM_REPLACE_OPERATOR")
                        .build(),
                c("move-tier-first", "把金卡条件移到最前面")
                        .subcase("move-node-to-front")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.MOVE_NODE, "n-tier", "n-root")
                        .slot("c1", "MEMBER_TIER", "EQUAL", "金卡", text("GOLD"))
                        .sel("member.tier")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-city", "member.city", EQUAL, str("上海"))))
                        .claims("CLAIM_MOVE_TIER_FIRST")
                        .build(),
                c("move-metric-first", "把订单数条件移到最前面")
                        .subcase("move-node-metric")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.MOVE_NODE, "n-count", "n-root")
                        .slot("c1", "ORDER_COUNT", "GREATER_THAN", "订单数条件", number(3))
                        .sel("order.count")
                        .ast(all("n-root",
                                metric("n-count", "order.count", rolling(90, days()),
                                        GREATER_THAN, integer(3)),
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD"))))
                        .claims("CLAIM_MOVE_METRIC_FIRST")
                        .build(),
                c("move-city-out-of-group", "把城市条件移出分组")
                        .subcase("move-node-out-of-scope")
                        .risk(RiskLevel.HIGH).forbid("ASSUME_INDEX_PATCH")
                        .refine(RefinementOperation.MOVE_NODE, "n-city", "n-group-city")
                        .slot("c1", "MEMBER_CITY", "EQUAL", "城市条件", text("上海"))
                        .sel("member.city")
                        .ast(all("n-root",
                                attr("n-tier", "member.tier", EQUAL, enumValue("GOLD")),
                                attr("n-city", "member.city", EQUAL, str("上海"))))
                        .claims("CLAIM_MOVE_CITY_OUT_OF_GROUP")
                        .build());
    }
}