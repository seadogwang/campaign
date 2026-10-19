package com.company.campaign.dev.preview.postgres;

import com.company.campaign.builder.tool.HostSafePreviewPort;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentIdentity;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.PreviewBackendIdentity;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.PreviewLimits;
import com.company.campaign.preview.PreviewPolicy;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.query.plan.SegmentQueryPlanner;
import com.company.campaign.query.sql.SqlCompilePolicy;
import com.company.campaign.query.sql.SqlCompiler;
import com.company.campaign.query.sql.StorageCatalog;
import com.company.campaign.query.sql.StorageCatalogResourceLoader;
import com.company.campaign.readiness.postgres.ReadinessFlywayLog;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidator;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * The focused opt-in configuration that turns the accepted T09C Preview service into a real read-only
 * PostgreSQL execution path.
 *
 * <p>It is inert unless the local development Agent host is enabled <em>and</em> the host selected
 * {@code campaign.agent.development.preview.postgres.enabled=true}. When it is off — the default — the
 * application creates no pool, no storage catalog, no execution backend, no Preview service and no
 * {@link SegmentPreviewPort} at all, exactly as before.
 *
 * <p>When it is on it builds, fail-closed and in one place, the one read-only Hikari pool
 * ({@value #DATASOURCE_BEAN_NAME}), the one server-owned {@link StorageCatalog}
 * ({@value #CATALOG_BEAN_NAME}), the one exact-pin {@link MetadataSnapshotRepository}
 * ({@value #METADATA_REPOSITORY_BEAN_NAME}), the one {@link PostgresPreviewExecutionBackend}
 * ({@value #BACKEND_BEAN_NAME}), the one {@link PreviewSampleHasher} ({@value #SAMPLE_HASHER_BEAN_NAME}), the
 * one {@link SegmentPreviewService} ({@value #SERVICE_BEAN_NAME}) and exactly one {@link HostSafePreviewPort}
 * ({@value #PREVIEW_PORT_BEAN_NAME}). Every bean carries an explicit name and qualifier, so enabling readiness
 * storage, durable conversation persistence and Preview together creates no ambiguous {@code DataSource},
 * {@code Flyway} or {@code FlywayMigrationInitializer} dependency.
 *
 * <p>The service is built only from accepted components: the same {@link DevelopmentCampaignAgentIdentity} the
 * development Campaign Agent pins, the frozen {@link CoreLoyaltyDevelopmentProfile} metadata snapshot, the
 * accepted DSL codec, validator with its standard policy, planner, authorization service, Preview policy,
 * sample hasher and the one injected backend. No second model and no second Segment Agent are created, and no
 * test fixture is reachable from main wiring.
 *
 * <p>This is local/development integration, not production evidence: it delivers no production identity, no
 * production Preview backend, no production model evidence and no Provider runtime. No Flyway runtime, no
 * schema and no migration asset is owned here, because Preview reads an existing allowlisted host schema and
 * must never create or mutate a business table.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PreviewPostgresProperties.class)
@ConditionalOnProperty(prefix = "campaign.agent.development", name = "enabled", havingValue = "true")
public class PreviewPostgresConfiguration {

    /** The one bounded read-only Preview pool. */
    public static final String DATASOURCE_BEAN_NAME = "campaignAgentPreviewPostgresDataSource";

    /** The one server-owned physical storage catalog Preview compiles against. */
    public static final String CATALOG_BEAN_NAME = "campaignAgentPreviewStorageCatalog";

    /** The one exact-pin Metadata snapshot repository the Preview service resolves against. */
    public static final String METADATA_REPOSITORY_BEAN_NAME = "campaignAgentPreviewMetadataRepository";

    /** The one read-only PostgreSQL execution backend. */
    public static final String BACKEND_BEAN_NAME = "campaignAgentPreviewPostgresBackend";

    /** The one server-owned sample hasher. */
    public static final String SAMPLE_HASHER_BEAN_NAME = "campaignAgentPreviewSampleHasher";

    /** The one Preview orchestrator. */
    public static final String SERVICE_BEAN_NAME = "campaignAgentPreviewPostgresService";

    /** The one auto-published typed Preview port; a host bean of this type has documented precedence. */
    public static final String PREVIEW_PORT_BEAN_NAME = "campaignAgentPreviewPostgresPreviewPort";

    /** The server-owned Preview response bounds; they are bounded by {@link PreviewLimits} at construction. */
    static final int PREVIEW_MAX_REQUEST_BYTES = PreviewLimits.MAX_REQUEST_BYTES;

    static final int PREVIEW_MAX_CONDITION_STATS = PreviewLimits.MAX_CONDITION_STATS;
    static final int PREVIEW_MAX_SAMPLE_SIZE = 100;
    static final int PREVIEW_MAX_MATCHED_COUNT = 1_000_000;
    static final Duration PREVIEW_MAX_DATA_AGE = Duration.ofDays(7);

    /** The exact Server-owned Preview policy of this development integration. */
    public static PreviewPolicy previewPolicy() {
        return new PreviewPolicy(
                PREVIEW_MAX_REQUEST_BYTES,
                PREVIEW_MAX_CONDITION_STATS,
                PREVIEW_MAX_SAMPLE_SIZE,
                PREVIEW_MAX_MATCHED_COUNT,
                PREVIEW_MAX_DATA_AGE);
    }

    /**
     * The real read-only Preview runtime, created only when the host explicitly enabled the PostgreSQL Preview.
     *
     * <p>It is a nested member class rather than a second condition on the outer class, so each element carries
     * exactly one explicit condition and the disabled default creates nothing at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(
            prefix = "campaign.agent.development.preview.postgres", name = "enabled", havingValue = "true")
    static class PostgresPreviewRuntime {

        /** The one bounded read-only pool of the Preview integration. */
        @Bean(name = DATASOURCE_BEAN_NAME, destroyMethod = "close")
        @Qualifier(DATASOURCE_BEAN_NAME)
        @ConditionalOnMissingBean(name = DATASOURCE_BEAN_NAME)
        public HikariDataSource campaignAgentPreviewPostgresDataSource(PreviewPostgresProperties properties) {
            properties.validate();
            // The credential, the URL and the sample key are registered for redaction before any pool can log.
            ReadinessFlywayLog.registerSecret(properties.getPassword());
            ReadinessFlywayLog.registerSecret(properties.getUrl());
            ReadinessFlywayLog.registerSecret(properties.getSampleKey());
            try {
                return PreviewPostgresDataSourceFactory.dataSource(properties);
            } catch (RuntimeException failure) {
                throw new PreviewPostgresConfigurationException(
                        PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_POOL_UNAVAILABLE,
                        "The configured read-only PostgreSQL Preview pool could not be established.", failure);
            }
        }

        /**
         * The one server-owned physical storage catalog of this development Preview integration.
         *
         * <p>An explicit host resource is loaded through the accepted strict catalog loader; without one, the
         * frozen development profile's own physical mapping is used, because the development host pins exactly
         * that profile's Metadata snapshot and a Preview that mapped a different catalog could not compile its
         * plans at all. Neither path reads a request, a fixture dataset or a test asset.
         */
        @Bean(name = CATALOG_BEAN_NAME)
        @Qualifier(CATALOG_BEAN_NAME)
        public StorageCatalog campaignAgentPreviewStorageCatalog(PreviewPostgresProperties properties) {
            String resource = properties.getStorageCatalogResource();
            try {
                return resource == null || resource.isBlank()
                        ? CoreLoyaltyDevelopmentProfile.storageCatalog()
                        : StorageCatalogResourceLoader.load(resource);
            } catch (RuntimeException failure) {
                throw new PreviewPostgresConfigurationException(
                        PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CATALOG_UNAVAILABLE,
                        "The configured server-owned Preview storage catalog could not be resolved.", failure);
            }
        }

        /**
         * The one exact-pin Metadata snapshot repository the Preview service resolves against.
         *
         * <p>It holds exactly the frozen development snapshot the Campaign Agent pins, so a genuine audience
         * turn and a Preview call resolve the same immutable Metadata facts. More than one host-supplied
         * repository is an ambiguous wiring and fails startup rather than depending on bean order.
         */
        @Bean(name = METADATA_REPOSITORY_BEAN_NAME)
        @Qualifier(METADATA_REPOSITORY_BEAN_NAME)
        public MetadataSnapshotRepository campaignAgentPreviewMetadataRepository(ListableBeanFactory beanFactory) {
            for (String name : beanFactory.getBeanNamesForType(MetadataSnapshotRepository.class, true, false)) {
                if (!METADATA_REPOSITORY_BEAN_NAME.equals(name)) {
                    throw new PreviewPostgresConfigurationException(
                            PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CONFIGURATION_INVALID,
                            "The opt-in Preview integration requires at most one Metadata snapshot repository.");
                }
            }
            MetadataSnapshot snapshot = CoreLoyaltyDevelopmentProfile.snapshot();
            InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
            repository.publish(snapshot);
            return repository;
        }

        /** The one server-owned opaque sample hasher, keyed from the host-owned key only. */
        @Bean(name = SAMPLE_HASHER_BEAN_NAME)
        @Qualifier(SAMPLE_HASHER_BEAN_NAME)
        public PreviewSampleHasher campaignAgentPreviewSampleHasher(PreviewPostgresProperties properties) {
            properties.validate();
            return new PreviewSampleHasher(properties.getSampleKey().getBytes(StandardCharsets.UTF_8));
        }

        /**
         * The one read-only PostgreSQL execution backend.
         *
         * <p>It receives exactly one immutable server-owned catalog, compiler, compile policy, backend identity,
         * watermark provider and execution policy, plus the one read-only pool. The FX resolver is deliberately
         * absent: this development integration has no host-owned rate snapshot, so a plan that needs an exact
         * conversion fails closed at compilation rather than approximating a rate.
         *
         * <p>The injected pool is resolved by its explicit qualifier, so a host-supplied read-only pool for that
         * exact boundary has documented precedence over the one this configuration would build, and a host that
         * already owns the connection target never gets a second hidden pool.
         */
        @Bean(name = BACKEND_BEAN_NAME)
        @Qualifier(BACKEND_BEAN_NAME)
        public PreviewExecutionBackend campaignAgentPreviewPostgresBackend(
                @Qualifier(DATASOURCE_BEAN_NAME) DataSource campaignAgentPreviewPostgresDataSource,
                @Qualifier(CATALOG_BEAN_NAME) StorageCatalog campaignAgentPreviewStorageCatalog,
                PreviewPostgresProperties properties) {
            properties.validate();
            return new PostgresPreviewExecutionBackend(
                    campaignAgentPreviewPostgresDataSource,
                    campaignAgentPreviewStorageCatalog,
                    new SqlCompiler(),
                    SqlCompilePolicy.standard(),
                    new PreviewBackendIdentity(
                            PostgresPreviewExecutionBackend.BACKEND_ID, properties.getBackendVersion()),
                    PreviewPostgresConfiguration::developmentWatermark,
                    properties.executionPolicy(),
                    null);
        }

        /**
         * The genuine Preview orchestrator over the exact T33 development identity and Metadata snapshot.
         *
         * <p>Every dependency is the accepted component: the same trusted context provider the development
         * Campaign Agent pins, the accepted DSL codec, validator and policy, planner, authorization service, the
         * one injected read-only backend, a bounded server-owned policy and the one sample hasher. No model, no
         * Segment Agent and no fixture is created here.
         */
        @Bean(name = SERVICE_BEAN_NAME)
        @Qualifier(SERVICE_BEAN_NAME)
        public SegmentPreviewService campaignAgentPreviewPostgresService(
                DevelopmentCampaignAgentIdentity campaignAgentDevelopmentIdentity,
                @Qualifier(METADATA_REPOSITORY_BEAN_NAME) MetadataSnapshotRepository campaignAgentPreviewMetadataRepository,
                @Qualifier(BACKEND_BEAN_NAME) PreviewExecutionBackend campaignAgentPreviewPostgresBackend,
                @Qualifier(SAMPLE_HASHER_BEAN_NAME) PreviewSampleHasher campaignAgentPreviewSampleHasher) {
            AuthorizationService authorizationService = CoreLoyaltyDevelopmentProfile.authorizationService();
            return new SegmentPreviewService(
                    campaignAgentDevelopmentIdentity,
                    campaignAgentPreviewMetadataRepository,
                    new SegmentDslCodec(),
                    new SegmentValidator(authorizationService),
                    SegmentValidationPolicy.standard(),
                    new SegmentQueryPlanner(authorizationService),
                    campaignAgentPreviewPostgresBackend,
                    authorizationService,
                    previewPolicy(),
                    campaignAgentPreviewSampleHasher);
        }

        /**
         * The one auto-published typed Preview port of this development integration.
         *
         * <p>The development Segment source identifies it by exactly this bean name, so a host port is never
         * confused with it and resolution never depends on registration order. This integration publishes it
         * only when the host enabled it; a host that wants a different Preview boundary simply leaves this mode
         * off and supplies its own port, which then keeps its accepted T33 precedence. A host port that coexists
         * with this one is an ambiguous wiring and fails startup with a bounded code rather than silently
         * selecting one of two differently configured read-only boundaries.
         */
        @Bean(name = PREVIEW_PORT_BEAN_NAME)
        @Qualifier(PREVIEW_PORT_BEAN_NAME)
        public SegmentPreviewPort campaignAgentPreviewPostgresPreviewPort(
                @Qualifier(SERVICE_BEAN_NAME) SegmentPreviewService campaignAgentPreviewPostgresService) {
            return new HostSafePreviewPort(campaignAgentPreviewPostgresService);
        }
    }

    /**
     * The host-owned watermark of this development integration: exactly the instant the fixed development
     * identity pins.
     *
     * <p>It is deliberately a static, deterministic function of the trusted context rather than a clock read, so
     * a Preview compiled at one development instant can never silently publish a different watermark. A context
     * whose snapshot reference is absent yields {@code null}, which the adapter refuses.
     */
    static Instant developmentWatermark(TrustedExecutionContext context) {
        if (context == null || context.snapshotRef() == null) {
            return null;
        }
        return context.snapshotRef().dataWatermark();
    }
}
