package com.company.campaign.dev.preview.postgres;

import com.company.campaign.evaluation.segment.contract.MemberIdSet;
import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.preview.PreviewErrorCodes;
import com.company.campaign.preview.PreviewException;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.plan.TrustedPlanContext;
import com.company.campaign.query.sql.CompiledSql;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.SqlExpression;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.security.context.TrustedExecutionContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The genuine opt-in PostgreSQL {@link PreviewExecutionBackend}.
 *
 * <p>It compiles the accepted T08 {@link SemanticQueryPlan} once through the accepted T09B {@link SqlCompiler},
 * then executes <em>only</em> that immutable artifact — its exact {@code sql()} text with its exact ordered
 * {@link com.company.campaign.query.sql.SqlParameter} list. No SQL text, table, column, value, catalog, plan,
 * time or identity fact can arrive from a request or a model: every input is constructor-injected and
 * server-owned, and the request boundary has no field that could reach any of them.
 *
 * <p>One execution is strictly read-only and strictly bounded: it sets JDBC read-only, attempts a bounded
 * isolation level, re-proves the connection reports itself read-only and reports the configured database
 * product/version, applies the statement query timeout, fetch size and maximum row count before executing,
 * binds every parameter with its exact JDBC type, reads exactly the one expected member-id column, and always
 * rolls back and closes. Preview never commits.
 *
 * <p>Every failure — a plan/context pin mismatch, an unresolvable, stale or incoherent watermark, a
 * compilation failure, a boundary violation, a driver failure, a malformed or duplicated member identifier, or
 * too many rows — becomes one bounded {@link PreviewBackendException.Kind}. No JDBC URL, SQL fragment, bind
 * parameter value, member identifier, trusted identifier, catalog identifier or exception text is ever copied
 * into a thrown message.
 *
 * <p>A plan that freezes an Existing Audience Reference is refused before compilation: this adapter has no
 * host reference-execution guard to re-prove a withdrawn version, a lost permission, a released retention hold
 * or a moved effective DataScope, so it fails closed rather than compiling a weakly guarded query.
 *
 * <p>It is deliberately registered only by the explicit, default-off
 * {@code campaign.agent.development.preview.postgres} configuration. No fixture data, fixture mapping, H2
 * engine, embedded database or synthetic dataset is reachable from this class or from any main wiring.
 */
public final class PostgresPreviewExecutionBackend implements PreviewExecutionBackend {

    /** The stable server-owned identity of this adapter, bound into every result it publishes. */
    public static final String BACKEND_ID = "preview-postgres-backend";

    private final DataSource dataSource;
    private final StorageCatalog storageCatalog;
    private final SqlCompiler sqlCompiler;
    private final SqlCompilePolicy sqlCompilePolicy;
    private final PreviewBackendIdentity identity;
    private final PreviewDataWatermarkProvider dataWatermarkProvider;
    private final PreviewPostgresExecutionPolicy executionPolicy;
    private final PreviewFxBindingResolver fxBindingResolver;

    /**
     * Creates the one PostgreSQL Preview adapter from explicit, server-owned inputs.
     *
     * <p>The FX binding resolver is the single deliberately optional input: a reference-free plan that needs no
     * currency conversion compiles without it, while a plan that needs the conversion fails closed at
     * compilation rather than approximating a rate. Every other input is mandatory.
     */
    public PostgresPreviewExecutionBackend(
            DataSource dataSource,
            StorageCatalog storageCatalog,
            SqlCompiler sqlCompiler,
            SqlCompilePolicy sqlCompilePolicy,
            PreviewBackendIdentity identity,
            PreviewDataWatermarkProvider dataWatermarkProvider,
            PreviewPostgresExecutionPolicy executionPolicy,
            PreviewFxBindingResolver fxBindingResolver) {
        this.dataSource = require(dataSource, "previewDataSource");
        this.storageCatalog = require(storageCatalog, "previewStorageCatalog");
        this.sqlCompiler = require(sqlCompiler, "previewSqlCompiler");
        this.sqlCompilePolicy = require(sqlCompilePolicy, "previewSqlCompilePolicy");
        this.identity = require(identity, "previewBackendIdentity");
        this.dataWatermarkProvider = require(dataWatermarkProvider, "previewDataWatermarkProvider");
        this.executionPolicy = require(executionPolicy, "previewExecutionPolicy");
        this.fxBindingResolver = fxBindingResolver;
    }

    private static <T> T require(T dependency, String invariant) {
        if (dependency == null) {
            throw PreviewException.of(PreviewErrorCodes.DEPENDENCY_MISSING, invariant);
        }
        return dependency;
    }

    @Override
    public PreviewBackendIdentity identity() {
        return identity;
    }

    @Override
    public PreviewBackendResult execute(SemanticQueryPlan plan, TrustedExecutionContext context) {
        if (plan == null || context == null) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        try {
            // 1. Re-prove every plan/context pin, and refuse a plan this adapter cannot guard, before the
            //    compiler is even called.
            requirePlanPins(plan, context);

            // 2. The host-owned watermark must exist, agree with the frozen plan pin and be inside the bound.
            Instant watermark = requireWatermark(plan, context);

            // 3. Compile the genuine plan exactly once through the accepted compiler.
            CompiledSql compiled = compile(plan);
            requireCompiledPins(compiled, plan);

            // 4. Execute only the immutable artifact, read-only and bounded.
            MemberIdSet memberIds = executeReadOnly(compiled);

            // 5. Publish the coherent, pinned result. Condition statistics stay honestly empty: the accepted
            //    compiled query computes no per-node counts, and no count is ever invented.
            return PreviewBackendResult.publish(
                    memberIds,
                    List.of(),
                    plan.planHash(),
                    plan.metadataSnapshotId(),
                    plan.dataScopePredicate().dataScopeId(),
                    context.snapshotRef().asOf(),
                    context.snapshotRef().timezone().getId(),
                    watermark,
                    identity.id(),
                    identity.version());
        } catch (PreviewBackendException backendFailure) {
            // A genuine bounded backend outcome is preserved verbatim; it is never relabelled.
            throw backendFailure;
        } catch (RuntimeException exception) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    // ------------------------------------------------------------------ plan pins

    /** Re-proves the genuine plan's trusted context against the caller's trusted context. */
    private static void requirePlanPins(SemanticQueryPlan plan, TrustedExecutionContext context) {
        TrustedPlanContext planContext = plan.trustedContext();
        if (planContext == null || context.snapshotRef() == null || plan.dataScopePredicate() == null) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        if (plan.hasAudienceReferences()) {
            // No host reference-execution guard is injectable here, so a reference-bearing plan cannot be
            // executed with the mandatory re-check and is refused instead of being weakened.
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        if (!planContext.tenantId().equals(context.tenantId())
                || !planContext.programId().equals(context.programId())
                || !planContext.dataScopeId().equals(context.dataScopeId())
                || !planContext.asOf().equals(context.snapshotRef().asOf())
                || !planContext.dataWatermark().equals(context.snapshotRef().dataWatermark())
                || !planContext.timezone().equals(context.snapshotRef().timezone().getId())
                || !plan.metadataSnapshotId().equals(context.snapshotRef().metadataSnapshotId())
                || !plan.dataScopePredicate().dataScopeId().equals(context.dataScopeId())) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    // ------------------------------------------------------------------ watermark

    /**
     * Resolves the host-owned watermark and re-proves it against the frozen plan pin and the execution bound.
     *
     * <p>The published result must carry exactly the watermark the trusted context pinned, so a host provider
     * that cannot vouch for that instant fails closed instead of producing a self-consistent but unsupported
     * result. A watermark after {@code asOf} is incoherent, and one further behind than the configured bound is
     * stale; both are refused.
     */
    private Instant requireWatermark(SemanticQueryPlan plan, TrustedExecutionContext context) {
        Instant watermark;
        try {
            watermark = dataWatermarkProvider.dataWatermark(context);
        } catch (RuntimeException exception) {
            throw failure(PreviewBackendException.Kind.UNAVAILABLE);
        }
        Instant asOf = context.snapshotRef().asOf();
        if (watermark == null || watermark.isAfter(asOf)) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        if (!watermark.equals(plan.trustedContext().dataWatermark())) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        Duration age = Duration.between(watermark, asOf);
        if (age.compareTo(executionPolicy.maxDataWatermarkAge()) > 0) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        return watermark;
    }

    // ------------------------------------------------------------------ compilation

    private CompiledSql compile(SemanticQueryPlan plan) {
        try {
            if (fxBindingResolver == null) {
                return sqlCompiler.compile(plan, storageCatalog, sqlCompilePolicy);
            }
            return sqlCompiler.compile(plan, storageCatalog, sqlCompilePolicy, fxBindingResolver.resolve(plan));
        } catch (RuntimeException exception) {
            // Every SqlException, catalog conflict, guard refusal, FX failure and compiler invariant becomes
            // the one bounded backend outcome; no compiler diagnostic reaches the caller.
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    /** Re-proves the artifact echoes exactly this plan and this catalog, and is one read-only SELECT. */
    private void requireCompiledPins(CompiledSql compiled, SemanticQueryPlan plan) {
        if (compiled == null
                || !compiled.planHash().equals(plan.planHash())
                || !compiled.catalogVersion().equals(storageCatalog.catalogVersion())
                || !compiled.catalogHash().equals(storageCatalog.contentHash())
                || !PreviewSqlBoundary.isSingleReadOnlySelect(compiled.sql())) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    // ------------------------------------------------------------------ read-only execution

    /**
     * Executes the one immutable artifact inside a read-only transaction that is always rolled back and closed.
     *
     * <p>The order is literal: acquire, disable auto-commit, set read-only and a bounded isolation level,
     * re-prove the connection is read-only and is the configured database product/version, prepare only the
     * artifact's own text, apply the query timeout, fetch size and maximum row count, bind every parameter in
     * order, execute, then read exactly the one expected member-id column.
     */
    private MemberIdSet executeReadOnly(CompiledSql compiled) {
        String expectedLabel = expectedMemberLabel(compiled);
        Connection connection = acquireConnection();
        PreparedStatement statement = null;
        ResultSet resultSet = null;
        try {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            setBoundedIsolation(connection);
            if (!connection.isReadOnly()) {
                throw failure(PreviewBackendException.Kind.INVALID_RESULT);
            }
            requireDatabaseProduct(connection);

            statement = connection.prepareStatement(compiled.sql());
            statement.setQueryTimeout(executionPolicy.queryTimeoutSeconds());
            statement.setFetchSize(executionPolicy.fetchSize());
            statement.setMaxRows(executionPolicy.maxReadRows());
            PreviewJdbcBinding.bind(statement, compiled.parameters());
            resultSet = statement.executeQuery();
            return readMembers(resultSet, expectedLabel);
        } catch (SQLException exception) {
            throw mapSqlFailure(exception);
        } finally {
            closeQuietly(resultSet);
            closeQuietly(statement);
            rollbackAndClose(connection);
        }
    }

    private Connection acquireConnection() {
        try {
            Connection connection = dataSource.getConnection();
            if (connection == null) {
                throw failure(PreviewBackendException.Kind.UNAVAILABLE);
            }
            return connection;
        } catch (SQLException exception) {
            throw failure(PreviewBackendException.Kind.UNAVAILABLE);
        } catch (RuntimeException exception) {
            throw failure(PreviewBackendException.Kind.UNAVAILABLE);
        }
    }

    /**
     * Attempts one bounded, explicitly non-dirty isolation level.
     *
     * <p>It is best-effort by design: a pool that refuses to change the isolation level of an already
     * read-only connection cannot weaken the read-only guarantee, which is re-proved separately, so a refusal
     * here is not a Preview failure.
     */
    private static void setBoundedIsolation(Connection connection) {
        try {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        } catch (SQLException ignored) {
            // The read-only re-proof below is the guarantee; isolation is an additional bound.
        }
    }

    private void requireDatabaseProduct(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        if (metaData == null
                || !executionPolicy.requiredDatabaseProduct().equals(metaData.getDatabaseProductName())
                || metaData.getDatabaseMajorVersion() < executionPolicy.minimumDatabaseMajorVersion()) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    /** The one exact result column label the accepted compiled artifact contract declares. */
    private static String expectedMemberLabel(CompiledSql compiled) {
        if (compiled.ast() == null || !(compiled.ast().projection() instanceof SqlExpression.Column column)) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        return column.column().value();
    }

    /**
     * Reads exactly the one expected member-id column into a canonical sorted set.
     *
     * <p>A missing or extra column, a differently labelled column, a null or blank identifier, a malformed
     * identifier, a duplicate identifier and a row beyond the explicit maximum are all refused; the accepted
     * {@link MemberIdSet} then sorts the identifiers independently of database row order.
     */
    private MemberIdSet readMembers(ResultSet resultSet, String expectedLabel) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        if (metaData == null || metaData.getColumnCount() != 1) {
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        String label = metaData.getColumnLabel(1);
        if (label == null || !label.equals(expectedLabel)) {
            // The result must project exactly the one member-id column the artifact contract declares; any
            // other shape is an unexpected result and not a member set this boundary can publish.
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
        List<String> memberIds = new ArrayList<>();
        while (resultSet.next()) {
            if (memberIds.size() >= executionPolicy.maxRows()) {
                // One row past the bound proves the result outgrew the policy and the whole Preview fails.
                throw failure(PreviewBackendException.Kind.INVALID_RESULT);
            }
            String memberId = resultSet.getString(1);
            if (memberId == null || memberId.isEmpty()) {
                throw failure(PreviewBackendException.Kind.INVALID_RESULT);
            }
            memberIds.add(memberId);
        }
        try {
            return new MemberIdSet(memberIds);
        } catch (RuntimeException exception) {
            // A malformed or duplicated identifier is an unusable result, never a client-visible diagnostic.
            throw failure(PreviewBackendException.Kind.INVALID_RESULT);
        }
    }

    // ------------------------------------------------------------------ failure mapping and cleanup

    private static PreviewBackendException mapSqlFailure(SQLException exception) {
        if (exception instanceof SQLTimeoutException) {
            return failure(PreviewBackendException.Kind.TIMEOUT);
        }
        if (exception instanceof SQLTransientException
                || exception instanceof SQLNonTransientConnectionException) {
            return failure(PreviewBackendException.Kind.UNAVAILABLE);
        }
        return failure(PreviewBackendException.Kind.INVALID_RESULT);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // A close failure on an already-finished read changes nothing about the outcome.
        }
    }

    /** Preview never commits: every path rolls back and then releases the connection. */
    private static void rollbackAndClose(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // A rollback failure cannot turn a read-only execution into a writer.
        } finally {
            closeQuietly(connection);
        }
    }

    private static PreviewBackendException failure(PreviewBackendException.Kind kind) {
        return new PreviewBackendException(Objects.requireNonNull(kind, "kind"));
    }
}
