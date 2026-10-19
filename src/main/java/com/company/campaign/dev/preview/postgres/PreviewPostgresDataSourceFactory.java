package com.company.campaign.dev.preview.postgres;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Builds the one bounded read-only PostgreSQL pool the opt-in Preview adapter uses.
 *
 * <p>Every resource limit is set from validated configuration rather than from a library default: the pool
 * size, the connection timeout, the validation timeout and the server-side {@code statement_timeout},
 * {@code lock_timeout} and {@code idle_in_transaction_session_timeout} are all bounded. The session additionally
 * runs with {@code default_transaction_read_only=on}, so the server itself refuses a write even if a future
 * adapter defect tried one, and the dedicated existing host schema is bound as the session
 * {@code search_path} through the PostgreSQL startup parameter list, so the connection is handed over with its
 * schema bound and no transaction begun.
 *
 * <p>The pool is created eagerly, so an unreachable or misconfigured server fails the application start rather
 * than surfacing later as a runtime surprise. Auto-commit is disabled at the pool level, so a connection
 * returned without an explicit rollback is rolled back by the pool. Because auto-commit is disabled, the pool
 * deliberately sets no {@code connectionInitSql}: a post-connect statement would begin a transaction and leave
 * it open on the handed-over connection, and PostgreSQL refuses the adapter's mandatory read-only switch while a
 * transaction is active (SQLSTATE 25001).
 *
 * <p>This factory creates no schema, runs no migration and owns no Flyway runtime: the Preview integration
 * reads an existing allowlisted host schema and must never create or mutate a business table.
 *
 * <p>The JDBC URL and the credential are never logged and never appear in a thrown message; the driver's
 * {@code options} parameter carries the session search path and the server-side bounds so no Preview session can
 * run unbounded.
 */
final class PreviewPostgresDataSourceFactory {

    /** The fixed pool name; it is a constant so no host value can reach a log line through it. */
    static final String POOL_NAME = "campaign-preview-postgres";

    private static final String POSTGRESQL_DRIVER = "org.postgresql.Driver";

    private PreviewPostgresDataSourceFactory() {
    }

    /** The bounded pool configuration for one validated properties object. */
    static HikariConfig configuration(PreviewPostgresProperties properties) {
        HikariConfig configuration = new HikariConfig();
        configuration.setPoolName(POOL_NAME);
        configuration.setDriverClassName(POSTGRESQL_DRIVER);
        configuration.setJdbcUrl(properties.getUrl());
        configuration.setUsername(properties.getUsername());
        configuration.setPassword(properties.getPassword());
        configuration.setMaximumPoolSize(properties.getMaximumPoolSize());
        configuration.setMinimumIdle(properties.getMinimumIdle());
        configuration.setConnectionTimeout(properties.getConnectionTimeoutMs());
        configuration.setValidationTimeout(properties.getValidationTimeoutMs());
        // A positive initialization timeout makes the pool prove reachability during construction.
        configuration.setInitializationFailTimeout(properties.getConnectionTimeoutMs());
        configuration.setRegisterMbeans(false);
        // Preview never commits, so the pool opens every connection outside auto-commit.
        configuration.setAutoCommit(false);
        configuration.setReadOnly(true);
        // The allowlisted schema is bound through the PostgreSQL startup parameter list, never a post-connect
        // connectionInitSql. Auto-commit is disabled, so a post-connect SET would begin a transaction and leave
        // it open on the handed-over connection; the adapter would then be refused when it marks the connection
        // read-only (SQLSTATE 25001, active_sql_transaction). A startup parameter is applied by the server while
        // it opens the session, before it can run a statement, so the connection arrives with its schema bound
        // and no transaction begun.
        configuration.addDataSourceProperty("options", sessionOptions(properties));
        configuration.addDataSourceProperty("ApplicationName", POOL_NAME);
        return configuration;
    }

    /** The resolved pool for one validated properties object, connected or refusing to start. */
    static HikariDataSource dataSource(PreviewPostgresProperties properties) {
        return new HikariDataSource(configuration(properties));
    }

    /**
     * The allowlisted schema bound as the session {@code search_path} value: the existing host schema first,
     * then {@code pg_catalog}.
     *
     * <p>The schema is already validated against {@link PreviewPostgresProperties#SCHEMA_PATTERN}, so it is a
     * bounded identifier and cannot carry a statement. It is quoted so the value is a valid PostgreSQL
     * identifier list even when a validated name happens to spell a keyword.
     */
    static String searchPath(String schema) {
        return "\"" + schema + "\",pg_catalog";
    }

    /**
     * The complete server-side startup parameter list of every Preview connection.
     *
     * <p>Every element is a PostgreSQL {@code -c} startup option, so the server applies it while it opens the
     * session — before the connection can run a statement and therefore before any transaction can exist. The
     * allowlisted schema is bound here rather than through {@code connectionInitSql}: with auto-commit disabled
     * a post-connect {@code SET} would leave a transaction active, and PostgreSQL refuses the adapter's
     * mandatory read-only switch while a transaction is open (SQLSTATE 25001, {@code active_sql_transaction}).
     */
    static String sessionOptions(PreviewPostgresProperties properties) {
        return "-c search_path=" + searchPath(properties.getSchema())
                + " " + serverSideBounds(properties);
    }

    /**
     * The server-side bounds of every Preview connection.
     *
     * <p>{@code default_transaction_read_only=on} makes the server itself enforce read-only for the whole
     * session; the statement, lock and idle-transaction timeouts stop a Preview statement, lock wait or abandoned
     * transaction from running unbounded.
     */
    static String serverSideBounds(PreviewPostgresProperties properties) {
        return "-c statement_timeout=" + properties.getStatementTimeoutMs()
                + " -c lock_timeout=" + properties.getLockTimeoutMs()
                + " -c idle_in_transaction_session_timeout=" + properties.getIdleTransactionTimeoutMs()
                + " -c default_transaction_read_only=on";
    }
}