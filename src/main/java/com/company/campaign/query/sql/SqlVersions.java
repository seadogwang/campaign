package com.company.campaign.query.sql;

/**
 * Version discriminators stamped into every storage catalog and compiled SQL artifact.
 *
 * <p>Compilation is a deterministic, versioned transformation: a change to an allowlisting, lowering,
 * rendering or hashing rule is a new version, so a stored artifact can never be silently compared with one
 * produced under different rules. Every value is part of the compiled hash material.
 *
 * <p>{@link #CATALOG_FORMAT_VERSION} moved with T18 because the catalog document can now declare the optional
 * server-owned audience membership binding, and {@link #SQL_COMPILER_VERSION} moved because the compiler can
 * now lower an Existing Audience Reference to a parameterized membership {@code EXISTS}. The closed SQL AST
 * and the SELECT render format are unchanged: a reference reuses the {@code EXISTS} and {@code Comparison}
 * nodes and the placeholder rendering that already existed, so those two discriminators stay where they were.
 */
public final class SqlVersions {

    /** The storage catalog document format version. */
    public static final String CATALOG_FORMAT_VERSION = "segment-storage-catalog/1.2";

    /** The closed SQL AST discriminators' version. */
    public static final String SQL_AST_VERSION = "segment-sql-ast/1.1";

    /** The T09B PostgreSQL compiler version. */
    public static final String SQL_COMPILER_VERSION = "segment-sql-compiler/1.2";

    /** The deterministic PostgreSQL SELECT render-format version. */
    public static final String SQL_RENDER_FORMAT_VERSION = "postgresql-select/1.1";

    private SqlVersions() {
    }
}