package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyErrorCodes;
import com.company.campaign.builder.assembly.CampaignSkillIds;

import java.util.List;

/**
 * The closed, server-owned Campaign component groups one Campaign conversation tracks.
 *
 * <p>The conversation is not a fixed questionnaire: it maintains one evolving Campaign brief plus this derived
 * TODO view, grouped by the component a fact belongs to. One user message may advance several components at
 * once, so the groups are the stable unit of progress rather than the order of questions.
 *
 * <p>A component code is product schema, not user copy: a client branches on the exact code, so the group a
 * requirement code belongs to is a deterministic function of the code and never of any wording. The ownership
 * declared here is host-owned too: the requirement codes, the validating Skills and the workspace dependency
 * codes a group owns are a function of the component, so no Skill identifier or dependency code reaches the
 * presentation and no group can claim a fact that belongs to another.
 */
public enum CampaignComponentCode {

    /** Audience definition and its Segment Skill validation. */
    SEGMENT(
            List.of(CampaignRequirementCode.AUDIENCE_REQUIRED),
            List.of(CampaignSkillIds.SEGMENT_RULE_BASED),
            List.of()),

    /** Whether an Offer exists, its definition, and its Promotion/Governance validation. */
    OFFER(
            List.of(CampaignRequirementCode.OFFER_DECISION_REQUIRED, CampaignRequirementCode.OFFER_DETAIL_REQUIRED),
            List.of(CampaignSkillIds.PROMOTION_REFERENCE, CampaignSkillIds.GOVERNANCE_LIMIT),
            List.of(CampaignAssemblyErrorCodes.MISSING_GOVERNANCE_LIMIT)),

    /** Selected channels plus message/content requirements and Message Skill validation. */
    COMMUNICATION(
            List.of(CampaignRequirementCode.CHANNEL_REQUIRED, CampaignRequirementCode.MESSAGE_REQUIRED),
            List.of(CampaignSkillIds.MESSAGE_TEMPLATE),
            List.of()),

    /** The branch decision and, when branching is required, its evaluable condition and both outcomes. */
    JOURNEY(
            List.of(CampaignRequirementCode.EVENT_REQUIRED,
                    CampaignRequirementCode.BRANCH_DECISION_REQUIRED,
                    CampaignRequirementCode.BRANCH_DETAIL_REQUIRED,
                    CampaignRequirementCode.BRANCH_RULE_REQUIRED),
            List.of(CampaignSkillIds.JOURNEY_FLOW),
            List.of(CampaignAssemblyErrorCodes.FLOW_REFERENCE_UNRESOLVED)),

    /** The execution mode and the complete executable schedule. */
    EXECUTION_SCHEDULE(
            List.of(CampaignRequirementCode.CADENCE_REQUIRED,
                    CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED,
                    CampaignRequirementCode.EXECUTION_TIME_REQUIRED,
                    CampaignRequirementCode.SCHEDULE_START_DATE_REQUIRED,
                    CampaignRequirementCode.SCHEDULE_END_DATE_REQUIRED),
            List.of(CampaignSkillIds.SCHEDULE_BASIC),
            List.of());

    private final List<CampaignRequirementCode> requirementCodes;
    private final List<String> skillIds;
    private final List<String> dependencyCodes;

    CampaignComponentCode(
            List<CampaignRequirementCode> requirementCodes, List<String> skillIds, List<String> dependencyCodes) {
        this.requirementCodes = List.copyOf(requirementCodes);
        this.skillIds = List.copyOf(skillIds);
        this.dependencyCodes = List.copyOf(dependencyCodes);
    }

    /** The exact stable machine code. */
    public String code() {
        return name();
    }

    /** Every requirement code this component owns, in the server-owned declaration order. */
    public List<CampaignRequirementCode> requirementCodes() {
        return requirementCodes;
    }

    /** The Skills whose accepted validation closes this component, in the server-owned declaration order. */
    public List<String> skillIds() {
        return skillIds;
    }

    /** The workspace missing-dependency codes this component is responsible for. */
    public List<String> dependencyCodes() {
        return dependencyCodes;
    }

    /**
     * The declared component whose exact code equals the argument, or {@code null} when none does.
     *
     * <p>It is the one strict decoder for a component wire name: an unknown value returns {@code null} rather
     * than selecting a group.
     */
    public static CampaignComponentCode fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CampaignComponentCode component : values()) {
            if (component.name().equals(code)) {
                return component;
            }
        }
        return null;
    }

    /**
     * The component group that owns the given capability, or {@code null} when none does.
     *
     * <p>This is the host-owned mapping from the six capability codes to the five component groups.
     * EVENT and FLOW both map to JOURNEY; MESSAGE maps to COMMUNICATION; PROMOTION maps to OFFER.
     */
    public static CampaignComponentCode fromCapability(CampaignCapability capability) {
        if (capability == null) {
            return null;
        }
        return switch (capability) {
            case SEGMENT -> SEGMENT;
            case EVENT, FLOW -> JOURNEY;
            case MESSAGE -> COMMUNICATION;
            case PROMOTION -> OFFER;
            case SCHEDULE -> EXECUTION_SCHEDULE;
        };
    }
}
