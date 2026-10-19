package com.company.campaign.builder.campaignagent;

import java.util.List;

/**
 * The one typed, Skill-specific input-resolution boundary of the top-level Campaign Agent.
 *
 * <p>It exists because a routing model may <em>select</em> a Skill but may never produce a Tool argument: the
 * routing vocabulary has no field that could carry a template reference, a reward reference, a quantity, a
 * governance ceiling, a Schedule declaration, a Governance declaration or a Journey node/topology. Those are
 * host evidence, and this port is the only place the host resolves them for one utterance.
 *
 * <p>Each method returns a typed input record of the accepted contract. A method that cannot resolve every
 * required fact returns the corresponding {@code unresolved()} value, which makes the runner ask its accepted
 * clarification instead of inventing a reference, a quantity, a ceiling, a Journey node, a schedule or a
 * governance limit.
 *
 * <p>{@link #unresolved()} is the honest inert implementation: it resolves nothing at all, so a build that has
 * no development model wired still runs every Skill through its accepted clarification path rather than
 * fabricating an artifact. The interface carries no raw map, no JSON document, no callback and no model handle.
 */
public interface CampaignSkillInputResolver {

    /** The typed Message inputs of one utterance. */
    CampaignMessageInputs messageInputs(String utterance, List<CampaignClarificationAnswer> answers);

    /** The typed Promotion inputs of one utterance. */
    CampaignPromotionInputs promotionInputs(String utterance, List<CampaignClarificationAnswer> answers);

    /** The typed Journey inputs of one utterance. */
    CampaignJourneyInputs journeyInputs(String utterance, List<CampaignClarificationAnswer> answers);

    /** The typed Schedule inputs of one utterance. */
    CampaignScheduleInputs scheduleInputs(String utterance, List<CampaignClarificationAnswer> answers);

    /** The typed Governance inputs of one utterance. */
    CampaignGovernanceInputs governanceInputs(String utterance, List<CampaignClarificationAnswer> answers);

    /**
     * The inert resolver: every Skill is unresolved.
     *
     * <p>It is the safe default of a build that has not opted into a development model, and it is also the
     * value a host must use when a resolution fails closed, so no partial fact can ever reach a Skill.
     */
    static CampaignSkillInputResolver unresolved() {
        return UnresolvedResolver.INSTANCE;
    }

    /** The one inert resolver instance; it holds no state and reads nothing. */
    final class UnresolvedResolver implements CampaignSkillInputResolver {

        private static final UnresolvedResolver INSTANCE = new UnresolvedResolver();

        private UnresolvedResolver() {
        }

        @Override
        public CampaignMessageInputs messageInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return CampaignMessageInputs.unresolved();
        }

        @Override
        public CampaignPromotionInputs promotionInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return CampaignPromotionInputs.unresolved();
        }

        @Override
        public CampaignJourneyInputs journeyInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return CampaignJourneyInputs.unresolved();
        }

        @Override
        public CampaignScheduleInputs scheduleInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return CampaignScheduleInputs.unresolved();
        }

        @Override
        public CampaignGovernanceInputs governanceInputs(
                String utterance, List<CampaignClarificationAnswer> answers) {
            return CampaignGovernanceInputs.unresolved();
        }
    }
}