package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;

/**
 * One bounded user turn submitted to a Campaign conversation.
 *
 * <p>It carries the caller's expected session revision, one bounded user message and the optional typed
 * clarification answers. It carries no campaign identity, no base revision, no permission, no Proposal and no
 * lifecycle action: none of those is a client-supplied fact here, and the optimistic revision is the only
 * concurrency token.
 */
public record CampaignAgentTurnRequest(
        long expectedRevision,
        String message,
        List<CampaignClarificationAnswer> answers,
        String at
) {

    public CampaignAgentTurnRequest {
        if (expectedRevision < 0) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "expectedRevision",
                    "An expected session revision must not be negative.");
        }
        if (message == null || message.isBlank() || message.length() > CampaignAgentLimits.MAX_UTTERANCE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "message",
                    "A turn requires one bounded non-empty user message.");
        }
        answers = answers == null ? List.of() : List.copyOf(new ArrayList<>(answers));
        if (answers.size() > CampaignAgentLimits.MAX_OPTIONS) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "answers",
                    "A turn exceeds its clarification-answer bound.");
        }
        if (at != null && (at.isEmpty() || at.length() > 64)) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "at",
                    "A turn instant must be bounded presentation text.");
        }
    }

    /** One turn that answers no clarification. */
    public static CampaignAgentTurnRequest of(long expectedRevision, String message) {
        return new CampaignAgentTurnRequest(expectedRevision, message, List.of(), null);
    }

    /** A bounded diagnostic: the revision and the counts, never the message. */
    @Override
    public String toString() {
        return "CampaignAgentTurnRequest[revision=" + expectedRevision
                + ", answers=" + answers.size() + ']';
    }
}