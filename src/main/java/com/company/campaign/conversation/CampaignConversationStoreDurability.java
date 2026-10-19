package com.company.campaign.conversation;

/**
 * The closed durability claim of one Campaign conversation store.
 *
 * <p>It is small and closed on purpose: a production Campaign host has exactly one accepted durability claim to
 * inspect, and no free-form text, credential, URL, schema name, host name or payload can be smuggled into a
 * startup decision. {@link #POSTGRESQL} is the only accepted claim; it describes the adapter and its storage
 * mechanism — the committed Campaign conversation schema — and nothing more. It is deliberately <em>not</em>
 * evidence that a live database was reached or exercised in any given session, and a host must never present it
 * as live readiness evidence.
 *
 * <p>{@link #IN_MEMORY}, {@link #DEVELOPMENT_ONLY} and {@link #UNVERIFIED} are the honest states of a store that
 * cannot carry a production conversation, and an absent claim — a store that exposes none — is refused the same
 * way. Every non-accepted state is rejected by the production boundary with the one bounded
 * {@code DURABLE_STORE_REQUIRED} code rather than being trusted.
 */
public enum CampaignConversationStoreDurability {

    /** The accepted claim: the store is the committed PostgreSQL conversation adapter over its schema. */
    POSTGRESQL,

    /** The store holds conversations only in the JVM heap and survives no process restart. */
    IN_MEMORY,

    /** The store is a development-only substitute that no production decision has accepted. */
    DEVELOPMENT_ONLY,

    /** The store asserts durability that has not been verified against an accepted adapter. */
    UNVERIFIED;

    /** True only for the one accepted PostgreSQL adapter claim. */
    public boolean accepted() {
        return this == POSTGRESQL;
    }
}