package com.company.campaign.production.audit;

import com.company.campaign.readiness.postgres.ReadinessPostgresConfiguration;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceErrorCodes;
import com.company.campaign.security.audit.AuditSink;
import com.company.campaign.testsupport.MyBatisTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in wiring of the default-off durable audit ledger.
 *
 * <p>It proves the ledger is inert unless both switches are on, that a missing, reserved or unreachable
 * configuration fails closed with one bounded and redacted code, that its qualified pool coexists with the
 * readiness pool without an ambiguous dependency, and that a host adapter has documented precedence while a
 * duplicate is an ambiguous wiring rather than a silent choice by bean order.
 *
 * <p>Every assertion here is <strong>non-database evidence</strong>: the only real connection attempt is the
 * one the bounded pool makes against a plainly unreachable placeholder target.
 */
class ProductionAuditConfigurationTest {

    private static final String[] ENABLED = {
            "campaign.production.enabled=true",
            "campaign.production.audit.enabled=true"};

    private static final String[] COMPLETE_LEDGER = {
            "campaign.production.audit.ledger.url=" + ProductionAuditTestFixtures.LEDGER_URL,
            "campaign.production.audit.ledger.username=" + ProductionAuditTestFixtures.LEDGER_USERNAME,
            "campaign.production.audit.ledger.password=" + ProductionAuditTestFixtures.LEDGER_PASSWORD,
            "campaign.production.audit.ledger.schema=" + ProductionAuditTestFixtures.LEDGER_SCHEMA,
            "campaign.production.audit.ledger.connection-timeout-ms=250",
            "campaign.production.audit.ledger.validation-timeout-ms=250"};

    private static final String[] COMPLETE_DATASOURCE = {
            "campaign.datasource.enabled=true",
            "campaign.datasource.url=" + ProductionAuditTestFixtures.LEDGER_URL,
            "campaign.datasource.username=" + ProductionAuditTestFixtures.LEDGER_USERNAME,
            "campaign.datasource.password=" + ProductionAuditTestFixtures.LEDGER_PASSWORD,
            "campaign.datasource.schema=" + ProductionAuditTestFixtures.LEDGER_SCHEMA,
            "campaign.datasource.connection-timeout-ms=250",
            "campaign.datasource.validation-timeout-ms=250"};

    @Test
    void theProductionIdentityBoundaryAloneCreatesNoDurableAuditBean() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class)
                .withPropertyValues("campaign.production.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(ProductionAuditProperties.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(MyBatisPlusProductionAuditLedger.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(FlywayMigrationInitializer.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(AuditSink.class)).isEmpty();
                });
    }

    @Test
    void theDurableAuditSwitchAloneCreatesNoDurableAuditBean() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class)
                .withPropertyValues("campaign.production.audit.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(MyBatisPlusProductionAuditLedger.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(AuditSink.class)).isEmpty();
                });
    }

    @Test
    void aMissingLedgerSettingFailsClosedWithABoundedCode() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_CONFIGURATION_INVALID);
                });
    }

    @Test
    void aReservedSchemaFailsClosedWithItsOwnBoundedCode() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class)
                .withPropertyValues(ENABLED)
                .withPropertyValues(COMPLETE_LEDGER)
                .withPropertyValues("campaign.production.audit.ledger.schema=public")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_SCHEMA_RESERVED);
                });
    }

    @Test
    void anUnreachableServerFailsStartupWithOneBoundedRedactedCode() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class)
                .withPropertyValues(ENABLED)
                .withPropertyValues(COMPLETE_DATASOURCE)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(datasourceFailureCode(failure))
                            .isEqualTo(CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE);
                    assertThat(chainText(failure))
                            .doesNotContain(ProductionAuditTestFixtures.LEDGER_URL)
                            .doesNotContain(ProductionAuditTestFixtures.LEDGER_USERNAME)
                            .doesNotContain(ProductionAuditTestFixtures.LEDGER_PASSWORD);
                });
    }

    @Test
    void theDurableAuditPoolCoexistsWithTheReadinessPoolWithoutAmbiguity() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.class, ReadinessPostgresConfiguration.class)
                .withPropertyValues(ENABLED)
                .withPropertyValues(COMPLETE_DATASOURCE)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(chainText(failure))
                            .doesNotContain("NoUniqueBeanDefinitionException")
                            .doesNotContain("No qualifying bean of type")
                            .doesNotContain(ProductionAuditTestFixtures.LEDGER_PASSWORD)
                            .doesNotContain("readiness-placeholder-secret")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1");
                    assertThat(poolFailureCode(failure)).isIn(
                            CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE,
                            ProductionAuditErrorCodes.PRODUCTION_AUDIT_POOL_UNAVAILABLE,
                            com.company.campaign.readiness.postgres.ReadinessPostgresErrorCodes
                                    .READINESS_POSTGRESQL_PROBE_UNAVAILABLE);
                });
    }

    @Test
    void everyAdapterIsPublishedWhenNoHostAdapterExists() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.AuditPortAdapters.class)
                .withBean(ProductionAuditConfiguration.LEDGER_BEAN_NAME, MyBatisPlusProductionAuditLedger.class,
                        ProductionAuditConfigurationTest::stubLedger)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionAuditConfiguration.SECURITY_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionSecurityAuditSink.class);
                    assertThat(context.getBean(ProductionAuditConfiguration.CONVERSATION_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionConversationAuditSink.class);
                    assertThat(context.getBean(ProductionAuditConfiguration.LIFECYCLE_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionLifecycleAuditSink.class);
                    assertThat(context.getBean(ProductionAuditConfiguration.RUNTIME_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionRuntimeAuditSink.class);
                    assertThat(context.getBean(ProductionAuditConfiguration.ACTION_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionActionAuditSink.class);
                });
    }

    @Test
    void exactlyOneHostAdapterOfAPortTakesPrecedence() {
        AuditSink hostSink = event -> {
        };
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.AuditPortAdapters.class)
                .withBean(ProductionAuditConfiguration.LEDGER_BEAN_NAME, MyBatisPlusProductionAuditLedger.class,
                        ProductionAuditConfigurationTest::stubLedger)
                .withBean("hostSecurityAuditSink", AuditSink.class, () -> hostSink)
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ProductionAuditConfiguration.SECURITY_SINK_BEAN_NAME))
                            .isSameAs(hostSink);
                    assertThat(context.getBean(ProductionAuditConfiguration.RUNTIME_SINK_BEAN_NAME))
                            .isInstanceOf(ProductionRuntimeAuditSink.class);
                });
    }

    @Test
    void moreThanOneHostAdapterOfAPortIsAnAmbiguousWiring() {
        runner()
                .withUserConfiguration(ProductionAuditConfiguration.AuditPortAdapters.class)
                .withBean(ProductionAuditConfiguration.LEDGER_BEAN_NAME, MyBatisPlusProductionAuditLedger.class,
                        ProductionAuditConfigurationTest::stubLedger)
                .withBean("hostSecurityAuditSinkOne", AuditSink.class, () -> event -> {
                })
                .withBean("hostSecurityAuditSinkTwo", AuditSink.class, () -> event -> {
                })
                .withPropertyValues(ENABLED)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionAuditErrorCodes.PRODUCTION_AUDIT_ADAPTER_AMBIGUOUS);
                });
    }

    // ------------------------------------------------------------------ helpers

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner();
    }

    private static MyBatisPlusProductionAuditLedger stubLedger() {
        ScriptedProductionAuditJdbcBoundary boundary = new ScriptedProductionAuditJdbcBoundary();
        return new MyBatisPlusProductionAuditLedger(
                MyBatisTestSupport.sessionFactory(boundary.dataSource()), ProductionAuditTestFixtures.ledger());
    }

    private static String failureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProductionAuditConfigurationException auditFailure) {
                return auditFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no bounded durable audit code");
    }

    private static String datasourceFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no unified datasource code");
    }

    private static String poolFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
            }
            if (cause instanceof ProductionAuditConfigurationException auditFailure) {
                return auditFailure.code();
            }
            if (cause instanceof ReadinessPostgresConfigurationException readinessFailure) {
                return readinessFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no bounded pool code");
    }

    private static String chainText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause).append(' ').append(cause.getMessage()).append(' ');
        }
        return text.toString();
    }
}
