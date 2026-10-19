package com.company.campaign.production.campaignagent;

import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignBusinessResourceTool;
import com.company.campaign.builder.campaignagent.CampaignSchemaRepairPolicy;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationHistoryService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.builder.segment.SegmentIntentModelPort;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.campaignagent.CampaignAssemblyContextFactory;
import com.company.campaign.campaignagent.CampaignConversationCreateService;
import com.company.campaign.campaignagent.CampaignConversationHistoryService;
import com.company.campaign.campaignagent.CampaignConversationTurnService;
import com.company.campaign.campaignagent.CampaignSegmentSourceProvider;
import com.company.campaign.conversation.CampaignConversationHistoryStore;
import com.company.campaign.conversation.CampaignConversationIdentitySource;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.conversation.CampaignConversationStoreDurability;
import com.company.campaign.conversation.DurableCampaignConversationAuditSink;
import com.company.campaign.conversation.DurableCampaignConversationStore;
import com.company.campaign.conversation.MyBatisPlusCampaignBusinessResourceTool;
import com.company.campaign.conversation.MyBatisPlusCampaignConversationStore;
import com.company.campaign.model.StructuredCampaignRoutingModel;
import com.company.campaign.model.StructuredCampaignRequirementExtractor;
import com.company.campaign.model.StructuredModelPort;
import com.company.campaign.model.StructuredSkillInputResolvers;
import com.company.campaign.persistence.CampaignDatasourceProperties;
import com.company.campaign.persistence.CampaignDatasourceConfiguration;
import com.company.campaign.production.model.ProductionModelConfiguration;
import com.company.campaign.production.security.ProductionSecurityConfiguration;
import com.company.campaign.security.context.TrustedContextProvider;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * The focused opt-in configuration of the default-off production Campaign Agent host.
 *
 * <p>It is inert unless the host sets {@code campaign.production.enabled=true} <em>and</em>
 * {@code campaign.production.campaign-agent.enabled=true}, and it can never be active while the development
 * Campaign Agent host is enabled. When it is off — the default — the application creates no production
 * conversation store, no identity source, no environment, no turn or create service and no Campaign Agent route
 * mapping beyond the accepted development host.
 *
 * <p>When it is on it wires, fail-closed and in one place: the production request-scoped trusted-context
 * provider; one durable Campaign conversation store and one durable Campaign conversation audit sink (an in-memory
 * store is refused twice: it is not a {@link DurableCampaignConversationStore} and it cannot expose the accepted
 * {@link CampaignConversationStoreDurability#POSTGRESQL} durability claim); the one
 * production structured model, which must also serve as the Segment intent model; exactly one genuine Preview
 * port; exactly one production Campaign asset port and exactly one production Segment asset port; the
 * cryptographically strong server-owned conversation identity source; the neutral create and turn services over
 * the neutral create/turn application logic; and the accepted strict controllers.
 *
 * <p>Every missing, ambiguous or incompatible dependency aborts the start with one bounded, redacted code
 * rather than silently substituting a second model, a fake Preview, an in-memory registry or a development
 * identity. No connector version of the accepted controllers or routes is added: they remain
 * {@code POST /api/v1/campaign-agent/conversations} and {@code POST /api/v1/campaign-agent/turns}, and no
 * trusted field is read from the request body — identity comes only from the request-scoped T35A1 provider.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ProductionCampaignAgentProperties.class, CampaignDatasourceProperties.class})
@Import(CampaignDatasourceConfiguration.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "campaign.production.campaign-agent", name = "enabled", havingValue = "true")
@ConditionalOnProperty(
        prefix = "campaign.agent.development", name = "enabled", havingValue = "false", matchIfMissing = true)
public class ProductionCampaignAgentConfiguration {

    /** The one durable production Campaign conversation store. */
    public static final String STORE_BEAN_NAME = "campaignProductionCampaignAgentStore";

    /** The one cryptographically strong, server-owned conversation identity source. */
    public static final String IDENTITY_SOURCE_BEAN_NAME = "campaignProductionConversationIdentitySource";

    /** The one production Campaign Agent environment. */
    public static final String ENVIRONMENT_BEAN_NAME = "campaignProductionCampaignAgentEnvironment";

    /** The one production conversation-create service. */
    public static final String CONVERSATION_SERVICE_BEAN_NAME = "campaignProductionCampaignAgentConversationService";

    /** The one production conversation-turn service. */
    public static final String TURN_SERVICE_BEAN_NAME = "campaignProductionCampaignAgentTurnService";

    /** The one production read-only conversation-history service bean name. */
    public static final String HISTORY_SERVICE_BEAN_NAME =
            "campaignProductionCampaignAgentConversationHistoryService";

    private final ProductionCampaignAgentProperties properties;
    private final CampaignDatasourceProperties campaignDatasourceProperties;

    public ProductionCampaignAgentConfiguration(
            ProductionCampaignAgentProperties properties,
            CampaignDatasourceProperties campaignDatasourceProperties) {
        this.properties = properties;
        this.campaignDatasourceProperties = campaignDatasourceProperties;
    }

    @PostConstruct
    void validateLegacyConfiguration() {
        properties.inheritDatasourceFrom(campaignDatasourceProperties);
        properties.validate();
        if (properties.isEnabled()) {
            ProductionCampaignAgentProperties.Store store = properties.getStore();
            if (store.getUrl() != null && !store.getUrl().isBlank()) {
                campaignDatasourceProperties.validateForLegacy(
                        store.getUrl(), store.getUsername(),
                        store.getPassword(), store.getSchema());
            }
        }
    }

    /** The one production clock; every production timestamp is read from it, never from {@code Instant.now()}. */
    @Bean
    public Clock campaignProductionCampaignAgentClock() {
        return Clock.systemUTC();
    }

    /**
     * The one durable Campaign conversation store of the production host.
     *
     * <p>A host-supplied {@link DurableCampaignConversationStore} bean has documented precedence, and more than
     * one is an ambiguous wiring that aborts the start. With none supplied, the shared business
     * {@link SqlSessionFactory} is used; a missing factory is a fail-closed startup error rather than a silent
     * in-memory fallback. Whichever store is resolved — host-supplied or host-constructed — must expose the
     * accepted {@link CampaignConversationStoreDurability#POSTGRESQL} durability claim, and an absent, in-memory,
     * development-only, unverified or otherwise non-accepted claim aborts the start with the same bounded
     * {@code DURABLE_STORE_REQUIRED} code. A bare Java type is never a durability proof: a
     * {@link CampaignConversationStore} that is not a {@link DurableCampaignConversationStore} cannot be resolved,
     * and a durable-typed store still passes only when its claim is accepted.
     */
    @Bean(name = STORE_BEAN_NAME)
    @Primary
    public DurableCampaignConversationStore campaignProductionCampaignAgentStore(
            ListableBeanFactory beanFactory,
            Clock campaignProductionCampaignAgentClock,
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactories) {
        properties.validate();
        List<String> hostNames = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(DurableCampaignConversationStore.class, true, true)) {
            if (!STORE_BEAN_NAME.equals(name)) {
                hostNames.add(name);
            }
        }
        if (hostNames.size() > 1) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.DURABLE_STORE_AMBIGUOUS,
                    "The production Campaign Agent host requires at most one durable conversation store.");
        }
        if (hostNames.size() == 1) {
            return requireAcceptedDurability(
                    beanFactory.getBean(hostNames.get(0), DurableCampaignConversationStore.class));
        }
        SqlSessionFactory sessionFactory = sessionFactories.getIfAvailable();
        if (sessionFactory == null) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED,
                    "The production Campaign Agent host requires a durable conversation store or the "
                            + "shared business SqlSessionFactory.");
        }
        return requireAcceptedDurability(new MyBatisPlusCampaignConversationStore(
                sessionFactory,
                campaignProductionCampaignAgentClock,
                properties.getMaxConversations(),
                properties.getMaxOutcomesPerConversation(),
                properties.getRetention()));
    }

    /**
     * Returns the resolved store only when it exposes the accepted PostgreSQL durability claim, and otherwise
     * aborts the start with the one bounded durable-store-required code.
     *
     * <p>The Java type alone is not a durability proof: a host-supplied store can implement
     * {@link DurableCampaignConversationStore} and still be an in-memory or development-only registry, and the
     * host's own adapter exposes the same inspectable claim. An absent claim and every non-accepted claim are
     * refused here, so the produced bean is the only store the create and turn services can ever resolve.
     */
    private static DurableCampaignConversationStore requireAcceptedDurability(
            DurableCampaignConversationStore store) {
        CampaignConversationStoreDurability claim = store.durability();
        if (claim == null || !claim.accepted()) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED,
                    "The production Campaign Agent host requires a conversation store that exposes the accepted "
                            + "PostgreSQL durability claim.");
        }
        return store;
    }

    /** The cryptographically strong, server-owned conversation identity source of the production host. */
    @Bean(name = IDENTITY_SOURCE_BEAN_NAME)
    @Qualifier(IDENTITY_SOURCE_BEAN_NAME)
    public CampaignConversationIdentitySource campaignProductionConversationIdentitySource() {
        return new ProductionConversationIdentitySource();
    }

    /**
     * The one production Campaign Agent environment over the production model, assets and Preview port.
     *
     * <p>Every required dependency is resolved here, and every missing, ambiguous or incompatible one aborts the
     * start with one bounded code. The model must be the one production structured model and must also serve as
     * the Segment intent model, so the same exact instance answers Campaign routing, every Skill input resolver
     * and Segment intent extraction.
     */
    @Bean(name = ENVIRONMENT_BEAN_NAME)
    @Qualifier(ENVIRONMENT_BEAN_NAME)
    public CampaignAgentEnvironment campaignProductionCampaignAgentEnvironment(
            @Qualifier(ProductionSecurityConfiguration.PROVIDER_BEAN_NAME)
            ObjectProvider<TrustedContextProvider> trustedContextProviders,
            @Qualifier(ProductionModelConfiguration.MODEL_BEAN_NAME)
            ObjectProvider<StructuredModelPort> models,
            ObjectProvider<ProductionCampaignAssets> campaignAssets,
            ObjectProvider<ProductionSegmentAssets> segmentAssets,
            ObjectProvider<SegmentPreviewPort> previewPorts,
            ObjectProvider<CampaignSegmentSourceProvider> segmentSourceProviders,
            ObjectProvider<CampaignBusinessResourceTool> businessResourceTools,
            @Qualifier(CampaignDatasourceProperties.SQL_SESSION_FACTORY_BEAN_NAME)
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders,
            Clock campaignProductionCampaignAgentClock) {
        TrustedContextProvider trustedContextProvider = required(
                trustedContextProviders, TrustedContextProvider.class,
                ProductionCampaignAgentErrorCodes.PRODUCTION_SECURITY_REQUIRED,
                "The production Campaign Agent host requires the production trusted-context provider.");
        StructuredModelPort model = required(
                models, StructuredModelPort.class, ProductionCampaignAgentErrorCodes.MODEL_REQUIRED,
                "The production Campaign Agent host requires exactly one production structured model.");
        // A model that cannot serve Segment intent is the most specific wiring fault, so it is reported first.
        if (!(model instanceof SegmentIntentModelPort intentModel)) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE,
                    "The selected production structured model cannot also serve as the Segment intent model, so "
                            + "the real Segment source cannot be built from it.");
        }
        ProductionCampaignAssets assets = required(
                campaignAssets, ProductionCampaignAssets.class,
                ProductionCampaignAgentErrorCodes.ASSETS_REQUIRED,
                "The production Campaign Agent host requires exactly one production asset port.");
        SegmentPreviewPort previewPort = required(
                previewPorts, SegmentPreviewPort.class, ProductionCampaignAgentErrorCodes.PREVIEW_REQUIRED,
                "The production Campaign Agent host requires exactly one genuine Preview port.");
        CampaignSegmentSourceProvider segmentSourceProvider = resolveSegmentSource(
                segmentSourceProviders, segmentAssets, assets, intentModel, previewPort,
                campaignProductionCampaignAgentClock, trustedContextProvider);
        CampaignSchemaRepairPolicy repairPolicy = properties.isSchemaRepairEnabled()
                ? CampaignSchemaRepairPolicy.ONE_REPAIR
                : CampaignSchemaRepairPolicy.NONE;
        CampaignBusinessResourceTool businessResourceTool = resolveBusinessResourceTool(
                businessResourceTools, sessionFactoryProviders);
        return new ProductionCampaignAgentEnvironment(
                new StructuredCampaignRoutingModel(model),
                repairPolicy,
                assets,
                trustedContextProvider,
                segmentSourceProvider,
                new StructuredSkillInputResolvers(model),
                new StructuredCampaignRequirementExtractor(
                        model, campaignProductionCampaignAgentClock,
                        () -> trustedContextProvider.trustedContext().snapshotRef().timezone()),
                campaignProductionCampaignAgentClock,
                businessResourceTool);
    }

    private static CampaignBusinessResourceTool resolveBusinessResourceTool(
            ObjectProvider<CampaignBusinessResourceTool> providers,
            ObjectProvider<SqlSessionFactory> sessionFactoryProviders) {
        List<CampaignBusinessResourceTool> supplied = providers.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.RESOURCE_CATALOG_REQUIRED,
                    "The production Campaign Agent host requires exactly one business-resource Tool.");
        }
        if (!supplied.isEmpty()) {
            return supplied.getFirst();
        }
        SqlSessionFactory sessionFactory = sessionFactoryProviders.getIfAvailable();
        if (sessionFactory == null) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.RESOURCE_CATALOG_REQUIRED,
                    "The production Campaign Agent host requires a database-backed business-resource Tool.");
        }
        return new MyBatisPlusCampaignBusinessResourceTool(sessionFactory);
    }

    /**
     * The one Segment source provider of the production host.
     *
     * <p>A host-supplied {@link CampaignSegmentSourceProvider} bean has documented precedence and more than one
     * is an ambiguous wiring that aborts the start. With none supplied, the host builds the real production
     * Segment source over the accepted Segment Agent, authoring integration and Preview chain, using the same
     * production model object the Campaign host uses — never a second model and never a fake Preview.
     */
    private static CampaignSegmentSourceProvider resolveSegmentSource(
            ObjectProvider<CampaignSegmentSourceProvider> providers,
            ObjectProvider<ProductionSegmentAssets> segmentAssetProviders,
            ProductionCampaignAssets campaignAssets,
            SegmentIntentModelPort intentModel,
            SegmentPreviewPort previewPort,
            Clock clock,
            TrustedContextProvider trustedContextProvider) {
        List<CampaignSegmentSourceProvider> supplied = providers.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                    "The production Campaign Agent host requires at most one Segment source provider.");
        }
        if (!supplied.isEmpty()) {
            return supplied.getFirst();
        }
        ProductionSegmentAssets segmentAssets = required(
                segmentAssetProviders, ProductionSegmentAssets.class,
                ProductionCampaignAgentErrorCodes.SEGMENT_ASSETS_REQUIRED,
                "The production Campaign Agent host requires exactly one production Segment asset port.");
        if (segmentAssets.segmentSkill() == null || segmentAssets.snapshot() == null) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.SEGMENT_ASSETS_REQUIRED,
                    "The production Segment asset port carries no committed Skill definition or pinned "
                            + "Metadata snapshot, so no real Segment source can be built from it.");
        }
        try {
            return ProductionSegmentAgentSourceProvider.of(
                    segmentAssets, campaignAssets, intentModel, previewPort, clock, trustedContextProvider);
        } catch (ProductionCampaignAgentConfigurationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.SEGMENT_ASSETS_REQUIRED,
                    "The production Segment asset port could not build the formal Segment Agent environment.",
                    failure);
        }
    }

    /** The one production conversation-create service over the production identity and durable store. */
    @Bean(name = CONVERSATION_SERVICE_BEAN_NAME)
    @Qualifier(CONVERSATION_SERVICE_BEAN_NAME)
    public CampaignAgentConversationService campaignProductionCampaignAgentConversationService(
            @Qualifier(ProductionSecurityConfiguration.PROVIDER_BEAN_NAME)
            ObjectProvider<TrustedContextProvider> trustedContextProviders,
            @Qualifier(STORE_BEAN_NAME) ObjectProvider<DurableCampaignConversationStore> stores,
            @Qualifier(IDENTITY_SOURCE_BEAN_NAME)
            ObjectProvider<CampaignConversationIdentitySource> identitySources) {
        TrustedContextProvider trustedContextProvider = required(
                trustedContextProviders, TrustedContextProvider.class,
                ProductionCampaignAgentErrorCodes.PRODUCTION_SECURITY_REQUIRED,
                "The production Campaign Agent host requires the production trusted-context provider.");
        DurableCampaignConversationStore store = required(
                stores, DurableCampaignConversationStore.class,
                ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED,
                "The production Campaign Agent host requires exactly one durable conversation store.");
        CampaignConversationIdentitySource identitySource = required(
                identitySources, CampaignConversationIdentitySource.class,
                ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The production Campaign Agent host requires exactly one conversation identity source.");
        return new CampaignConversationCreateService(trustedContextProvider, store, identitySource);
    }

    /** The accepted strict conversation-create HTTP adapter, registered only in the production host. */
    @Bean
    public CampaignAgentConversationController campaignProductionCampaignAgentConversationController(
            @Qualifier(CONVERSATION_SERVICE_BEAN_NAME) CampaignAgentConversationService service) {
        return new CampaignAgentConversationController(service);
    }

    /**
     * The one production conversation-turn service over the production environment, durable store and durable
     * audit sink.
     *
     * <p>The audit sink must be a {@link DurableCampaignConversationAuditSink}: the development in-memory sink
     * implements only {@link com.company.campaign.conversation.CampaignConversationAuditSink} and is therefore
     * rejected by type.
     */
    @Bean(name = TURN_SERVICE_BEAN_NAME)
    @Qualifier(TURN_SERVICE_BEAN_NAME)
    public CampaignAgentTurnService campaignProductionCampaignAgentTurnService(
            @Qualifier(ProductionSecurityConfiguration.PROVIDER_BEAN_NAME)
            ObjectProvider<TrustedContextProvider> trustedContextProviders,
            @Qualifier(ENVIRONMENT_BEAN_NAME) ObjectProvider<CampaignAgentEnvironment> environments,
            @Qualifier(STORE_BEAN_NAME) ObjectProvider<DurableCampaignConversationStore> stores,
            ObjectProvider<DurableCampaignConversationAuditSink> auditSinks,
            ObjectProvider<ProductionCampaignAssets> campaignAssets,
            Clock campaignProductionCampaignAgentClock) {
        TrustedContextProvider trustedContextProvider = required(
                trustedContextProviders, TrustedContextProvider.class,
                ProductionCampaignAgentErrorCodes.PRODUCTION_SECURITY_REQUIRED,
                "The production Campaign Agent host requires the production trusted-context provider.");
        CampaignAgentEnvironment environment = required(
                environments, CampaignAgentEnvironment.class,
                ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The production Campaign Agent host requires exactly one Campaign Agent environment.");
        ProductionCampaignAssets assets = required(
                campaignAssets, ProductionCampaignAssets.class,
                ProductionCampaignAgentErrorCodes.ASSETS_REQUIRED,
                "The production Campaign Agent host requires exactly one production asset port.");
        DurableCampaignConversationStore store = required(
                stores, DurableCampaignConversationStore.class,
                ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED,
                "The production Campaign Agent host requires exactly one durable conversation store.");
        DurableCampaignConversationAuditSink auditSink = required(
                auditSinks, DurableCampaignConversationAuditSink.class,
                ProductionCampaignAgentErrorCodes.DURABLE_AUDIT_REQUIRED,
                "The production Campaign Agent host requires exactly one durable conversation audit sink.");
        CampaignAssemblyContextFactory assemblyContextFactory = assets.assemblyContextFactory();
        if (assemblyContextFactory == null) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.ASSETS_REQUIRED,
                    "The production asset port carries no assembly-context factory.");
        }
        return new CampaignConversationTurnService(
                trustedContextProvider, environment, store, assemblyContextFactory, auditSink,
                campaignProductionCampaignAgentClock);
    }

    /** The accepted strict turn HTTP adapter, registered only in the production host. */
    @Bean
    public CampaignAgentTurnController campaignProductionCampaignAgentTurnController(
            @Qualifier(TURN_SERVICE_BEAN_NAME) CampaignAgentTurnService service) {
        return new CampaignAgentTurnController(service);
    }

    /**
     * The one production read-only conversation-history service over the same durable store and environment.
     *
     * <p>The resolved durable store must also implement {@link CampaignConversationHistoryStore}. A host store
     * that cannot enumerate its own conversations fails startup closed here instead of letting the left sidebar
     * render an empty or invented history, and no in-memory registry is ever substituted.
     */
    @Bean(name = HISTORY_SERVICE_BEAN_NAME)
    @Qualifier(HISTORY_SERVICE_BEAN_NAME)
    public CampaignAgentConversationHistoryService campaignProductionCampaignAgentConversationHistoryService(
            @Qualifier(ProductionSecurityConfiguration.PROVIDER_BEAN_NAME)
            ObjectProvider<TrustedContextProvider> trustedContextProviders,
            @Qualifier(STORE_BEAN_NAME) ObjectProvider<DurableCampaignConversationStore> stores,
            @Qualifier(ENVIRONMENT_BEAN_NAME) ObjectProvider<CampaignAgentEnvironment> environments) {
        TrustedContextProvider trustedContextProvider = required(
                trustedContextProviders, TrustedContextProvider.class,
                ProductionCampaignAgentErrorCodes.PRODUCTION_SECURITY_REQUIRED,
                "The production Campaign Agent host requires the production trusted-context provider.");
        CampaignAgentEnvironment environment = required(
                environments, CampaignAgentEnvironment.class,
                ProductionCampaignAgentErrorCodes.CONFIGURATION_INVALID,
                "The production Campaign Agent host requires exactly one Campaign Agent environment.");
        DurableCampaignConversationStore store = required(
                stores, DurableCampaignConversationStore.class,
                ProductionCampaignAgentErrorCodes.DURABLE_STORE_REQUIRED,
                "The production Campaign Agent host requires exactly one durable conversation store.");
        if (!(store instanceof CampaignConversationHistoryStore historyStore)) {
            throw new ProductionCampaignAgentConfigurationException(
                    ProductionCampaignAgentErrorCodes.HISTORY_STORE_REQUIRED,
                    "The durable conversation store cannot serve conversation history.");
        }
        return new CampaignConversationHistoryService(trustedContextProvider, historyStore, environment);
    }

    /** The accepted read-only conversation-history HTTP adapter, registered only in the production host. */
    @Bean
    public CampaignAgentConversationHistoryController campaignProductionCampaignAgentConversationHistoryController(
            @Qualifier(HISTORY_SERVICE_BEAN_NAME) CampaignAgentConversationHistoryService service) {
        return new CampaignAgentConversationHistoryController(service);
    }

    /**
     * Resolves exactly one required dependency of a port, or aborts the start with one bounded code.
     *
     * <p>More than one candidate is an ambiguous wiring and reports the same bounded code as a missing one, so
     * the host can never be left guessing which of two differently configured boundaries is the real one.
     */
    private static <T> T required(
            ObjectProvider<T> provider, Class<T> portType, String code, String message) {
        List<T> supplied = provider.orderedStream().limit(2).toList();
        if (supplied.size() != 1) {
            throw new ProductionCampaignAgentConfigurationException(code, message);
        }
        return supplied.getFirst();
    }

    /**
     * The real PostgreSQL runtime of the durable production Campaign conversation store.
     *
     * <p>It is a nested member class that exists solely to hold the triple opt-in condition
     * ({@code campaign.production.enabled=true}, {@code campaign.production.campaign-agent.enabled=true},
     * and {@code campaign.production.campaign-agent.store.url} is set). The pool, Flyway runtime and migration
     * initializer are now provided by {@code CampaignDatasourceConfiguration} over the shared business datasource.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "campaign.production.campaign-agent", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "campaign.production.campaign-agent.store", name = "url")
    static class ProductionConversationStoreRuntime {
        // The pool, Flyway and migration are now provided by CampaignDatasourceConfiguration.
        // The conversation store bean is published by the outer class over the shared SqlSessionFactory.
    }
}
