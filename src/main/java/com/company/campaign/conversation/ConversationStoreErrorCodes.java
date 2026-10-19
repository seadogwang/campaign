package com.company.campaign.conversation;

/**
 * Stable machine-readable codes of the local Campaign conversation registry.
 *
 * <p>They are bounded codes only: no code carries a conversation value, a session revision, an idempotency key
 * or a payload.
 */
public final class ConversationStoreErrorCodes {

    /** A conversation key is malformed or built from an unbounded identifier. */
    public static final String KEY_INVALID = "CAMPAIGN_CONVERSATION_KEY_INVALID";

    /** The registry has reached its bounded conversation count and cannot admit another session. */
    public static final String STORE_CAPACITY_EXCEEDED = "CAMPAIGN_CONVERSATION_STORE_CAPACITY_EXCEEDED";

    /** A create Idempotency-Key was replayed with a different request fingerprint. */
    public static final String CREATE_CONFLICT = "CAMPAIGN_CONVERSATION_CREATE_CONFLICT";

    /** A generated conversation identity already exists at this trusted boundary. */
    public static final String IDENTITY_COLLISION = "CAMPAIGN_CONVERSATION_IDENTITY_COLLISION";

    /** A reservation request was malformed or inconsistent with its trusted boundary. */
    public static final String RESERVATION_INVALID = "CAMPAIGN_CONVERSATION_RESERVATION_INVALID";

    // ------------------------------------------------------------------ durable snapshot vocabulary

    /** A stored session snapshot is structurally invalid, unknown-versioned or incoherent. */
    public static final String SNAPSHOT_INVALID = "CAMPAIGN_CONVERSATION_SNAPSHOT_INVALID";

    /** A stored session snapshot exceeds one of its declared byte or collection bounds. */
    public static final String SNAPSHOT_LIMIT_EXCEEDED = "CAMPAIGN_CONVERSATION_SNAPSHOT_LIMIT_EXCEEDED";

    /** A stored session snapshot contradicts its own content hash, or a re-encode differs from the bytes. */
    public static final String SNAPSHOT_INTEGRITY = "CAMPAIGN_CONVERSATION_SNAPSHOT_INTEGRITY";

    /** A stored idempotency outcome is malformed, oversized or inconsistent with its stored fingerprint. */
    public static final String OUTCOME_INVALID = "CAMPAIGN_CONVERSATION_OUTCOME_INVALID";

    // ------------------------------------------------------------------ durable store vocabulary

    /** A transaction tried to advance a session revision that the stored row no longer holds. */
    public static final String STALE_REVISION = "CAMPAIGN_CONVERSATION_STALE_REVISION";

    /** The persistence boundary could not complete the bounded transaction. */
    public static final String STORE_UNAVAILABLE = "CAMPAIGN_CONVERSATION_STORE_UNAVAILABLE";

    /** A conversation has reached its bounded number of stored idempotency outcomes. */
    public static final String OUTCOME_LIMIT_EXCEEDED = "CAMPAIGN_CONVERSATION_OUTCOME_LIMIT_EXCEEDED";

    /** The durable conversation retention policy is missing or out of bounds. */
    public static final String RETENTION_INVALID = "CAMPAIGN_CONVERSATION_RETENTION_INVALID";

    private ConversationStoreErrorCodes() {
    }
}