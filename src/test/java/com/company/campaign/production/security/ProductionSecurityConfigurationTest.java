package com.company.campaign.production.security;

import com.company.campaign.readiness.postgres.ReadinessPostgresConfiguration;
import com.company.campaign.readiness.postgres.ReadinessPostgresConfigurationException;
import com.company.campaign.readiness.postgres.ReadinessPostgresErrorCodes;
import com.company.campaign.persistence.CampaignDatasourceConfigurationException;
import com.company.campaign.persistence.CampaignDatasourceErrorCodes;
import com.company.campaign.security.context.TrustedContextProvider;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in wiring of the production identity boundary.
 *
 * <p>It proves the boundary is inert by default, that enabling it publishes exactly one coherent runtime, that a
 * missing or ambiguous configuration fails closed with one bounded code, that the development host cannot be
 * enabled together with it, and that its qualified pool coexists with the readiness pool without an ambiguous
 * dependency.
 */
class ProductionSecurityConfigurationTest {

    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private static WebApplicationContextRunner runner() {
        return new WebApplicationContextRunner();
    }

    @Test
    void theDisabledDefaultCreatesNoProductionIdentityBeanAtAll() {
        runner()
                .withUserConfiguration(ProductionSecurityInertConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(ProductionMembershipRepository.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(TrustedContextProvider.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
                });
    }

    @Test
    void theDisabledProductionBoundaryCoexistsWithTheDevelopmentHost() {
        runner()
                .withUserConfiguration(ProductionSecurityInertConfiguration.class)
                .withPropertyValues("campaign.agent.development.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(ProductionHostExclusionGuard.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
                });
    }

    @Test
    void completeHostModeEnablementPublishesExactlyOneCoherentRuntime() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean("hostMembershipRepository", ProductionMembershipRepository.class,
                        () -> (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(NOW)))
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues("campaign.production.membership.mode=HOST")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(JwtDecoder.class)).hasSize(2);
                    assertThat(context.getBean(ProductionSecurityConfiguration.DECODER_BEAN_NAME))
                            .isSameAs(context.getBean(NimbusJwtDecoder.class));
                    assertThat(context.getBeanNamesForType(ProductionMembershipRepository.class)).hasSize(2);
                    assertThat(context.getBean(ProductionSecurityConfiguration.REPOSITORY_BEAN_NAME))
                            .isSameAs(context.getBean("hostMembershipRepository"));
                    assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
                    assertThat(Arrays.stream(context.getBeanNamesForType(TrustedContextProvider.class))
                            .filter(name -> !name.startsWith("scopedTarget."))
                            .toList())
                            .containsExactly(ProductionSecurityConfiguration.PROVIDER_BEAN_NAME);
                    assertThat(context.getBeanNamesForType(DataSource.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HikariDataSource.class)).isEmpty();
                });
    }

    @Test
    void aMissingPostgresqlMembershipSettingFailsClosedWithABoundedCode() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues("campaign.production.membership.mode=POSTGRESQL")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionSecurityErrorCodes.PRODUCTION_SECURITY_CONFIGURATION_INVALID);
                });
    }

    @Test
    void hostModeWithoutAHostRepositoryFailsClosedWithABoundedCode() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues("campaign.production.membership.mode=HOST")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_REPOSITORY_REQUIRED);
                });
    }

    @Test
    void twoHostRepositoriesFailClosedAsAmbiguous() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean("hostMembershipRepositoryOne", ProductionMembershipRepository.class,
                        () -> (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(NOW)))
                .withBean("hostMembershipRepositoryTwo", ProductionMembershipRepository.class,
                        () -> (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(NOW)))
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues("campaign.production.membership.mode=HOST")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_REPOSITORY_AMBIGUOUS);
                });
    }

    @Test
    void enablingTheDevelopmentHostAndTheProductionBoundaryTogetherFailsClosed() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean("hostMembershipRepository", ProductionMembershipRepository.class,
                        () -> (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(NOW)))
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues(
                        "campaign.production.membership.mode=HOST",
                        "campaign.agent.development.enabled=true")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionSecurityErrorCodes.PRODUCTION_DEVELOPMENT_HOST_CONFLICT);
                });
    }

    @Test
    void aMissingJwkSetUriWithNoHostDecoderFailsClosedWithABoundedCode() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class)
                .withBean("hostMembershipRepository", ProductionMembershipRepository.class,
                        () -> (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(NOW)))
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues("campaign.production.membership.mode=HOST")
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(failureCode(context.getStartupFailure()))
                            .isEqualTo(ProductionSecurityErrorCodes.PRODUCTION_JWK_SET_UNAVAILABLE);
                });
    }

    @Test
    void theProductionAndReadinessPoolsCoexistByQualifierWithoutAmbiguity() {
        runner()
                .withUserConfiguration(ProductionSecurityConfiguration.class, ReadinessPostgresConfiguration.class)
                .withBean(NimbusJwtDecoder.class, ProductionTestFixtures::hostDecoder)
                .withPropertyValues(enabledJwtProperties())
                .withPropertyValues(
                        "campaign.production.membership.mode=POSTGRESQL",
                        "campaign.production.jwt.jwk-set-uri=https://production.jwks.test/keys",
                        "campaign.datasource.enabled=true",
                        "campaign.datasource.url=jdbc:postgresql://127.0.0.1:1/membership",
                        "campaign.datasource.username=production-user",
                        "campaign.datasource.password=production-secret",
                        "campaign.datasource.schema=campaign_production_t35a1",
                        "campaign.datasource.connection-timeout-ms=250",
                        "campaign.datasource.validation-timeout-ms=250")
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isNotNull();
                    assertThat(messages(failure))
                            .doesNotContain("NoUniqueBeanDefinitionException")
                            .doesNotContain("No qualifying bean of type")
                            .doesNotContain("production-secret")
                            .doesNotContain("readiness-secret")
                            .doesNotContain("jdbc:postgresql://127.0.0.1:1");
                    assertThat(poolFailureCode(failure)).isIn(
                            CampaignDatasourceErrorCodes.CAMPAIGN_DATASOURCE_POOL_UNAVAILABLE,
                            ProductionSecurityErrorCodes.PRODUCTION_MEMBERSHIP_POOL_UNAVAILABLE,
                            ReadinessPostgresErrorCodes.READINESS_POSTGRESQL_PROBE_UNAVAILABLE);
                });
    }

    // ------------------------------------------------------------------ helpers

    private static String[] enabledJwtProperties() {
        return new String[] {
                "campaign.production.enabled=true",
                "campaign.production.jwt.issuer-uri=" + ProductionTestFixtures.ISSUER,
                "campaign.production.jwt.audience=" + ProductionTestFixtures.AUDIENCE};
    }

    private static String failureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProductionSecurityConfigurationException productionFailure) {
                return productionFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no bounded production code");
    }

    private static String poolFailureCode(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CampaignDatasourceConfigurationException datasourceFailure) {
                return datasourceFailure.code();
            }
            if (cause instanceof ProductionSecurityConfigurationException productionFailure) {
                return productionFailure.code();
            }
            if (cause instanceof ReadinessPostgresConfigurationException readinessFailure) {
                return readinessFailure.code();
            }
        }
        throw new AssertionError("the failure chain carries no bounded pool code");
    }

    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            text.append(cause).append(' ').append(cause.getMessage()).append(' ');
        }
        return text.toString();
    }
}
