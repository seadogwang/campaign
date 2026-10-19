package com.company.campaign.dev.preview.postgres;

import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.conversation.postgres.ConversationPostgresConfiguration;
import com.company.campaign.conversation.postgres.ConversationPostgresConfigurationException;
import com.company.campaign.conversation.postgres.ConversationPostgresErrorCodes;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentConfiguration;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentConfigurationException;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentErrorCodes;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentIdentity;
import com.company.campaign.dev.campaignagent.DevelopmentSegmentSourceProvider;
import com.company.campaign.dev.campaignagent.testsupport.DevelopmentSegmentRealHostFixture;
import com.company.campaign.dev.segment.CoreLoyaltyDevelopmentProfile;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.persistence.CampaignDatasourceConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceErrorCodes;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfiguration;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfigurationException;
import com.company.campaign.readiness.postgres.ReadinessPostgresErrorCodes;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34C requirement 3: the opt-in PostgreSQL Preview integration is default-off, validates a complete
 * configuration before anything connects, resolves exactly one Preview port with documented precedence,
 * coexists with the readiness and durable-conversation pools by qualifier, and keeps the exact T33 development
 * identity and Metadata snapshot pins.
 */
class DevelopmentPreviewPostgresConfigurationTest {

    private static final String PREVIEW_URL_PROPERTY =
            "campaign.agent.development.preview.postgres.url";

    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentCampaignAgentConfiguration.class, PreviewPostgresConfiguration.class)
                .withBean(DevelopmentSegmentRealHostFixture.FakeTransport.class,
                        DevelopmentSegmentRealHostFixture.FakeTransport::new)
                .withPropertyValues("campaign.agent.development.enabled=true");
    }

    private static ApplicationContextRunner enabledWithHostPool() {
        return enabled()
                .withBean(PreviewPostgresConfiguration.DATASOURCE_BEAN_NAME, DataSource.class,
                        () -> new ScriptedPreviewJdbcBoundary().dataSource())
                .withPropertyValues(
                        "campaign.agent.development.preview.postgres.enabled=true",
                        PREVIEW_URL_PROPERTY + "=jdbc:postgresql://127.0.0.1:1/preview",
                        "campaign.agent.development.preview.postgres.username=preview-user",
                        "campaign.agent.development.preview.postgres.password=preview-secret-value",
                        "campaign.agent.development.preview.postgres.schema=loyalty_host",
                        "campaign.agent.development.preview.postgres.sample-key="
                                + "host-owned-sample-key-value-0123456789");
    }

    @Test
    void theDisabledDefaultCreatesNoPreviewRuntimeBeanAtAll() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(SegmentPreviewService.class)).isEmpty();
            assertThat(context.getBeanNamesForType(PreviewExecutionBackend.class)).isEmpty();
            assertThat(context.getBeanNamesForType(SegmentPreviewPort.class)).isEmpty();
            assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(MetadataSnapshotRepository.class)).isEmpty();
            assertThat(context.getBeanNamesForType(
                    com.company.campaign.builder.campaignagent.CampaignAgentEnvironment.class)).hasSize(1);
        });
    }

    @Test
    void enablingThePreviewModeWithoutTheDevelopmentHostCreatesNothing() {
        new ApplicationContextRunner()
                .withUserConfiguration(PreviewPostgresConfiguration.class)
                .withPropertyValues("campaign.agent.development.preview.postgres.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(SegmentPreviewService.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                });
    }

    @Test
    void completeEnablementPublishesExactlyOneCoherentPreviewPort() {
        enabledWithHostPool().run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(context.getBeanNamesForType(SegmentPreviewPort.class)).hasSize(1);
            assertThat(context.getBean(SegmentPreviewPort.class))
                    .isInstanceOf(com.company.campaign.builder.tool.HostSafePreviewPort.class);
            assertThat(context.getBeanNamesForType(SegmentPreviewService.class)).hasSize(1);
            assertThat(context.getBeanNamesForType(PreviewExecutionBackend.class)).hasSize(1);

            PreviewExecutionBackend backend = context.getBean(PreviewExecutionBackend.class);
            assertThat(backend.identity().id()).isEqualTo(PostgresPreviewExecutionBackend.BACKEND_ID);
            assertThat(backend.identity().version()).isEqualTo("1");

            // The Preview service resolves the exact T33 development identity and snapshot pin.
            DevelopmentCampaignAgentIdentity identity = context.getBean(DevelopmentCampaignAgentIdentity.class);
            MetadataSnapshot snapshot = CoreLoyaltyDevelopmentProfile.snapshot();
            assertThat(identity.executionReference().metadataSnapshotId())
                    .isEqualTo(CoreLoyaltyDevelopmentProfile.SNAPSHOT_ID)
                    .isEqualTo(snapshot.snapshotId());
            assertThat(identity.executionReference().dataScopeId())
                    .isEqualTo(DevelopmentCampaignAgentIdentity.DATA_SCOPE)
                    .isEqualTo("scope-dev");
            assertThat(identity.trustedContext().tenantId()).isEqualTo(CoreLoyaltyDevelopmentProfile.TENANT);
            assertThat(identity.trustedContext().programId()).isEqualTo(CoreLoyaltyDevelopmentProfile.PROGRAM);

            MetadataSnapshotRepository repository = context.getBean(
                    PreviewPostgresConfiguration.METADATA_REPOSITORY_BEAN_NAME, MetadataSnapshotRepository.class);
            assertThat(repository.findByPin(
                    snapshot.snapshotId(), snapshot.snapshotVersion(), snapshot.contentHash()))
                    .isPresent();

            // The real development Segment source is built by the host over the auto-published port.
            assertThat(context.getBeansOfType(DevelopmentSegmentSourceProvider.class)).isEmpty();
            assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
        });
    }

    @Test
    void aHostPreviewPortTogetherWithTheAutoPortFailsClosedAsAmbiguous() {
        enabledWithHostPool()
                .withBean("hostPreviewPort", SegmentPreviewPort.class,
                        () -> new DevelopmentSegmentRealHostFixture.FakePreviewPort(
                                DevelopmentSegmentRealHostFixture.identity(), 1))
                .run(context -> assertDevelopmentConfigurationFailure(
                        context.getStartupFailure(),
                        DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID));
    }

    @Test
    void aMissingSettingFailsClosedWithABoundedCode() {
        enabled()
                .withPropertyValues("campaign.agent.development.preview.postgres.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(previewFailureCode(context.getStartupFailure()))
                            .isEqualTo(PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CONFIGURATION_INVALID);
                });
    }

    @Test
    void aNonPostgresqlUrlFailsClosedWithABoundedCode() {
        enabled()
                .withPropertyValues(
                        "campaign.agent.development.preview.postgres.enabled=true",
                        PREVIEW_URL_PROPERTY + "=jdbc:mysql://127.0.0.1:3306/preview",
                        "campaign.agent.development.preview.postgres.username=preview-user",
                        "campaign.agent.development.preview.postgres.password=preview-secret-value",
                        "campaign.agent.development.preview.postgres.schema=loyalty_host",
                        "campaign.agent.development.preview.postgres.sample-key="
                                + "host-owned-sample-key-value-0123456789")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(previewFailureCode(context.getStartupFailure()))
                            .isEqualTo(PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_CONFIGURATION_INVALID);
                    assertThat(messages(context.getStartupFailure())).doesNotContain("jdbc:mysql");
                });
    }

    @Test
    void anUnreachableServerFailsClosedWithoutDisclosingIt() {
        enabled()
                .withPropertyValues(
                        "campaign.agent.development.preview.postgres.enabled=true",
                        PREVIEW_URL_PROPERTY + "=jdbc:postgresql://127.0.0.1:1/preview",
                        "campaign.agent.development.preview.postgres.username=preview-user",
                        "campaign.agent.development.preview.postgres.password=preview-secret-value",
                        "campaign.agent.development.preview.postgres.schema=loyalty_host",
                        "campaign.agent.development.preview.postgres.sample-key="
                                + "host-owned-sample-key-value-0123456789",
                        "campaign.agent.development.preview.postgres.connection-timeout-ms=250",
                        "campaign.agent.development.preview.postgres.validation-timeout-ms=250")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(previewFailureCode(failure))
                            .isEqualTo(PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_POOL_UNAVAILABLE);
                    assertThat(messages(failure))
                            .doesNotContain("preview-secret-value")
                            .doesNotContain("host-owned-sample-key-value")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1")
                            .doesNotContain("127.0.0.1");
                });
    }

    @Test
    void readinessConversationAndPreviewPoolsCoexistWithoutAmbiguity() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentCampaignAgentConfiguration.class,
                        PreviewPostgresConfiguration.class,
                        ConversationPostgresConfiguration.class,
                        ReadinessPostgresConfiguration.class)
                .withBean(DevelopmentSegmentRealHostFixture.FakeTransport.class,
                        DevelopmentSegmentRealHostFixture.FakeTransport::new)
                .withPropertyValues(
                        "campaign.agent.development.enabled=true",
                        "campaign.agent.development.preview.postgres.enabled=true",
                        PREVIEW_URL_PROPERTY + "=jdbc:postgresql://127.0.0.1:1/preview",
                        "campaign.agent.development.preview.postgres.username=preview-user",
                        "campaign.agent.development.preview.postgres.password=preview-secret-value",
                        "campaign.agent.development.preview.postgres.schema=loyalty_host",
                        "campaign.agent.development.preview.postgres.sample-key="
                                + "host-owned-sample-key-value-0123456789",
                        "campaign.agent.development.preview.postgres.connection-timeout-ms=250",
                        "campaign.agent.development.preview.postgres.validation-timeout-ms=250",
                        "campaign.agent.development.store.mode=POSTGRESQL",
                        "campaign.datasource.enabled=true",
                        "campaign.datasource.url=jdbc:postgresql://127.0.0.1:1/conversation",
                        "campaign.datasource.username=conversation-user",
                        "campaign.datasource.password=conversation-secret",
                        "campaign.datasource.schema=campaign_conversation_t34c_combined",
                        "campaign.datasource.connection-timeout-ms=250",
                        "campaign.datasource.validation-timeout-ms=250")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    // All three pools target a closed local port, so the start fails on one bounded pool code
                    // and never on an ambiguous DataSource, Flyway or FlywayMigrationInitializer dependency.
                    assertThat(failure).isNotNull();
                    assertThat(messages(failure))
                            .doesNotContain("NoUniqueBeanDefinitionException")
                            .doesNotContain("No qualifying bean of type")
                            .doesNotContain("preview-secret-value")
                            .doesNotContain("conversation-secret")
                            .doesNotContain("readiness-secret")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1");
                    assertThat(poolFailureCode(failure)).isIn(
                            CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE,
                            PreviewPostgresErrorCodes.PREVIEW_POSTGRESQL_POOL_UNAVAILABLE,
                            ConversationPostgresErrorCodes.CONVERSATION_POSTGRESQL_POOL_UNAVAILABLE,
                            ReadinessPostgresErrorCodes.READINESS_POSTGRESQL_PROBE_UNAVAILABLE);
                });
    }

    // ------------------------------------------------------------------ helpers

    private static void assertDevelopmentConfigurationFailure(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof DevelopmentCampaignAgentConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(DevelopmentCampaignAgentConfigurationException.class);
        assertThat(((DevelopmentCampaignAgentConfigurationException) cause).code()).isEqualTo(code);
    }

    private static String previewFailureCode(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof PreviewPostgresConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(PreviewPostgresConfigurationException.class);
        return ((PreviewPostgresConfigurationException) cause).code();
    }

    /** The first bounded pool code on the failure chain, whichever slice's pool was built first. */
    private static String poolFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
            }
            if (cause instanceof PreviewPostgresConfigurationException previewFailure) {
                return previewFailure.code();
            }
            if (cause instanceof ConversationPostgresConfigurationException conversationFailure) {
                return conversationFailure.code();
            }
            if (cause instanceof ReadinessPostgresConfigurationException readinessFailure) {
                return readinessFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no bounded pool code");
    }

    /** Every text on the failure chain, so a redaction assertion cannot be defeated by a wrapper. */
    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause).append(' ').append(cause.getMessage()).append(' ');
        }
        return text.toString();
    }
}
