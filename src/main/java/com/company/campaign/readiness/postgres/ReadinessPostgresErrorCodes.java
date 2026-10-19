package com.company.campaign.readiness.postgres;

/**
 * The stable machine codes of the opt-in PostgreSQL readiness configuration.
 *
 * <p>They are a separate, additive vocabulary from the T25 {@code ReadinessErrorCodes}: enabling a database
 * is host configuration, not a readiness-model failure, and reusing a readiness model code for a missing
 * connection string would make the two indistinguishable to a reader or a metric. No T25 code is renamed,
 * removed or reinterpreted.
 */
public final class ReadinessPostgresErrorCodes {

    /** The host enabled PostgreSQL mode but a required setting is absent, out of bounds or reserved. */
    public static final String READINESS_POSTGRESQL_CONFIGURATION_INVALID =
            "READINESS_POSTGRESQL_CONFIGURATION_INVALID";

    /** The bounded PostgreSQL probe could not reach or read the configured engine. */
    public static final String READINESS_POSTGRESQL_PROBE_UNAVAILABLE =
            "READINESS_POSTGRESQL_PROBE_UNAVAILABLE";

    private ReadinessPostgresErrorCodes() {
    }
}