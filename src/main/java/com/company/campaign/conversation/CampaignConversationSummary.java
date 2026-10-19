package com.company.campaign.conversation;

import java.time.Instant;
import java.util.Objects;

/**
 * One bounded list entry of a Campaign conversation history.
 *
 * <p>It carries only what a left-sidebar list needs: the server-issued conversation and run identity, the stored
 * session revision, whether an accepted turn has been recorded, the first-user-turn title (or null when the
 * conversation was reserved but has not accepted a turn), the accepted transcript turn count and the stored
 * created/updated instants. It carries no model output, no Tool artifact, no Proposal payload and no trusted
 * fact beyond the identity the caller's own boundary already owns.
 */
public record CampaignConversationSummary(
        String conversationId,
        String runId,
        long revision,
        boolean hasSession,
        String title,
        int turnCount,
        Instant createdAt,
        Instant updatedAt
) {

    /** The declared bound on one rendered conversation title. */
    public static final int MAX_TITLE_LENGTH = 120;

    public CampaignConversationSummary {
        Objects.requireNonNull(conversationId, "conversationId");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 0L || turnCount < 0) {
            throw ConversationStoreException.invalid(
                    ConversationStoreErrorCodes.SNAPSHOT_INVALID, "summary",
                    "A conversation summary revision and turn count must not be negative.");
        }
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH);
        }
    }

    /** A bounded diagnostic: the revision and the counts only, never a title or a value. */
    @Override
    public String toString() {
        return "CampaignConversationSummary[revision=" + revision
                + ", hasSession=" + hasSession
                + ", turns=" + turnCount + ']';
    }
}