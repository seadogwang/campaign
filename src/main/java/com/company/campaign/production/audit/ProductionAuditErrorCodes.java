package com.company.campaign.production.audit;

/**
 * The stable machine codes of the default-off production durable audit ledger.
 *
 * <p>They are a separate, additive vocabulary from the readiness, conversation, Preview and production identity
 * codes: enabling durable audit is host configuration, and a missing pool setting must be distinguishable from a
 * readiness probe failure, a membership lookup failure, a conversation store failure and a Preview failure. No
 * existing code is renamed, removed or reinterpreted.
 *
 * <p>Every code is bounded and non-disclosing: it never contains a JDBC URL, a credential, a schema name, a
 * tenant, a program, a subject, a member identifier, an event document or a driver message.
 */
public final class ProductionAuditErrorCodes {

    /** Durable audit was enabled but a required setting is absent, out of bounds or malformed. */
    public static final String PRODUCTION_AUDIT_CONFIGURATION_INVALID = "PRODUCTION_AUDIT_CONFIGURATION_INVALID";

    /** The configured schema is reserved and is never an acceptable durable audit boundary. */
    public static final String PRODUCTION_AUDIT_SCHEMA_RESERVED = "PRODUCTION_AUDIT_SCHEMA_RESERVED";

    /** The bounded PostgreSQL audit pool or its ordered migration run could not be established. */
    public static final String PRODUCTION_AUDIT_POOL_UNAVAILABLE = "PRODUCTION_AUDIT_POOL_UNAVAILABLE";

    /** More than one host-supplied adapter of one accepted audit port exists, so the wiring is ambiguous. */
    public static final String PRODUCTION_AUDIT_ADAPTER_AMBIGUOUS = "PRODUCTION_AUDIT_ADAPTER_AMBIGUOUS";

    /** A batch is empty, oversized, carries a null element or is otherwise outside the accepted bounds. */
    public static final String PRODUCTION_AUDIT_BATCH_INVALID = "PRODUCTION_AUDIT_BATCH_INVALID";

    /**
     * The same immutable logical event key already exists with different material, so the replay is a conflict.
     *
     * <p>Exact replay — the same logical key and the same canonical document — is never this code: it is
     * idempotent and writes nothing.
     */
    public static final String PRODUCTION_AUDIT_CONFLICT = "PRODUCTION_AUDIT_CONFLICT";

    /** A staged batch could not be identified as the exact uncommitted batch of this thread of control. */
    public static final String PRODUCTION_AUDIT_ROLLBACK_INVALID = "PRODUCTION_AUDIT_ROLLBACK_INVALID";

    /** A canonical source document or a canonical ledger document is malformed, unknown or non-canonical. */
    public static final String PRODUCTION_AUDIT_CODEC_INVALID = "PRODUCTION_AUDIT_CODEC_INVALID";

    /** An accepted source record's own canonical hash does not match its material, so it is refused. */
    public static final String PRODUCTION_AUDIT_SOURCE_HASH_MISMATCH = "PRODUCTION_AUDIT_SOURCE_HASH_MISMATCH";

    /** A JDBC, codec or binding failure at the ledger boundary; the cause is never disclosed. */
    public static final String PRODUCTION_AUDIT_UNAVAILABLE = "PRODUCTION_AUDIT_UNAVAILABLE";

    private ProductionAuditErrorCodes() {
    }
}