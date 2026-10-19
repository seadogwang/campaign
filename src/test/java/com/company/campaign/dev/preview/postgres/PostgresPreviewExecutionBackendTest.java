package com.company.campaign.dev.preview.postgres;

import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.preview.PreviewBackendException;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewBackendResult;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.sql.CompiledSql;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.SqlTestSupport;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T34C requirements 1 and 3: the PostgreSQL adapter compiles the genuine plan once, executes only the immutable
 * artifact inside one bounded read-only transaction that always rolls back and closes, binds every parameter in
 * order, produces a canonical sorted pinned result and maps every failure to a bounded, redacted outcome.
 *
 * <p>The JDBC boundary is explicitly a strict scripted double and not database evidence; real PostgreSQL is
 * exercised only by the environment-gated integration test.
 */
class PostgresPreviewExecutionBackendTest {

    private static final StorageCatalog CATALOG = SqlTestSupport.standardCatalog();
    private static final PreviewBackendIdentity IDENTITY =
            new PreviewBackendIdentity(PostgresPreviewExecutionBackend.BACKEND_ID, "1");
    private static final PreviewPostgresExecutionPolicy POLICY =
            new PreviewPostgresExecutionPolicy(10, 100, 1_000, 86_400L, "PostgreSQL", 12);

    // ------------------------------------------------------------------ success

    @Test
    void oneReadOnlyBoundedExecutionPublishesACanonicalSortedPinnedResult() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .withRows("member-2", "member-1");
        SemanticQueryPlan plan = plan();
        TrustedExecutionContext context = SegmentValidationFixtures.context();
        CompiledSql expected = compile(plan);

        PreviewBackendResult result = backend(boundary, watermarkOfContext()).execute(plan, context);

        assertThat(result.memberIdSet().memberIds()).containsExactly("member-1", "member-2");
        assertThat(result.matchedCount()).isEqualTo(2);
        assertThat(result.conditionStatistics()).isEmpty();
        assertThat(result.planHash()).isEqualTo(plan.planHash());
        assertThat(result.metadataSnapshotId()).isEqualTo(plan.metadataSnapshotId());
        assertThat(result.dataScopeId()).isEqualTo(plan.dataScopePredicate().dataScopeId());
        assertThat(result.asOf()).isEqualTo(context.snapshotRef().asOf());
        assertThat(result.timezone()).isEqualTo(context.snapshotRef().timezone().getId());
        assertThat(result.dataWatermark()).isEqualTo(context.snapshotRef().dataWatermark());
        assertThat(result.backendId()).isEqualTo(PostgresPreviewExecutionBackend.BACKEND_ID);
        assertThat(result.backendVersion()).isEqualTo("1");

        assertThat(boundary.preparedSql()).isEqualTo(expected.sql());
        assertThat(boundary.bindings()).hasSameSizeAs(expected.parameters());

        assertThat(boundary.calls()).containsSubsequence(
                "getConnection",
                "setAutoCommit(false)",
                "setReadOnly(true)",
                "setTransactionIsolation(2)",
                "isReadOnly",
                "getMetaData",
                "prepareStatement",
                "setQueryTimeout(10)",
                "setFetchSize(100)",
                "setMaxRows(1001)",
                "executeQuery",
                "rollback",
                "close");
        assertThat(boundary.rolledBack()).isTrue();
        assertThat(boundary.connectionClosed()).isTrue();
        assertThat(boundary.statementClosed()).isTrue();
        assertThat(boundary.resultSetClosed()).isTrue();
        // Every bounded setting is applied before the statement is executed.
        assertThat(boundary.calls().indexOf("executeQuery"))
                .isGreaterThan(boundary.calls().indexOf("setMaxRows(1001)"));
    }

    @Test
    void anEmptyResultIsAPublishableHonestAnswer() {
        PreviewBackendResult result = backend(new ScriptedPreviewJdbcBoundary(), watermarkOfContext())
                .execute(plan(), SegmentValidationFixtures.context());

        assertThat(result.matchedCount()).isZero();
        assertThat(result.memberIdSet().memberIds()).isEmpty();
        assertThat(result.conditionStatistics()).isEmpty();
    }

    // ------------------------------------------------------------------ result boundary

    @Test
    void duplicateNullBlankMalformedAndWrongShapeRowsAreRefused() {
        assertRefused(new ScriptedPreviewJdbcBoundary().withRows("member-1", "member-1"));
        assertRefused(new ScriptedPreviewJdbcBoundary().withRows("member-1", null));
        assertRefused(new ScriptedPreviewJdbcBoundary().withRows(""));
        assertRefused(new ScriptedPreviewJdbcBoundary().withRows("not a valid identifier!"));
        assertRefused(new ScriptedPreviewJdbcBoundary().withColumnLabel("other_column"));
        assertRefused(new ScriptedPreviewJdbcBoundary().withColumnCount(2));
    }

    @Test
    void rowsBeyondTheExplicitMaximumAreRefusedRatherThanTruncated() {
        PreviewPostgresExecutionPolicy small = new PreviewPostgresExecutionPolicy(
                10, 100, 2, 86_400L, "PostgreSQL", 12);
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .withRows("member-1", "member-2", "member-3");

        assertRefusedKind(
                () -> backend(boundary, small, watermarkOfContext())
                        .execute(plan(), SegmentValidationFixtures.context()),
                PreviewBackendException.Kind.INVALID_RESULT);
        assertThat(boundary.rolledBack()).isTrue();
        assertThat(boundary.connectionClosed()).isTrue();
    }

    // ------------------------------------------------------------------ read-only and product boundary

    @Test
    void aConnectionThatDoesNotReportItselfReadOnlyIsRefused() {
        assertRefused(new ScriptedPreviewJdbcBoundary()
                .withRows("member-1").withReadOnlyReported(false));
    }

    @Test
    void anUnexpectedDatabaseProductOrVersionIsRefused() {
        assertRefused(new ScriptedPreviewJdbcBoundary()
                .withRows("member-1").withDatabase("MySQL", 15));
        assertRefused(new ScriptedPreviewJdbcBoundary()
                .withRows("member-1").withDatabase("PostgreSQL", 11));
    }

    // ------------------------------------------------------------------ pins and watermark

    @Test
    void aPlanFromAnotherTrustedContextIsRefusedBeforeCompilation() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary().withRows("member-1");

        assertRefusedKind(
                () -> backend(boundary, watermarkOfContext()).execute(plan(), foreignContext()),
                PreviewBackendException.Kind.INVALID_RESULT);
        assertThat(boundary.calls()).doesNotContain("prepareStatement");
    }

    @Test
    void aMissingOrIncoherentWatermarkFailsClosedBeforePreparation() {
        assertRefusedFromWatermark(new ScriptedPreviewJdbcBoundary().withRows("member-1"), context -> null);
        assertRefusedFromWatermark(new ScriptedPreviewJdbcBoundary().withRows("member-1"),
                context -> context.snapshotRef().asOf());
    }

    @Test
    void aWatermarkOlderThanTheExplicitBoundIsStaleAndRefused() {
        PreviewPostgresExecutionPolicy strict = new PreviewPostgresExecutionPolicy(
                10, 100, 1_000, 60L, "PostgreSQL", 12);
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary().withRows("member-1");
        SemanticQueryPlan plan = plan();
        TrustedExecutionContext context = SegmentValidationFixtures.context();
        Instant pinned = plan.trustedContext().dataWatermark();

        assertRefusedKind(
                () -> backend(boundary, strict, ignored -> pinned).execute(plan, context),
                PreviewBackendException.Kind.INVALID_RESULT);
    }

    // ------------------------------------------------------------------ failure mapping and redaction

    @Test
    void aStatementTimeoutMapsToTheBoundedTimeoutKindAndStillRollsBack() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .failingOnExecute(new SQLTimeoutException("timeout"));

        assertRefusedKind(
                () -> backend(boundary, watermarkOfContext())
                        .execute(plan(), SegmentValidationFixtures.context()),
                PreviewBackendException.Kind.TIMEOUT);
        assertThat(boundary.rolledBack()).isTrue();
        assertThat(boundary.connectionClosed()).isTrue();
    }

    @Test
    void aDriverFailureNeverDisclosesTheUrlSqlValueOrMemberIdentifier() {
        String url = "jdbc:postgresql://preview-host.example:5432/loyalty";
        String memberId = "member-secret-7f3a";
        String sqlFragment = "SELECT m.\"member_id\"";
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .failingOnExecute(new SQLException(
                        "The statement failed at " + url + " running " + sqlFragment
                                + " with value " + memberId));

        Throwable thrown = catchThrowable(
                () -> backend(boundary, watermarkOfContext()).execute(plan(), SegmentValidationFixtures.context()));

        assertThat(thrown).isInstanceOf(PreviewBackendException.class);
        String text = thrown + " " + thrown.getMessage();
        assertThat(text)
                .doesNotContain(url)
                .doesNotContain("preview-host.example")
                .doesNotContain(memberId)
                .doesNotContain(sqlFragment)
                .doesNotContain("SELECT");
        assertThat(thrown.getCause()).isNull();
    }

    @Test
    void anUnreachablePoolMapsToTheBoundedUnavailableKind() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary().failingToConnect();

        assertRefusedKind(
                () -> backend(boundary, watermarkOfContext())
                        .execute(plan(), SegmentValidationFixtures.context()),
                PreviewBackendException.Kind.UNAVAILABLE);
    }

    @Test
    void aReadOnlySwitchRefusedInsideAnActiveTransactionBecomesOneBoundedRedactedFailure() {
        // PostgreSQL refuses the read-only switch on a connection that already has an active transaction with
        // SQLSTATE 25001 (active_sql_transaction). The Preview pool deliberately hands over no such connection
        // (it binds the schema through a startup parameter, never a post-connect connectionInitSql); this proves
        // that if one ever reaches the adapter it still fails closed, before any statement, and discloses nothing.
        String url = "jdbc:postgresql://preview-host.example:5432/loyalty";
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .withRows("member-1")
                .failingToSetReadOnly(new SQLException(
                        "Cannot change transaction read-only property in the middle of a transaction at " + url,
                        "25001"));

        Throwable thrown = catchThrowable(
                () -> backend(boundary, watermarkOfContext())
                        .execute(plan(), SegmentValidationFixtures.context()));

        assertThat(thrown).isInstanceOf(PreviewBackendException.class);
        assertThat(((PreviewBackendException) thrown).kind())
                .isEqualTo(PreviewBackendException.Kind.INVALID_RESULT);
        String text = thrown + " " + thrown.getMessage();
        assertThat(text)
                .doesNotContain(url)
                .doesNotContain("preview-host.example")
                .doesNotContain("25001")
                .doesNotContain("transaction")
                .doesNotContain("read-only");
        assertThat(thrown.getCause()).isNull();
        assertThat(boundary.calls()).doesNotContain("prepareStatement");
        assertThat(boundary.rolledBack()).isTrue();
        assertThat(boundary.connectionClosed()).isTrue();
    }

    // ------------------------------------------------------------------ the SQL execution boundary

    @Test
    void theAdapterAcceptsOnlyOneGenuineReadOnlySelect() {
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect(compile(plan()).sql())).isTrue();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1")).isTrue();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT \"update_log\".\"x\" FROM \"s\".\"t\""))
                .as("a quoted identifier that merely spells a mutation keyword is a name, not a statement")
                .isTrue();

        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect(null)).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1; DROP TABLE members")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1;")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1 -- a comment")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1 /* a comment */")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("INSERT INTO \"s\".\"t\" VALUES (1)")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("UPDATE \"s\".\"t\" SET \"x\" = ?")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("DELETE FROM \"s\".\"t\"")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("WITH x AS (SELECT 1) SELECT * FROM x")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("SELECT 1 INTO \"s\".\"t\"")).isFalse();
        assertThat(PreviewSqlBoundary.isSingleReadOnlySelect("  SELECT 1")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private static PostgresPreviewExecutionBackend backend(
            ScriptedPreviewJdbcBoundary boundary, PreviewDataWatermarkProvider watermark) {
        return backend(boundary, POLICY, watermark);
    }

    private static PostgresPreviewExecutionBackend backend(
            ScriptedPreviewJdbcBoundary boundary,
            PreviewPostgresExecutionPolicy policy,
            PreviewDataWatermarkProvider watermark) {
        return new PostgresPreviewExecutionBackend(
                boundary.dataSource(), CATALOG, new SqlCompiler(), SqlCompilePolicy.standard(),
                IDENTITY, watermark, policy, null);
    }

    private static PreviewDataWatermarkProvider watermarkOfContext() {
        return context -> context == null || context.snapshotRef() == null
                ? null
                : context.snapshotRef().dataWatermark();
    }

    private static void assertRefused(ScriptedPreviewJdbcBoundary boundary) {
        assertRefusedKind(
                () -> backend(boundary, watermarkOfContext())
                        .execute(plan(), SegmentValidationFixtures.context()),
                PreviewBackendException.Kind.INVALID_RESULT);
        assertThat(boundary.rolledBack()).isTrue();
        assertThat(boundary.connectionClosed()).isTrue();
    }

    /** A plan or watermark refusal must happen before any statement is prepared. */
    private static void assertRefusedFromWatermark(
            ScriptedPreviewJdbcBoundary boundary, PreviewDataWatermarkProvider watermark) {
        assertRefusedKind(
                () -> backend(boundary, watermark).execute(plan(), SegmentValidationFixtures.context()),
                PreviewBackendException.Kind.INVALID_RESULT);
        assertThat(boundary.calls()).doesNotContain("prepareStatement");
    }

    private static void assertRefusedKind(ThrowingCallable callable, PreviewBackendException.Kind kind) {
        PreviewBackendException failure = catchThrowableOfType(PreviewBackendException.class, callable);
        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(kind);
    }

    private static SemanticQueryPlan plan() {
        return SqlTestSupport.plan(SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
    }

    private static CompiledSql compile(SemanticQueryPlan plan) {
        // No external FX binding is needed: this plan requires no currency conversion, so the compiler ignores it.
        return new SqlCompiler().compile(plan, CATALOG, SqlCompilePolicy.standard());
    }

    private static TrustedExecutionContext foreignContext() {
        return MetadataFixtures.context("tenant-999", "program-001", MetadataFixtures.allPermissions());
    }
}