package com.company.campaign.conversation;

import java.util.List;

/**
 * The read-only history port of a durable Campaign conversation store.
 *
 * <p>It is a separate port from {@link CampaignConversationStore} for two reasons. First, listing and reading
 * conversations is a different capability from creating and advancing them, so the Create/Turn services keep
 * depending on the narrow mutation contract only. Second, a store that cannot enumerate its own conversations
 * cannot accidentally satisfy the left-sidebar history requirement by bean type: the production Campaign host
 * requires this exact interface <em>in addition to</em> the durable mutation store, so an in-memory registry or a
 * store without a real enumeration path fails startup closed instead of silently serving an empty list.
 *
 * <p>Both operations are scoped to the caller's trusted boundary. {@link #list} never returns a conversation
 * outside the supplied tenant/program/user, and {@link #read} is addressed by the full trusted key, so a
 * conversation identifier alone can never reach another boundary's session. Neither operation mutates anything:
 * they issue bounded read-only statements, take no write lock and stamp no timestamp.
 */
public interface CampaignConversationHistoryStore {

    /**
     * Lists the live conversations of one trusted boundary, most recently updated first, at most {@code limit}.
     *
     * <p>The bound is explicit and positive; an implementation returns fewer entries rather than exceeding it.
     * Reserved-but-turnless conversations are listed with {@code hasSession=false} and a null title, exactly as
     * they are stored.
     */
    List<CampaignConversationSummary> list(CampaignConversationBoundary boundary, int limit);

    /**
     * Reads the stored snapshot of one conversation, or null when the key holds no conversation at this
     * boundary.
     *
     * <p>A reserved conversation that has not accepted a turn still round-trips: its snapshot has a null session
     * and a zero workspace revision.
     */
    CampaignConversationSnapshot read(CampaignConversationKey key);
}