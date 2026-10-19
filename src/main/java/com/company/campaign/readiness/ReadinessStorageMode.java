package com.company.campaign.readiness;

/**
 * The closed set of storage modes the running application can report.
 *
 * <p>It has exactly two members and no {@code UNKNOWN}: a process either holds a real PostgreSQL readiness
 * store or it holds the deterministic in-memory adapter. There is no third "probably a database" value, so a
 * report can never hedge between a real engine and a substitute.
 */
public enum ReadinessStorageMode {

    /** The deterministic in-memory adapter is active; no external engine is in use. */
    IN_MEMORY,

    /** A real PostgreSQL-backed {@link MyBatisPlusReadinessStore} is wired. */
    POSTGRESQL
}