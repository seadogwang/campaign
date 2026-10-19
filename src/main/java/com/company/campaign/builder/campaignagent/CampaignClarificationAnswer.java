package com.company.campaign.builder.campaignagent;

/**
 * One bounded user answer to a typed clarification question.
 *
 * <p>An answer chooses one bounded option, supplies bounded free text, or does both — an option that names a
 * categorical choice and free text that adds bounded detail. It carries no Tool argument, no capability
 * payload and no trusted fact: an answer is input to a later host-owned decision, never an instruction.
 *
 * <p>For multi-component clarifications, the answer carries the owning {@link CampaignComponentCode} so the
 * host can route each answer to exactly the component it belongs to. A legacy single-question answer omits
 * the component and is accepted only when exactly one component issue is pending. The backward-compatible
 * three-argument constructor ({@link #CampaignClarificationAnswer(String, String, String)}) preserves that
 * path and always sets component to {@code null}.
 */
public record CampaignClarificationAnswer(
        String code, String optionId, String freeText, CampaignComponentCode component) {

    /**
     * A backward-compatible three-argument constructor for genuine legacy single-question answers.
     *
     * <p>The component is set to {@code null}: this answer is unscoped. The host accepts an unscoped
     * answer only when exactly one component issue is pending.
     */
    public CampaignClarificationAnswer(String code, String optionId, String freeText) {
        this(code, optionId, freeText, null);
    }

    public CampaignClarificationAnswer {
        if (code != null && !code.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "answerCode",
                    "A clarification answer code must be a stable machine code.");
        }
        if (optionId != null && !optionId.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$")) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "answerOption",
                    "A clarification answer option must be a bounded opaque identifier.");
        }
        if (freeText != null) {
            if (freeText.isBlank() || freeText.length() > CampaignAgentLimits.MAX_ANSWER_LENGTH) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "answerText",
                        "A clarification answer text must be bounded and non-empty.");
            }
        }
        if (optionId == null && freeText == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "answer",
                    "A clarification answer must choose an option or supply bounded text.");
        }
    }

    /** A bounded diagnostic: which question was answered and in which form, never the text. */
    @Override
    public String toString() {
        return "CampaignClarificationAnswer[code=" + (code == null ? "-" : code)
                + ", option=" + (optionId == null ? "-" : optionId)
                + ", text=" + (freeText != null)
                + ", component=" + (component == null ? "-" : component.name()) + ']';
    }
}