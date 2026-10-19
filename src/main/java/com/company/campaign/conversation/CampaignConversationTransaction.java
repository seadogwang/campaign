package com.company.campaign.conversation;

import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * One exclusive transaction of one {@link CampaignConversationKey}.
 *
 * <p>A transaction is opened by {@link CampaignConversationStore#open} and releases its key only when it is
 * committed or closed, so exactly one turn runs per key at a time: two concurrent calls with the same expected
 * revision cannot both advance the session, because the second sees the first's committed revision and fails
 * closed as stale.
 *
 * <p>The session a transaction hands out is a defensive copy: a caller cannot mutate the stored session outside
 * the transaction, and a transaction that is closed without committing stores nothing at all.
 */
public interface CampaignConversationTransaction extends AutoCloseable {

    /** A defensive copy of the stored session, or null when the key has no session yet. */
    CampaignAgentSession session();

    /**
     * True only when this key was explicitly reserved through
     * {@link CampaignConversationStore#reserve(CampaignConversationCreateKey, CampaignConversationKey,
     * ContentHash)}.
     *
     * <p>A turn must never implicitly create a conversation, so a transaction over a key that was never
     * reserved reports {@code false} and the host turn boundary refuses it as a bounded absence instead of
     * staging a first revision.
     */
    boolean reserved();

    /** The stored outcome for one idempotency key, or null when the key was never used for this conversation. */
    CampaignStoredOutcome stored(String idempotencyKey);

    /**
     * Commits one turn atomically and releases the transaction.
     *
     * <p>The working session may be null when the turn refused before a session existed; the outcome is always
     * stored, so a later replay of the same key and fingerprint is byte-equivalent and calls no model or Tool.
     */
    void commit(CampaignAgentSession workingSession, String idempotencyKey, CampaignStoredOutcome outcome);

    /** Abandons the transaction, storing neither a session nor an outcome, and releases it. */
    void abort();

    /** Releases the transaction; closing without committing is equivalent to {@link #abort()}. */
    @Override
    void close();
}