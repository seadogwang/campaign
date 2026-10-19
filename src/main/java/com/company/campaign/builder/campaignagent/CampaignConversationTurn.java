package com.company.campaign.builder.campaignagent;

/**
 * One accepted turn of the conversation transcript.
 *
 * <p>It carries a stable turn identifier, the role, the ordinal of the turn and bounded text: the user's own
 * utterance, bounded safe assistant copy, a clarification prompt or a host-generated status line. The optional
 * {@code at} instant is host-supplied presentation only — the core reads no clock — and it is never Campaign
 * state.
 */
public record CampaignConversationTurn(String turnId, String role, int ordinal, String text, String at) {

    /** The bounded user role. */
    public static final String USER = "USER";

    /** The bounded assistant role. */
    public static final String ASSISTANT = "ASSISTANT";

    /** Largest length of one transcript turn text. */
    public static final int MAX_TEXT_LENGTH = 512;

    public CampaignConversationTurn {
        if (turnId == null || turnId.isEmpty() || role == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turn",
                    "A conversation turn requires its identity and role.");
        }
        if (!USER.equals(role) && !ASSISTANT.equals(role)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnRole",
                    "A conversation turn role is either USER or ASSISTANT.");
        }
        if (ordinal < 0) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnOrdinal",
                    "A conversation turn ordinal must not be negative.");
        }
        if (text == null || text.isEmpty() || text.length() > MAX_TEXT_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnText",
                    "A conversation turn requires bounded non-empty text.");
        }
        if (at != null && (at.isEmpty() || at.length() > 64)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "turnInstant",
                    "A conversation turn instant must be bounded presentation text.");
        }
    }

    /** A bounded diagnostic: the role and the ordinal, never the text. */
    @Override
    public String toString() {
        return "CampaignConversationTurn[ordinal=" + ordinal + ", role=" + role + ']';
    }
}