package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.assembly.CampaignSkillIds;

import java.util.ArrayList;
import java.util.List;

/**
 * The closed typed command one Skill runner receives.
 *
 * <p>A runner is handed the latest user utterance and the bounded answers of the pending clarification — not
 * the routing model's output, not a raw model payload and not another Skill's artifact. The variant decides
 * which Skill runs, so a command can never name a Skill the router does not own.
 */
public sealed interface CampaignSkillCommand {

    /** The server-owned Skill identifier this command addresses. */
    String skillId();

    /** The latest bounded user utterance. */
    String utterance();

    /** The bounded clarification answers supplied with this turn. */
    List<CampaignClarificationAnswer> answers();

    /** The accepted rule-based Segment Skill. */
    record SegmentRuleBased(String utterance, List<CampaignClarificationAnswer> answers)
            implements CampaignSkillCommand {

        public SegmentRuleBased {
            answers = bounded(answers);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.SEGMENT_RULE_BASED;
        }
    }

    /** The Message template Skill. */
    record MessageTemplate(String utterance, List<CampaignClarificationAnswer> answers)
            implements CampaignSkillCommand {

        public MessageTemplate {
            answers = bounded(answers);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.MESSAGE_TEMPLATE;
        }
    }

    /** The Promotion reference Skill. */
    record PromotionReference(String utterance, List<CampaignClarificationAnswer> answers)
            implements CampaignSkillCommand {

        public PromotionReference {
            answers = bounded(answers);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.PROMOTION_REFERENCE;
        }
    }

    /** The planned Journey flow Skill, which this build cannot execute. */
    record JourneyFlow(
            String utterance,
            List<CampaignClarificationAnswer> answers,
            boolean minimal,
            String triggerEventCode)
            implements CampaignSkillCommand {

        public JourneyFlow {
            answers = bounded(answers);
        }

        public JourneyFlow(String utterance, List<CampaignClarificationAnswer> answers, boolean minimal) {
            this(utterance, answers, minimal, null);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.JOURNEY_FLOW;
        }
    }

    /** The planned Schedule Skill, which this build cannot execute. */
    record ScheduleBasic(String utterance, List<CampaignClarificationAnswer> answers)
            implements CampaignSkillCommand {

        public ScheduleBasic {
            answers = bounded(answers);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.SCHEDULE_BASIC;
        }
    }

    /** The planned Governance limit Skill, which this build cannot execute. */
    record GovernanceLimit(String utterance, List<CampaignClarificationAnswer> answers)
            implements CampaignSkillCommand {

        public GovernanceLimit {
            answers = bounded(answers);
        }

        @Override
        public String skillId() {
            return CampaignSkillIds.GOVERNANCE_LIMIT;
        }
    }

    /** Builds the closed command of one server-owned Skill identifier. */
    static CampaignSkillCommand forSkill(
            String skillId, String utterance, List<CampaignClarificationAnswer> answers) {
        return forSkill(skillId, utterance, answers, false);
    }

    /** Builds a command while carrying the host-owned decision that no Journey action is applicable. */
    static CampaignSkillCommand forSkill(
            String skillId,
            String utterance,
            List<CampaignClarificationAnswer> answers,
            boolean minimalJourney) {
        if (!CampaignSkillIds.isKnown(skillId)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.ROUTING_UNKNOWN_SKILL, "skillId",
                    "A Skill command may only address a server-owned Skill.");
        }
        return switch (skillId) {
            case CampaignSkillIds.SEGMENT_RULE_BASED -> new SegmentRuleBased(utterance, answers);
            case CampaignSkillIds.MESSAGE_TEMPLATE -> new MessageTemplate(utterance, answers);
            case CampaignSkillIds.PROMOTION_REFERENCE -> new PromotionReference(utterance, answers);
            case CampaignSkillIds.JOURNEY_FLOW -> new JourneyFlow(utterance, answers, minimalJourney, null);
            case CampaignSkillIds.SCHEDULE_BASIC -> new ScheduleBasic(utterance, answers);
            default -> new GovernanceLimit(utterance, answers);
        };
    }

    /** Builds a command with host-owned Journey mode and registered trigger-event binding. */
    static CampaignSkillCommand forSkill(
            String skillId,
            String utterance,
            List<CampaignClarificationAnswer> answers,
            boolean minimalJourney,
            String triggerEventCode) {
        if (!CampaignSkillIds.JOURNEY_FLOW.equals(skillId)) {
            return forSkill(skillId, utterance, answers, minimalJourney);
        }
        return new JourneyFlow(utterance, answers, minimalJourney, triggerEventCode);
    }

    private static List<CampaignClarificationAnswer> bounded(List<CampaignClarificationAnswer> answers) {
        return answers == null ? List.of() : List.copyOf(new ArrayList<>(answers));
    }
}
