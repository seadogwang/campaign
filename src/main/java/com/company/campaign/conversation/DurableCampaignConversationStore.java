package com.company.campaign.conversation;

/**
 * The durable Campaign conversation store port.
 *
 * <p>It exists so a production wiring requirement can be expressed as an inspectable claim rather than as a
 * runtime name check, a comment or a bare Java type: a production Campaign host that requires durable
 * conversation persistence resolves this interface <em>and</em> validates {@link #durability()}, so an in-memory
 * registry — which implements only {@link CampaignConversationStore} and exposes no accepted claim — can neither
 * satisfy the type nor pass the claim check. A sufficient implementation is the accepted
 * {@link MyBatisPlusCampaignConversationStore} over the committed PostgreSQL conversation schema, and a host that owns
 * its own durable store implements this interface directly and exposes the accepted claim only when that claim is
 * true of its storage.
 *
 * <p>Beyond that one claim it adds no behavior of its own: every reservation, transaction, optimistic-revision
 * and idempotency guarantee is still the one declared by {@link CampaignConversationStore}.
 */
public interface DurableCampaignConversationStore extends CampaignConversationStore {

    /**
     * The durability claim of this store, or {@code null} when it exposes none.
     *
     * <p>The production host accepts only {@link CampaignConversationStoreDurability#POSTGRESQL} and fails closed
     * otherwise. The claim describes the adapter and its storage mechanism only: it is never evidence that a live
     * database was reached, and it is not live readiness evidence.
     */
    CampaignConversationStoreDurability durability();
}