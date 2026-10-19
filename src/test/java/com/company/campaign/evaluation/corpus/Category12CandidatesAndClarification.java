package com.company.campaign.evaluation.corpus;

import com.company.campaign.contracts.Disposition;
import com.company.campaign.evaluation.corpus.CorpusV1Blueprints.CaseSpec;
import com.company.campaign.evaluation.segment.contract.RiskLevel;

import java.util.List;

import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.c;
import static com.company.campaign.evaluation.corpus.CorpusV1Blueprints.text;

/**
 * Category 12 — multiple Metadata candidates and required clarification.
 *
 * <p>Ambiguous business vocabulary with two or more plausible definitions must produce a question, not a
 * pick. Each case names the candidates it considered, the conflicting candidate it rejected, and the
 * assumption it refuses to make silently.
 */
final class Category12CandidatesAndClarification {

    private Category12CandidatesAndClarification() {
    }

    static List<CaseSpec> cases() {
        return List.of(
                clarify("spend", "消费高的会员", "消费", "SELECT_SPEND_DEFINITION", "SPEND_AMOUNT",
                        "member.tier", "order.gross_amount"),
                clarify("status", "状态异常的会员", "状态", "SELECT_STATUS_DEFINITION", "MEMBER_STATUS",
                        "member.status", "order.status"),
                clarify("tier", "等级较高的会员", "等级", "SELECT_TIER_DEFINITION", "MEMBER_TIER",
                        "member.tier", "account.segment"),
                clarify("activity", "活跃的会员", "活跃", "SELECT_ACTIVITY_DEFINITION", "ACTIVITY",
                        "member.last_seen", "event.count"),
                clarify("recent", "最近的会员", "最近", "SELECT_WINDOW", "RECENCY",
                        "order.count", "event.count"),
                clarify("amount", "金额大的会员", "金额", "SELECT_AMOUNT_DEFINITION", "AMOUNT",
                        "order.amount", "order.gross_amount"),
                clarify("orders", "订单多的会员", "订单", "SELECT_ORDER_DEFINITION", "ORDER_COUNT",
                        "order.count", "order.amount"),
                clarify("frequency", "频率高的会员", "频率", "SELECT_FREQUENCY_DEFINITION", "FREQUENCY",
                        "event.count", "order.count"),
                clarify("city", "城市特殊的会员", "城市", "SELECT_CITY_DEFINITION", "CITY",
                        "member.city", "member.name"),
                clarify("points", "积分多的会员", "积分", "SELECT_POINTS_DEFINITION", "POINTS",
                        "member.loyalty_points", "member.order_count"),
                clarify("events", "事件丰富的会员", "事件", "SELECT_EVENT_DEFINITION", "EVENT",
                        "event.type", "order.status"),
                clarify("members", "会员量大的分组", "会员量", "SELECT_MEMBER_DEFINITION", "MEMBER_SET",
                        "member.tier", "member.status"),
                clarify("value", "价值高的会员", "价值", "SELECT_VALUE_DEFINITION", "MEMBER_VALUE",
                        "order.net_paid_amount", "account.segment"),
                clarify("time-range", "时间范围长的会员", "时间范围", "SELECT_WINDOW", "TIME_RANGE",
                        "order.count", "order.net_paid_amount"),
                clarify("channel", "渠道偏好的会员", "渠道", "SELECT_CHANNEL_DEFINITION", "CHANNEL",
                        "order.channel", "member.city"));
    }

    private static CaseSpec clarify(
            String slug, String utterance, String slotText, String question, String concept,
            String selectedCandidate, String rejectedCandidate) {

        return c(slug, utterance)
                .subcase("clarification-" + slug)
                .risk(RiskLevel.HIGH)
                .disposition(Disposition.NEEDS_INPUT)
                .forbid("ASSUME_" + concept + "_DEFINITION", "ASSUME_SINGLE_CANDIDATE")
                .ask(question)
                .slot("c1", concept, "AMBIGUOUS", slotText, text("AMBIGUOUS"))
                .sel(selectedCandidate)
                .rej(rejectedCandidate)
                .validate("AMBIGUOUS_DEFINITION")
                .claims("CLAIM_CLARIFICATION_REQUIRED")
                .build();
    }
}