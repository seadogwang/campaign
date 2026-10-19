package com.company.campaign.readiness;

/**
 * Whether real PostgreSQL was actually exercised by this configured process.
 *
 * <p>This is deliberately separate from {@link ReadinessStorageMode}: being configured for PostgreSQL is not
 * the same as having proved it. A process that merely mentions a database URL, or that holds an in-memory
 * store, reports {@link #NOT_RUN} — an optimistic {@code PASSED} is unrepresentable because the value is
 * produced only by a bounded probe that reached the engine and found the ordered migration set applied.
 */
public enum ReadinessPostgresVerification {

    /** The configured engine answered a bounded probe and carries the ordered readiness migration set. */
    PASSED,

    /** No external PostgreSQL verification was performed in this configured environment. */
    NOT_RUN,

    /** PostgreSQL is configured but the bounded probe failed; the state is not proved either way. */
    FAILED
}