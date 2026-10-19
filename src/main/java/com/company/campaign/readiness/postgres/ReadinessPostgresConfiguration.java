package com.company.campaign.readiness.postgres;

import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import com.company.campaign.readiness.MyBatisPlusReadinessStore;
import com.company.campaign.readiness.ReadinessStorageProbe;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * The focused opt-in configuration that turns the accepted T25 migrations and JDBC adapter into a real
 * PostgreSQL runtime.
 *
 * <p>It is inert unless the host sets {@code campaign.datasource.enabled=true} (the unified business
 * datasource). When it is enabled it does exactly three things, all fail-closed:
 *
 * <ol>
 *   <li>validates legacy host configuration against the unified business datasource before anything connects;</li>
 *   <li>publishes a real {@link MyBatisPlusReadinessStore} over the shared business
 *       {@link SqlSessionFactory}, so the store only ever exists against PostgreSQL;</li>
 *   <li>publishes a bounded {@link ReadinessStorageProbe} computed once after the shared business migration
 *       run, so the local health endpoint can report whether the engine was actually exercised.</li>
 * </ol>
 *
 * <p>A missing setting, an unreachable server, a failed migration or a probe failure aborts the application
 * start with a bounded code and a message that carries no credential and no JDBC URL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ReadinessPostgresProperties.class, CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.datasource", name = "enabled", havingValue = "true")
public class ReadinessPostgresConfiguration {

    private final ReadinessPostgresProperties properties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public ReadinessPostgresConfiguration(
            ReadinessPostgresProperties properties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.properties = properties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    /**
     * Validates legacy readiness properties against the unified business datasource.
     *
     * <p>If the legacy readiness properties are enabled, the legacy configuration is validated on its own
     * and then checked for conflicts with the unified datasource.
     */
    @PostConstruct
    void validateLegacyConfiguration() {
        properties.inheritFrom(campaignDatasourceProperties);
        properties.validate();
        if (properties.isEnabled()) {
            campaignDatasourceProperties.validateForLegacy(
                    properties.getUrl(), properties.getUsername(),
                    properties.getPassword(), properties.getSchema());
        }
    }

    @Bean
    public MyBatisPlusReadinessStore readinessJdbcStore(
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            SqlSessionFactory campaignBusinessSqlSessionFactory) {
        return new MyBatisPlusReadinessStore(campaignBusinessSqlSessionFactory);
    }

    /** The one-shot probe, ordered after the migration run by its explicit dependency on the initializer. */
    @Bean
    public ReadinessStorageProbe readinessStorageProbe(
            @Qualifier(CampaignDatasourceProperties.DATASOURCE_BEAN_NAME) DataSource campaignBusinessDataSource,
            @Qualifier("campaignBusinessFlywayInitializer") FlywayMigrationInitializer campaignBusinessFlywayInitializer,
            ReadinessPostgresProperties properties) {
        return new PostgresReadinessStorageProbe(campaignBusinessDataSource, properties);
    }
}
