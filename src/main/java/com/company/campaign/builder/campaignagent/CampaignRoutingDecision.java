package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;

/**
 * One bounded, strictly decoded routing decision.
 *
 * <p>It carries exactly seven facts: the route kind, a nullable Campaign type, at most six ordered server-owned
 * Skill identifiers, at most four bounded question codes, bounded safe assistant copy, at most eight answer
 * options, and an untrusted objective-evidence string proposal (never a confirmed fact). There is deliberately
 * no field for a Tool identifier, a Tool argument, a Proposal operation, a
 * capability payload, a lifecycle action, a Provider call or a saved-state fact, so none of those is
 * representable here.
 *
 * <p>{@link #campaignType()} is only meaningful for a {@link CampaignRouteKind#CAMPAIGN_REQUEST}, where the
 * strict decoder requires exactly one {@link CampaignType}. Every other route kind carries a null type. The
 * record itself neither infers nor defaults a type: it is the guard
 * ({@link CampaignRoutingDecisionDecoder}) that enforces which route kinds may carry one, because only the
 * decoder judges a model output. A host that resumes an already-classified Campaign may therefore build a
 * decision through the source-compatible constructor below without inventing a type.
 *
 * <p>The decision is the only shape a routing model may produce. The host decides what it means: whether the
 * turn is initial, follow-up or finalizable, which prior session revision is current, and whether the named
 * Skills may run at all.
 */
public record CampaignRoutingDecision(
        CampaignRouteKind routeKind,
        CampaignType campaignType,
        List<String> skillIds,
        List<String> questionCodes,
        String assistantCopy,
        List<CampaignAnswerOption> options,
        String objectiveEvidence
) {

    /**
     * The source-compatible shape for a host that predates the Campaign type field.
     *
     * <p>It carries a null type and never guesses one; the decoder is the only party that may require a type,
     * and only for a Campaign request it is judging.
     */
    public CampaignRoutingDecision(
            CampaignRouteKind routeKind,
            List<String> skillIds,
            List<String> questionCodes,
            String assistantCopy,
            List<CampaignAnswerOption> options) {
        this(routeKind, null, skillIds, questionCodes, assistantCopy, options, null);
    }

    public CampaignRoutingDecision {
        if (routeKind == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "routeKind", "A routing decision needs a route.");
        }
        skillIds = skillIds == null ? List.of() : List.copyOf(new ArrayList<>(skillIds));
        questionCodes = questionCodes == null ? List.of() : List.copyOf(new ArrayList<>(questionCodes));
        options = options == null ? List.of() : List.copyOf(new ArrayList<>(options));
        if (skillIds.size() > CampaignAgentLimits.MAX_SELECTED_SKILLS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "skillIds",
                    "A routing decision exceeds its Skill bound.");
        }
        for (String skillId : skillIds) {
            CampaignRoutingVocabulary.requireKnownSkill(skillId);
        }
        if (questionCodes.size() > CampaignAgentLimits.MAX_QUESTION_CODES) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "questionCodes",
                    "A routing decision exceeds its question bound.");
        }
        for (String code : questionCodes) {
            if (code == null || !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_SHAPE_INVALID, "questionCode",
                        "A question code must be a stable machine code.");
            }
        }
        if (options.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "options",
                    "A routing decision exceeds its option bound.");
        }
        if (assistantCopy != null && assistantCopy.length() > CampaignAgentLimits.MAX_ASSISTANT_COPY_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_LIMIT_EXCEEDED, "assistantCopy",
                    "Bounded assistant copy exceeds its declared bound.");
        }
    }

    /** True when this decision asks the named Skills to run. */
    public boolean selectsSkills() {
        return routeKind.selectsSkills();
    }

    /** True when this decision carries no executable content at all. */
    public boolean empty() {
        return skillIds.isEmpty() && questionCodes.isEmpty() && options.isEmpty() && assistantCopy == null;
    }
}