package com.company.campaign.dev.preview.postgres;

import com.company.campaign.builder.tool.HostSafePreviewPort;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewExecutionContext;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.plan.SemanticQueryPlan;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.SqlIdentifier;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.sql.TableRef;
import com.company.campaign.query.testsupport.QueryPlanFixtures;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidator;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment-gated real-PostgreSQL verification of the genuine T34C Preview execution path.
 *
 * <p>It is skipped unless {@code CAMPAIGN_PREVIEW_POSTGRES_URL} (plus a user and a password) is supplied, and
 * when it does run it uses only real PostgreSQL: there is no test double, no embedded engine and no H2
 * fallback anywhere on this path — a missing setting means the run does not happen, never that a substitute
 * runs instead.
 *
 * <p>It creates one isolated temporary schema named for this verifier, with the minimal allowlisted catalog
 * tables that schema needs, then runs the genuine plan → accepted compiled artifact → PostgreSQL adapter →
 * {@code SegmentPreviewService} path and proves the matched count, the deterministic opaque sample hashes and
 * the absence of any raw member identifier on the wire. The companion
 * {@link EnabledPreviewPostgresHttpEndToEndTest} proves the enabled HTTP conversation route.
 */
@EnabledIfEnvironmentVariable(
        named = PreviewPostgresTestEnvironment.URL_VARIABLE,
        matches = ".+",
        disabledReason = "real PostgreSQL is not configured")
class PreviewPostgresIntegrationTest {

    private static final String SAMPLE_KEY = "integration-sample-key-value-0123456789";

    @Test
    void realPostgresqlExecutesOnlyTheCompiledArtifactAndPublishesCoherentPinnedSamples() throws Exception {
        MetadataSnapshot snapshot = SegmentValidationFixtures.snapshot();
        TrustedExecutionContext context = SegmentValidationFixtures.context();
        StorageCatalog catalog = minimalCatalog(PreviewPostgresTestEnvironment.schema());
        SegmentDsl document = QueryPlanFixtures.document(
                snapshot, SegmentValidationFixtures.tierEquals("n-tier", "GOLD"));
        SemanticQueryPlan plan = QueryPlanFixtures.plan(document, snapshot, context);
        assertThat(plan.dataScopePredicate().dataScopeId()).isEqualTo(context.dataScopeId());

        // The fixture tables are created over a plain writable connection: the Preview pool below is
        // deliberately read-only and could never create a schema or a table itself.
        try (Connection connection = PreviewPostgresTestEnvironment.schemaConnection()) {
            PreviewPostgresTestEnvironment.createIsolatedSchema(connection);
            createMinimalTables(connection);
            seedMinimalRows(connection);
            assertThat(PreviewPostgresTestEnvironment.serverVersion(connection))
                    .containsIgnoringCase("postgres");
        }

        try (HikariDataSource dataSource = PreviewPostgresTestEnvironment.dataSource()) {
            PreviewSampleHasher sampleHasher = new PreviewSampleHasher(SAMPLE_KEY.getBytes(StandardCharsets.UTF_8));
            PreviewResponse response = new HostSafePreviewPort(service(context, snapshot, catalog, dataSource,
                    sampleHasher)).preview(document, new PreviewPresentation(true, true));

            assertThat(response.status()).isEqualTo("SUCCESS");
            assertThat(response.matchedCount()).isEqualTo(2);
            assertThat(response.queryPlanHash()).isEqualTo(plan.planHash().value());
            assertThat(response.conditionStats()).isEmpty();

            // The sample is the deterministic opaque hash of each matched member, never the raw identifier.
            assertThat(response.sample()).containsExactlyInAnyOrderElementsOf(List.of(
                    sampleHasher.hash(context.tenantId(), context.programId(), context.dataScopeId(), "member-1")
                            .value(),
                    sampleHasher.hash(context.tenantId(), context.programId(), context.dataScopeId(), "member-2")
                            .value()));
            assertThat(response.sample()).allSatisfy(value ->
                    assertThat(value).matches("^sha256:[0-9a-f]{64}$"));
            assertThat(response.toString() + response.contentHash().value())
                    .doesNotContain("member-1")
                    .doesNotContain("member-2")
                    .doesNotContain("member-3")
                    .doesNotContain(PreviewPostgresTestEnvironment.schema());

            PreviewExecutionContext execution = response.executionContext();
            assertThat(execution.metadataSnapshotId()).isEqualTo(context.snapshotRef().metadataSnapshotId());
            assertThat(execution.asOf()).isEqualTo(context.snapshotRef().asOf().toString());
            assertThat(execution.timezone()).isEqualTo(context.snapshotRef().timezone().getId());
            assertThat(execution.dataWatermark()).isEqualTo(context.snapshotRef().dataWatermark().toString());
        }
    }

    // ------------------------------------------------------------------ the genuine service

    private static SegmentPreviewService service(
            TrustedExecutionContext context,
            MetadataSnapshot snapshot,
            StorageCatalog catalog,
            HikariDataSource dataSource,
            PreviewSampleHasher sampleHasher) {
        AuthorizationService authorizationService = SegmentValidationFixtures.allowAll();
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);
        return new SegmentPreviewService(
                provider(context),
                repository,
                new SegmentDslCodec(),
                new SegmentValidator(authorizationService),
                SegmentValidationPolicy.standard(),
                new SegmentQueryPlanner(authorizationService),
                new PostgresPreviewExecutionBackend(
                        dataSource,
                        catalog,
                        new SqlCompiler(),
                        SqlCompilePolicy.standard(),
                        new PreviewBackendIdentity(PostgresPreviewExecutionBackend.BACKEND_ID, "1"),
                        trusted -> trusted.snapshotRef() == null ? null : trusted.snapshotRef().dataWatermark(),
                        new PreviewPostgresExecutionPolicy(10, 100, 1_000, 86_400L, "PostgreSQL", 12),
                        null),
                authorizationService,
                PreviewPostgresConfiguration.previewPolicy(),
                sampleHasher);
    }

    private static TrustedContextProvider provider(TrustedExecutionContext context) {
        return new TrustedContextProvider() {
            @Override
            public VerifiedIdentity verifiedIdentity() {
                return context.identity();
            }

            @Override
            public PrincipalMembership verifiedMembership() {
                return context.membership();
            }

            @Override
            public TrustedExecutionContextRef executionReference() {
                return context.snapshotRef();
            }

            @Override
            public TrustedExecutionContext trustedContext() {
                return context;
            }
        };
    }

    // ------------------------------------------------------------------ the minimal allowlisted catalog

    /**
     * The smallest catalog that can compile one {@code member.tier EQUAL} plan: the member entity, that one
     * attribute and the mandatory DataScope membership table, all in the isolated verifier schema. It invents
     * no metric, no relation and no dataset.
     */
    private static StorageCatalog minimalCatalog(String schema) {
        return StorageCatalog.publish(
                "preview-integration-catalog",
                List.of(new StorageCatalog.EntityStorage(
                        MetadataFixtures.memberVersion(),
                        new TableRef(SqlIdentifier.of(schema), SqlIdentifier.of("members")),
                        SqlIdentifier.of("member_id"),
                        new StorageCatalog.ContextStorage(
                                SqlIdentifier.of("tenant_id"), SqlIdentifier.of("program_id")))),
                List.of(new StorageCatalog.AttributeStorage(
                        "member.tier", SubjectKind.MEMBER, MetadataSchemaVersions.SUPPORTED,
                        SemanticDataType.ENUM, SqlIdentifier.of("tier"), null)),
                List.of(),
                List.of(),
                new StorageCatalog.DataScopeStorage(
                        new TableRef(SqlIdentifier.of(schema), SqlIdentifier.of("data_scope_members")),
                        SqlIdentifier.of("scope_id"),
                        SqlIdentifier.of("member_id"),
                        SqlIdentifier.of("tenant_id"),
                        SqlIdentifier.of("program_id")),
                null,
                null);
    }

    private static void createMinimalTables(Connection connection) throws Exception {
        String schema = PreviewPostgresTestEnvironment.schema();
        execute(connection, "CREATE TABLE \"" + schema + "\".\"members\" ("
                + "member_id text PRIMARY KEY, tenant_id text NOT NULL, program_id text NOT NULL, tier text)");
        execute(connection, "CREATE TABLE \"" + schema + "\".\"data_scope_members\" ("
                + "scope_id text NOT NULL, member_id text NOT NULL, tenant_id text NOT NULL,"
                + " program_id text NOT NULL)");
    }

    private static void seedMinimalRows(Connection connection) throws Exception {
        String schema = PreviewPostgresTestEnvironment.schema();
        execute(connection, "INSERT INTO \"" + schema + "\".\"members\" VALUES"
                + " ('member-1', 'tenant-001', 'program-001', 'GOLD'),"
                + " ('member-2', 'tenant-001', 'program-001', 'GOLD'),"
                + " ('member-3', 'tenant-001', 'program-001', 'SILVER')");
        execute(connection, "INSERT INTO \"" + schema + "\".\"data_scope_members\" VALUES"
                + " ('scope-008', 'member-1', 'tenant-001', 'program-001'),"
                + " ('scope-008', 'member-2', 'tenant-001', 'program-001'),"
                + " ('scope-008', 'member-3', 'tenant-001', 'program-001')");
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}