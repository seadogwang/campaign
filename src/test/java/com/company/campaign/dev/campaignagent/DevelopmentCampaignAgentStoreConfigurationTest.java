package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.conversation.postgres.ConversationPostgresConfiguration;
import com.company.campaign.conversation.postgres.ConversationPostgresConfigurationException;
import com.company.campaign.conversation.postgres.ConversationPostgresErrorCodes;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import com.company.campaign.persistence.CampaignDatasourceConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceErrorCodes;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfiguration;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfigurationException;
import com.company.campaign.readiness.postgres.ReadinessPostgresErrorCodes;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34B: the opt-in host selects exactly one Campaign conversation store, fail-closed.
 *
 * <p>The default keeps the in-memory registry and creates no database bean at all; an explicit PostgreSQL mode
 * validates its complete configuration before anything connects; a host-supplied store always wins; and an
 * ambiguous wiring, an unreachable server or a disabled host never publishes a store.
 */
class DevelopmentCampaignAgentStoreConfigurationTest {

    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentCampaignAgentConfiguration.class)
                .withPropertyValues("campaign.agent.development.enabled=true")
                .withBean(DevelopmentStructuredModelPort.class,
                        DevelopmentCampaignAgentStoreConfigurationTest::plainTransport);
    }

    /** A bounded transport that answers every prompt with the empty JSON object. */
    private static DevelopmentStructuredModelPort plainTransport() {
        return prompt -> "{}".getBytes(StandardCharsets.UTF_8);
    }

    private static ApplicationContextRunner enabledWithPostgres() {
        return enabled().withUserConfiguration(ConversationPostgresConfiguration.class);
    }

    private static CampaignConversationStore hostStore() {
        return new InMemoryDevelopmentConversationStore(
                Clock.fixed(Instant.parse("2026-10-10T00:00:00Z"), ZoneOffset.UTC), 4, 4,
                Duration.ofMinutes(30));
    }

    @Test
    void theDefaultInMemoryModePublishesTheRegistryAndCreatesNoDatabaseBean() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CampaignConversationStore.class))
                    .isInstanceOf(InMemoryDevelopmentConversationStore.class);
            assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
            assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).hasSize(1);
        });
    }

    @Test
    void aHostSuppliedStoreAlwaysWins() {
        CampaignConversationStore supplied = hostStore();
        enabled().withBean(CampaignConversationStore.class, () -> supplied).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CampaignConversationStore.class)).isSameAs(supplied);
            assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).hasSize(1);
        });
    }

    @Test
    void moreThanOneHostSuppliedStoreFailsStartupWithABoundedConfigurationCode() {
        enabled()
                .withBean("firstStore", CampaignConversationStore.class,
                        DevelopmentCampaignAgentStoreConfigurationTest::hostStore)
                .withBean("secondStore", CampaignConversationStore.class,
                        DevelopmentCampaignAgentStoreConfigurationTest::hostStore)
                .run(context -> assertAgentConfigurationFailure(
                        context.getStartupFailure(),
                        DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID));
    }

    @Test
    void postgresqlModeWithAMissingDatabaseSettingFailsClosed() {
        enabledWithPostgres()
                .withPropertyValues("campaign.agent.development.store.mode=POSTGRESQL")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(postgresFailureCode(context.getStartupFailure()))
                            .isEqualTo(ConversationPostgresErrorCodes
                                    .CONVERSATION_POSTGRESQL_CONFIGURATION_INVALID);
                });
    }

    @Test
    void postgresqlModeWithAReservedSchemaFailsClosed() {
        enabledWithPostgres()
                .withPropertyValues(
                        "campaign.agent.development.store.mode=POSTGRESQL",
                        "campaign.agent.development.store.url=jdbc:postgresql://127.0.0.1:1/campaign",
                        "campaign.agent.development.store.username=campaign-user",
                        "campaign.agent.development.store.password=not-a-real-credential",
                        "campaign.agent.development.store.schema=public")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(postgresFailureCode(context.getStartupFailure()))
                            .isEqualTo(ConversationPostgresErrorCodes
                                    .CONVERSATION_POSTGRESQL_CONFIGURATION_INVALID);
                });
    }

    @Test
    void postgresqlModeWithAnUnreachableServerFailsClosedWithoutDisclosingIt() {
        enabledWithPostgres()
                .withPropertyValues(
                        "campaign.agent.development.store.mode=POSTGRESQL",
                        "campaign.datasource.enabled=true",
                        "campaign.datasource.url=jdbc:postgresql://127.0.0.1:1/campaign",
                        "campaign.datasource.username=campaign-user",
                        "campaign.datasource.password=not-a-real-credential",
                        "campaign.datasource.schema=campaign_conversation_t34b_startup",
                        "campaign.datasource.connection-timeout-ms=250",
                        "campaign.datasource.validation-timeout-ms=250")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(datasourceFailureCode(failure))
                            .isEqualTo(CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE);
                    assertThat(messages(failure))
                            .doesNotContain("not-a-real-credential")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1")
                            .doesNotContain("127.0.0.1");
                });
    }

    @Test
    void enablingReadinessStorageAndAgentPersistenceTogetherCreatesNoAmbiguousBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentCampaignAgentConfiguration.class,
                        ConversationPostgresConfiguration.class,
                        ReadinessPostgresConfiguration.class)
                .withBean(DevelopmentStructuredModelPort.class,
                        DevelopmentCampaignAgentStoreConfigurationTest::plainTransport)
                .withPropertyValues(
                        "campaign.agent.development.enabled=true",
                        "campaign.agent.development.store.mode=POSTGRESQL",
                        "campaign.datasource.enabled=true",
                        "campaign.datasource.url=jdbc:postgresql://127.0.0.1:1/agent",
                        "campaign.datasource.username=agent-user",
                        "campaign.datasource.password=agent-secret",
                        "campaign.datasource.schema=campaign_conversation_t34b_combined",
                        "campaign.datasource.connection-timeout-ms=250",
                        "campaign.datasource.validation-timeout-ms=250")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    // Both pools target a closed local port, so the start must fail on one bounded pool code
                    // and never on an ambiguous DataSource, Flyway or FlywayMigrationInitializer dependency.
                    assertThat(failure).isNotNull();
                    assertThat(messages(failure))
                            .doesNotContain("NoUniqueBeanDefinitionException")
                            .doesNotContain("No qualifying bean of type")
                            .doesNotContain("agent-secret")
                            .doesNotContain("readiness-secret")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1");
                    assertThat(poolFailureCode(failure)).isIn(
                            CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE,
                            ConversationPostgresErrorCodes.CONVERSATION_POSTGRESQL_POOL_UNAVAILABLE,
                            ReadinessPostgresErrorCodes.READINESS_POSTGRESQL_PROBE_UNAVAILABLE);
                });
    }

    @Test
    void theDisabledDefaultPublishesNoStoreAndNoDatabaseBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentCampaignAgentConfiguration.class, ConversationPostgresConfiguration.class)
                .withPropertyValues("campaign.agent.development.store.mode=POSTGRESQL")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CampaignConversationStore.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
                });
    }

    private static void assertAgentConfigurationFailure(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof DevelopmentCampaignAgentConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(DevelopmentCampaignAgentConfigurationException.class);
        assertThat(((DevelopmentCampaignAgentConfigurationException) cause).code()).isEqualTo(code);
    }

    private static String postgresFailureCode(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof ConversationPostgresConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(ConversationPostgresConfigurationException.class);
        return ((ConversationPostgresConfigurationException) cause).code();
    }

    private static String datasourceFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no unified datasource code");
    }

    /** The first bounded pool code on the failure chain, whichever slice's pool was built first. */
    private static String poolFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
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
