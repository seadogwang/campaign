package com.company.campaign.builder.campaignagent;

/**
 * The bounded limits of one Campaign conversation.
 *
 * <p>Every bound is a fixed constant of this build, so a caller cannot widen a bound by shaping a request,
 * a model output or a stored turn.
 */
public final class CampaignAgentLimits {

    /** Largest accepted user utterance length. */
    public static final int MAX_UTTERANCE_LENGTH = 512;

    /** Largest bounded clarification answer text. */
    public static final int MAX_ANSWER_LENGTH = 512;

    /** Largest accepted raw routing-model output size, in bytes. */
    public static final int MAX_MODEL_OUTPUT_BYTES = 8_192;

    /** Largest number of prior turn summaries the routing request may carry. */
    public static final int MAX_PRIOR_TURNS = 4;

    /** Largest length of one prior turn summary. */
    public static final int MAX_PRIOR_TURN_LENGTH = 280;

    /** Largest number of accepted turns one conversation may hold. */
    public static final int MAX_TURNS = 64;

    /** Largest number of Skills one routing decision may select. */
    public static final int MAX_SELECTED_SKILLS = 6;

    /** Largest number of question codes one routing decision may carry. */
    public static final int MAX_QUESTION_CODES = 4;

    /** Largest number of answer options one clarification may carry. */
    public static final int MAX_OPTIONS = 8;

    /** Largest length of bounded safe assistant copy. */
    public static final int MAX_ASSISTANT_COPY_LENGTH = 320;

    /** Largest length of one answer option identifier. */
    public static final int MAX_OPTION_ID_LENGTH = 96;

    /** Largest length of one answer option label. */
    public static final int MAX_OPTION_LABEL_LENGTH = 160;

    /** Largest length of manifest evidence text. */
    public static final int MAX_EVIDENCE_LENGTH = 512;

    /** Largest number of selected resource codes one manifest may carry. */
    public static final int MAX_SELECTED_RESOURCES = 4;

    /** Largest number of component manifests one session may carry (one per capability). */
    public static final int MAX_MANIFESTS = CampaignCapability.values().length;

    private CampaignAgentLimits() {
    }
}