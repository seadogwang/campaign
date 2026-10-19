package com.company.campaign.conversation.postgres;

/**
 * The stable machine codes of the opt-in PostgreSQL Campaign conversation configuration.
 *
 * <p>They are a separate, additive vocabulary from both {@code ReadinessPostgresErrorCodes} and
 * {@code ConversationStoreErrorCodes}: enabling a durable conversation database is host configuration, and a
 * missing connection string must be distinguishable from a readiness probe failure and from a store failure.
 * No existing code is renamed, removed or reinterpreted.
 */
public final class ConversationPostgresErrorCodes {

    /** The host selected PostgreSQL mode but a required setting is absent, out of bounds or reserved. */
    public static final String CONVERSATION_POSTGRESQL_CONFIGURATION_INVALID =
            "CONVERSATION_POSTGRESQL_CONFIGURATION_INVALID";

    /** The bounded PostgreSQL pool or migration run could not be established. */
    public static final String CONVERSATION_POSTGRESQL_POOL_UNAVAILABLE =
            "CONVERSATION_POSTGRESQL_POOL_UNAVAILABLE";

    private ConversationPostgresErrorCodes() {
    }
}