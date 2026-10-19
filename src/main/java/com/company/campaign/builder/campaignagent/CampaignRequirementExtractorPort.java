package com.company.campaign.builder.campaignagent;

import java.util.List;
import java.util.Optional;

/** Extracts only the Campaign requirement facts explicitly supplied by one user turn. */
@FunctionalInterface
public interface CampaignRequirementExtractorPort {

    /** Returns an accepted partial delta, or empty when extraction was rejected. */
    Optional<CampaignRequirementDelta> extractDelta(
            String utterance, CampaignRequirementBrief accepted, CampaignRequirementCode pendingQuestion);

    /** Generates one bounded natural-language question for the host-selected missing requirement. */
    default Optional<String> clarificationQuestion(
            CampaignRequirementBrief accepted, CampaignRequirementCode missingRequirement) {
        return Optional.empty();
    }

    /** Generates one bounded question constrained by host-registered answer candidates. */
    default Optional<String> clarificationQuestion(
            CampaignRequirementBrief accepted,
            CampaignRequirementCode missingRequirement,
            List<CampaignAnswerOption> options) {
        return clarificationQuestion(accepted, missingRequirement);
    }

    /**
     * Generates one bounded question with the current user utterance available only as a language cue.
     *
     * <p>The host still owns which requirement is missing. Implementations that do not need the utterance keep
     * their existing behaviour through this compatibility default.
     */
    default Optional<String> clarificationQuestion(
            String utterance,
            CampaignRequirementBrief accepted,
            CampaignRequirementCode missingRequirement,
            List<CampaignAnswerOption> options) {
        return clarificationQuestion(accepted, missingRequirement, options);
    }

    /**
     * Generates one bounded natural-language question for a component-owned clarification.
     *
     * <p><strong>Forbidden only for Main Agent component aggregation.</strong> The Main Agent must not
     * invoke this method during aggregate projection — component prompts are final and the Main Agent
     * only collects, orders and projects them deterministically. A component agent may use this method
     * from inside its own component execution boundary, using only its own data.
     *
     * <p>Prefer {@link #componentClarificationWording(CampaignRequirementBrief, CampaignComponentClarification, String)}
     * for component-internal wording; this method is retained for backward-compatible test references.
     *
     * @deprecated The Main Agent must not model-rewrite component questions; component prompts are final.
     *             For component-internal wording use {@link #componentClarificationWording} instead.
     */
    @Deprecated
    default Optional<String> skillClarificationQuestion(
            CampaignRequirementBrief accepted, CampaignClarification clarification) {
        return Optional.empty();
    }

    /**
     * Synthesizes one concise natural-language question from the complete list of component clarification
     * requests of one turn.
     *
     * <p><strong>Obsolete.</strong> The Main Agent no longer synthesizes aggregate clarification questions.
     * Multi-component top-level prompts are deterministic host-built concatenations, not model-synthesized.
     *
     * @deprecated Multi-component prompts are deterministic host-built concatenations, not model-synthesized.
     */
    @Deprecated
    default Optional<String> aggregateClarificationQuestion(
            List<CampaignComponentClarification> requests) {
        return Optional.empty();
    }

    /** Generates bounded conversational copy for a host-owned collection outcome. */
    default Optional<String> statusMessage(
            String utterance, CampaignRequirementBrief accepted, String statusCode) {
        return Optional.empty();
    }

    /**
     * Generates a natural-language prompt for one component-owned clarification.
     *
     * <p>Called from inside a component agent's own execution boundary with only that component's
     * data: its own NeedsInput outcome, stable error code/question codes/options, component identity,
     * and the accepted brief. The user text is provided only as a language cue.
     *
     * <p>The Main Agent must not call this method; it is for component-internal wording only.
     * Each component must make an independent wording decision; a cross-component aggregate must
     * never be sent to the model through this port.
     *
     * <p>The default returns empty, which causes the component agent to use its deterministic
     * fallback prompt from {@link CampaignComponentClarification#promptFor}.
     *
     * @param accepted       the accepted Campaign requirement brief at the time of the clarification
     * @param clarification  the component-scoped clarification with its question codes, options and error code
     * @param userText       the latest user utterance, provided only as a language cue
     * @return the model-reworded prompt, or empty to use the deterministic fallback
     */
    default Optional<String> componentClarificationWording(
            CampaignRequirementBrief accepted,
            CampaignComponentClarification clarification,
            String userText) {
        return Optional.empty();
    }
}
