package com.company.campaign.dev.preview.postgres;

/**
 * The stable machine codes of the opt-in development PostgreSQL Preview integration.
 *
 * <p>They are a separate, additive vocabulary from {@code ConversationPostgresErrorCodes},
 * {@code ReadinessPostgresErrorCodes} and {@code PreviewErrorCodes}: enabling a real read-only Preview database
 * is host configuration, and a missing connection string must be distinguishable from a durable-store failure,
 * a readiness probe failure and a {@code SegmentPreviewService} execution failure. No existing code is renamed,
 * removed or reinterpreted.
 */
public final class PreviewPostgresErrorCodes {

    /** The host enabled the PostgreSQL Preview mode but a required setting is absent, malformed or out of bounds. */
    public static final String PREVIEW_POSTGRESQL_CONFIGURATION_INVALID =
            "PREVIEW_POSTGRESQL_CONFIGURATION_INVALID";

    /** The bounded read-only Preview pool could not be established. */
    public static final String PREVIEW_POSTGRESQL_POOL_UNAVAILABLE =
            "PREVIEW_POSTGRESQL_POOL_UNAVAILABLE";

    /**
     * The server-owned physical storage catalog the Preview adapter compiles against could not be resolved.
     *
     * <p>It covers both an explicitly configured resource that cannot be read or published and an incoherent
     * development catalog; the message never echoes a path, a table or a column.
     */
    public static final String PREVIEW_POSTGRESQL_CATALOG_UNAVAILABLE =
            "PREVIEW_POSTGRESQL_CATALOG_UNAVAILABLE";

    private PreviewPostgresErrorCodes() {
    }
}