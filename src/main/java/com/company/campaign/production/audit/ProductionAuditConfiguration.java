package com.company.campaign.production.audit;

import com.company.campaign.action.ActionAuditSink;
import com.company.campaign.conversation.CampaignConversationAuditSink;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import com.company.campaign.runtime.RuntimeAuditSink;
import com.company.campaign.security.audit.AuditSink;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.List;

/**
 * The one opt-in configuration of the default-off production durable audit ledger.
 *
 * <p>It is inert unless the host sets <em>both</em> {@code campaign.production.enabled=true} and
 * {@code campaign.production.audit.enabled=true}. When either is off — the default — the application creates no
 * audit DataSource, no Flyway runtime, no migration run, no ledger and no adapter at all, so the accepted T35A1
 * production identity boundary can be enabled on its own without any durable audit bean existing.
 *
 * <p>When it is on, it wires fail-closed and in one place: the one append-only
 * {@link MyBatisPlusProductionAuditLedger} over the shared business {@link SqlSessionFactory}; and one production
 * adapter for each of the five accepted audit ports. Every bean has an explicit name and qualifier, so the
 * durable audit pool, the readiness pool, the production membership pool and the conversation pool coexist
 * without an ambiguous {@code DataSource}, {@code Flyway} or {@code FlywayMigrationInitializer} dependency.
 *
 * <p>A host-supplied adapter of any accepted port has documented precedence: exactly one host bean of that port
 * type is used instead of this module's adapter, and more than one is an ambiguous wiring that aborts startup
 * with one bounded code rather than being resolved by bean order. No credential, URL, schema name, event
 * document or driver message is ever committed here or printed by any log, envelope or health surface.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProductionAuditProperties.class, CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "campaign.production.audit", name = "enabled", havingValue = "true")
public class ProductionAuditConfiguration {

    /** The one append-only durable audit writer. */
    public static final String LEDGER_BEAN_NAME = "campaignProductionAuditLedger";

    /** The production adapter of {@link AuditSink}. */
    public static final String SECURITY_SINK_BEAN_NAME = "campaignProductionSecurityAuditSink";

    /** The production adapter of {@link CampaignConversationAuditSink}. */
    public static final String CONVERSATION_SINK_BEAN_NAME = "campaignProductionConversationAuditSink";

    /** The production adapter of {@link LifecycleAuditSink}. */
    public static final String LIFECYCLE_SINK_BEAN_NAME = "campaignProductionLifecycleAuditSink";

    /** The production adapter of {@link RuntimeAuditSink}. */
    public static final String RUNTIME_SINK_BEAN_NAME = "campaignProductionRuntimeAuditSink";

    /** The production adapter of {@link ActionAuditSink}. */
    public static final String ACTION_SINK_BEAN_NAME = "campaignProductionActionAuditSink";

    private final ProductionAuditProperties properties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public ProductionAuditConfiguration(
            ProductionAuditProperties properties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.properties = properties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        properties.inheritDatasourceFrom(campaignDatasourceProperties);
        properties.validate();
        if (properties.isEnabled()) {
            campaignDatasourceProperties.validateForLegacy(
                    properties.getLedger().getUrl(), properties.getLedger().getUsername(),
                    properties.getLedger().getPassword(), properties.getLedger().getSchema());
        }
    }

    /**
     * The one append-only durable audit writer.
     *
     * <p>It is backed by the shared business {@link SqlSessionFactory} from
     * {@code CampaignDatasourceConfiguration}. The shared migration run is ordered before the ledger can exist,
     * so the ledger can never address a table that has not been created.
     */
    @Bean(name = LEDGER_BEAN_NAME)
    @Qualifier(LEDGER_BEAN_NAME)
    public MyBatisPlusProductionAuditLedger campaignProductionAuditLedger(
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            SqlSessionFactory campaignBusinessSqlSessionFactory) {
        properties.validate();
        return new MyBatisPlusProductionAuditLedger(campaignBusinessSqlSessionFactory, properties.getLedger());
    }

    /**
     * The five production adapters of the accepted audit ports.
     *
     * <p>They are a separate nested configuration from the ledger so the two halves own disjoint bean names
     * and can never shadow each other. The two opt-in conditions are repeated here because component scanning
     * can register a nested configuration class on its own, and without them a production-disabled or
     * audit-disabled application would try to build adapters it has no ledger for.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "campaign.production.audit", name = "enabled", havingValue = "true")
    static class AuditPortAdapters {

        /** The production adapter of the security audit port, unless a host adapter takes precedence. */
        @Bean(name = SECURITY_SINK_BEAN_NAME)
        @Qualifier(SECURITY_SINK_BEAN_NAME)
        public AuditSink campaignProductionSecurityAuditSink(
                @Qualifier(LEDGER_BEAN_NAME) MyBatisPlusProductionAuditLedger ledger,
                ListableBeanFactory beanFactory) {
            AuditSink host = singleHostAdapter(beanFactory, AuditSink.class, SECURITY_SINK_BEAN_NAME);
            return host != null ? host : new ProductionSecurityAuditSink(ledger);
        }

        /** The production adapter of the conversation audit port, unless a host adapter takes precedence. */
        @Bean(name = CONVERSATION_SINK_BEAN_NAME)
        @Qualifier(CONVERSATION_SINK_BEAN_NAME)
        public CampaignConversationAuditSink campaignProductionConversationAuditSink(
                @Qualifier(LEDGER_BEAN_NAME) MyBatisPlusProductionAuditLedger ledger,
                ListableBeanFactory beanFactory) {
            CampaignConversationAuditSink host =
                    singleHostAdapter(beanFactory, CampaignConversationAuditSink.class, CONVERSATION_SINK_BEAN_NAME);
            return host != null ? host : new ProductionConversationAuditSink(ledger);
        }

        /** The production adapter of the lifecycle audit port, unless a host adapter takes precedence. */
        @Bean(name = LIFECYCLE_SINK_BEAN_NAME)
        @Qualifier(LIFECYCLE_SINK_BEAN_NAME)
        public LifecycleAuditSink campaignProductionLifecycleAuditSink(
                @Qualifier(LEDGER_BEAN_NAME) MyBatisPlusProductionAuditLedger ledger,
                ListableBeanFactory beanFactory) {
            LifecycleAuditSink host =
                    singleHostAdapter(beanFactory, LifecycleAuditSink.class, LIFECYCLE_SINK_BEAN_NAME);
            return host != null ? host : new ProductionLifecycleAuditSink(ledger);
        }

        /** The production adapter of the runtime audit port, unless a host adapter takes precedence. */
        @Bean(name = RUNTIME_SINK_BEAN_NAME)
        @Qualifier(RUNTIME_SINK_BEAN_NAME)
        public RuntimeAuditSink campaignProductionRuntimeAuditSink(
                @Qualifier(LEDGER_BEAN_NAME) MyBatisPlusProductionAuditLedger ledger,
                ListableBeanFactory beanFactory) {
            RuntimeAuditSink host = singleHostAdapter(beanFactory, RuntimeAuditSink.class, RUNTIME_SINK_BEAN_NAME);
            return host != null ? host : new ProductionRuntimeAuditSink(ledger);
        }

        /** The production adapter of the action audit port, unless a host adapter takes precedence. */
        @Bean(name = ACTION_SINK_BEAN_NAME)
        @Qualifier(ACTION_SINK_BEAN_NAME)
        public ActionAuditSink campaignProductionActionAuditSink(
                @Qualifier(LEDGER_BEAN_NAME) MyBatisPlusProductionAuditLedger ledger,
                ListableBeanFactory beanFactory) {
            ActionAuditSink host = singleHostAdapter(beanFactory, ActionAuditSink.class, ACTION_SINK_BEAN_NAME);
            return host != null ? host : new ProductionActionAuditSink(ledger);
        }

        /**
         * Resolves at most one host-supplied adapter of one accepted port.
         *
         * <p>Zero host adapters means this module's own adapter is published; one means the host adapter has
         * documented precedence; more than one is ambiguous and fails startup with one bounded code rather than
         * being resolved by bean order or by name.
         */
        static <T> T singleHostAdapter(ListableBeanFactory beanFactory, Class<T> portType, String ownName) {
            List<String> hostNames = new ArrayList<>();
            for (String name : beanFactory.getBeanNamesForType(portType, true, true)) {
                if (!ownName.equals(name)) {
                    hostNames.add(name);
                }
            }
            if (hostNames.size() > 1) {
                throw new ProductionAuditConfigurationException(
                        ProductionAuditErrorCodes.PRODUCTION_AUDIT_ADAPTER_AMBIGUOUS,
                        "The durable audit ledger requires at most one host adapter of one accepted audit port.");
            }
            return hostNames.isEmpty() ? null : beanFactory.getBean(hostNames.get(0), portType);
        }
    }
}
