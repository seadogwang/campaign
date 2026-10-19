package com.company.campaign.conversation.postgres;

import com.company.campaign.conversation.MyBatisPlusCampaignConversationStore;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentProperties;
import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.time.Clock;

/**
 * The focused opt-in configuration that turns the committed Campaign conversation migrations into a real
 * PostgreSQL runtime.
 *
 * <p>It is inert unless the local Agent host is enabled <em>and</em> the host selected
 * {@code campaign.agent.development.store.mode=POSTGRESQL}. When it is active it does exactly one thing,
 * fail-closed:
 *
 * <ol>
 *   <li>validates legacy host configuration against the unified business datasource; and</li>
 *   <li>publishes a {@link MyBatisPlusCampaignConversationStore} over the shared business
 *       {@link SqlSessionFactory}, so the store only ever exists against PostgreSQL.</li>
 * </ol>
 *
 * <p>The shared business SqlSessionFactory and Flyway infrastructure come from
 * {@code CampaignDatasourceConfiguration}, so no independent pool or migration run is created here.
 *
 * <p>A missing setting, an unreserved or reserved schema, an unreachable server or a failed migration aborts the
 * application start with a bounded code and a message that carries no credential and no JDBC URL.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ConversationPostgresProperties.class, CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.agent.development", name = "enabled", havingValue = "true")
public class ConversationPostgresConfiguration {

    private final ConversationPostgresProperties storeProperties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public ConversationPostgresConfiguration(
            ConversationPostgresProperties storeProperties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.storeProperties = storeProperties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        storeProperties.inheritFrom(campaignDatasourceProperties);
        storeProperties.validate();
        if (storeProperties.isPostgresql()) {
            campaignDatasourceProperties.validateForLegacy(
                    storeProperties.getUrl(), storeProperties.getUsername(),
                    storeProperties.getPassword(), storeProperties.getSchema());
        }
    }

    /**
     * The durable runtime, created only when the host explicitly selected PostgreSQL mode.
     *
     * <p>It is a nested member class rather than a second condition on the outer class, so each element carries
     * exactly one explicit condition and the disabled or in-memory default creates nothing at all.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "campaign.agent.development.store", name = "mode", havingValue = "POSTGRESQL")
    static class PostgresConversationRuntime {

        /** The one durable Campaign conversation store over the shared business SqlSessionFactory. */
        @Bean(name = "campaignAgentPostgresStore")
        @Qualifier("campaignAgentPostgresStore")
        public MyBatisPlusCampaignConversationStore campaignAgentPostgresStore(
                @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
                SqlSessionFactory campaignBusinessSqlSessionFactory,
                Clock clock,
                DevelopmentCampaignAgentProperties developmentProperties) {
            return new MyBatisPlusCampaignConversationStore(
                    campaignBusinessSqlSessionFactory,
                    clock,
                    developmentProperties.getMaxConversations(),
                    developmentProperties.getMaxOutcomesPerConversation(),
                    developmentProperties.getRetention());
        }
    }
}
