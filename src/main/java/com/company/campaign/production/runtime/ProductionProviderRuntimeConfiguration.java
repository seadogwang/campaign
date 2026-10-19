package com.company.campaign.production.runtime;

import com.company.campaign.action.ActionApplicationService;
import com.company.campaign.action.ActionAuditSink;
import com.company.campaign.action.ActionPolicies;
import com.company.campaign.action.ActionRecipientResolverPort;
import com.company.campaign.action.ActionRequestResolver;
import com.company.campaign.action.ActionWorker;
import com.company.campaign.action.DurableActionAuditSink;
import com.company.campaign.action.DurableActionStore;
import com.company.campaign.action.HostActionRuntimePort;
import com.company.campaign.action.MessageActionService;
import com.company.campaign.action.MessageProvider;
import com.company.campaign.action.RewardActionService;
import com.company.campaign.action.RewardProvider;
import com.company.campaign.fullpublish.RuntimeWorkDeliveryPort;
import com.company.campaign.production.audit.ProductionAuditConfiguration;
import com.company.campaign.production.provider.ProductionProviderMessageAdapter;
import com.company.campaign.production.provider.ProductionProviderRewardAdapter;
import com.company.campaign.runtime.AudienceMaterializationPort;
import com.company.campaign.runtime.DurableRuntimeAuditSink;
import com.company.campaign.runtime.DurableRuntimeStore;
import com.company.campaign.runtime.RuntimeAuditSink;
import com.company.campaign.runtime.RuntimeCampaignStatePort;
import com.company.campaign.runtime.RuntimeFactPort;
import com.company.campaign.runtime.RuntimeIngressService;
import com.company.campaign.runtime.RuntimeMaterializationService;
import com.company.campaign.runtime.RuntimeRetryPolicy;
import com.company.campaign.runtime.RuntimeWorker;
import com.company.campaign.runtime.T21RuntimeWorkSource;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

/**
 * The focused opt-in configuration of the default-off production provider runtime host.
 *
 * <p>It is inert unless the host sets {@code campaign.production.enabled=true} <em>and</em>
 * {@code campaign.production.provider-runtime.enabled=true}. When it is off — the default — the application
 * creates no worker, no service bean, no executor and no thread.
 *
 * <p>When it is on it wires, fail-closed and in one place, exactly one coherent graph over host-supplied
 * collaborators: one {@link T21RuntimeWorkSource}, one {@link RuntimeIngressService}, one
 * {@link RuntimeMaterializationService}, one {@link RuntimeWorker}, one {@link HostActionRuntimePort}, one
 * {@link ActionRequestResolver}, one {@link ActionApplicationService}, the Message and Reward facades, one
 * {@link ActionWorker} and the bounded cycle service — all over the exact same durable Runtime and Action stores,
 * the same clock and the same frozen source/definition collaborators.
 *
 * <p>Every required collaborator is resolved by explicit type and exactly-one rule. Missing, ambiguous,
 * development/simulated or incompatible collaborators abort startup with one bounded, redacted code rather than
 * silently substituting an in-memory store, a simulated Provider or a second graph. The durable Runtime and
 * Action stores must expose the accepted {@code POSTGRESQL} durability claim: a bare Java type is never a
 * durability proof, and this repository ships no PostgreSQL implementation of those large transactional ports, so
 * the host must supply one.
 *
 * <p>The accepted production durable audit adapters are required, so no in-memory audit sink and no second
 * transaction path can be used. Every produced bean has an explicit name and qualifier.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductionProviderRuntimeProperties.class)
@ConditionalOnProperty(prefix = "campaign.production", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "campaign.production.provider-runtime", name = "enabled", havingValue = "true")
public class ProductionProviderRuntimeConfiguration {

    /** The one coherent production provider runtime graph. */
    public static final String COMPONENTS_BEAN_NAME = "campaignProductionProviderRuntimeComponents";

    /** The one bounded cycle service. */
    public static final String CYCLE_BEAN_NAME = "campaignProductionProviderRuntimeCycle";

    /** The one bounded observability state and snapshot provider. */
    public static final String OBSERVABILITY_BEAN_NAME = "campaignProductionProviderRuntimeObservability";

    /** The one bounded background scheduler, present only when scheduling is separately opted in. */
    public static final String SCHEDULER_BEAN_NAME = "campaignProductionProviderRuntimeScheduler";

    /** The one bounded, redacted topology evidence record. */
    public static final String TOPOLOGY_EVIDENCE_BEAN_NAME = "campaignProductionProviderRuntimeTopologyEvidence";

    /**
     * The one coherent production provider runtime graph.
     *
     * <p>Every required collaborator is resolved here, once, so the published beans are exactly the instances of
     * one graph. Any missing, ambiguous, simulated or non-durable collaborator aborts the start with one bounded
     * code.
     */
    @Bean(name = COMPONENTS_BEAN_NAME)
    @Qualifier(COMPONENTS_BEAN_NAME)
    public ProviderRuntimeComponents campaignProductionProviderRuntimeComponents(
            ProductionProviderRuntimeProperties properties,
            ListableBeanFactory beanFactory,
            ObjectProvider<RuntimeWorkDeliveryPort> workDeliveryPorts,
            ObjectProvider<RuntimeCampaignStatePort> campaignStatePorts,
            ObjectProvider<RuntimeFactPort> factPorts,
            ObjectProvider<AudienceMaterializationPort> audiencePorts,
            ObjectProvider<ActionRecipientResolverPort> recipientResolvers,
            ObjectProvider<MessageProvider> messageProviders,
            ObjectProvider<RewardProvider> rewardProviders,
            ObjectProvider<ProviderRuntimeTenantSource> tenantSources,
            ObjectProvider<ProviderRuntimeObservabilityPort> observabilityPorts) {
        properties.validate();
        // The one clock the whole graph shares. It is built here rather than published as a bean, so enabling
        // the production runtime can never make an unrelated bare Clock injection ambiguous.
        Clock clock = Clock.systemUTC();

        DurableRuntimeStore runtimeStore = requireDurableRuntimeStore(beanFactory);
        DurableActionStore actionStore = requireDurableActionStore(beanFactory);
        requireProductionRuntimeAudit(beanFactory);
        requireProductionActionAudit(beanFactory);

        RuntimeWorkDeliveryPort workDeliveryPort = requiredOne(
                workDeliveryPorts, RuntimeWorkDeliveryPort.class,
                ProductionProviderRuntimeErrorCodes.WORK_DELIVERY_PORT_REQUIRED,
                "The production provider runtime requires exactly one Runtime work-delivery port.");
        RuntimeCampaignStatePort campaignStates = requiredOne(
                campaignStatePorts, RuntimeCampaignStatePort.class,
                ProductionProviderRuntimeErrorCodes.CAMPAIGN_STATE_PORT_REQUIRED,
                "The production provider runtime requires exactly one Campaign state port.");
        RuntimeFactPort facts = requiredOne(
                factPorts, RuntimeFactPort.class,
                ProductionProviderRuntimeErrorCodes.FACT_PORT_REQUIRED,
                "The production provider runtime requires exactly one Runtime fact port.");
        AudienceMaterializationPort audience = requiredOne(
                audiencePorts, AudienceMaterializationPort.class,
                ProductionProviderRuntimeErrorCodes.AUDIENCE_PORT_REQUIRED,
                "The production provider runtime requires exactly one audience materialization port.");
        ActionRecipientResolverPort recipients = requiredOne(
                recipientResolvers, ActionRecipientResolverPort.class,
                ProductionProviderRuntimeErrorCodes.RECIPIENT_RESOLVER_REQUIRED,
                "The production provider runtime requires exactly one action recipient resolver port.");
        MessageProvider messageProvider = requireProductionMessageProvider(messageProviders);
        RewardProvider rewardProvider = requireProductionRewardProvider(rewardProviders);
        ProviderRuntimeTenantSource tenantSource = requiredOne(
                tenantSources, ProviderRuntimeTenantSource.class,
                ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_REQUIRED,
                "The production provider runtime requires exactly one bounded tenant source.");
        // The tenant list is validated before any worker exists, so a misbehaving source fails startup closed.
        ProviderRuntimeTenantSource.bounded(tenantSource, properties.getMaxTenants());
        ProviderRuntimeObservabilityPort observabilityPort = requiredOne(
                observabilityPorts, ProviderRuntimeObservabilityPort.class,
                ProductionProviderRuntimeErrorCodes.OBSERVABILITY_PORT_REQUIRED,
                "The production provider runtime requires exactly one host observability port.");

        T21RuntimeWorkSource workSource = new T21RuntimeWorkSource(workDeliveryPort);
        RuntimeIngressService ingress = new RuntimeIngressService(workSource, workSource, runtimeStore, clock);
        RuntimeMaterializationService materialization = new RuntimeMaterializationService(
                workSource, audience, runtimeStore, clock, properties.getMaterializationFreshness());
        RuntimeWorker runtimeWorker = new RuntimeWorker(
                workSource, campaignStates, facts, runtimeStore, clock,
                RuntimeRetryPolicy.of(properties.getRuntimeAttempts(), properties.getRuntimeBackoff()),
                properties.getLeaseDuration(), properties.getFactTimeoutMillis(),
                properties.getClaimScanLimit());
        HostActionRuntimePort actionRuntime =
                new HostActionRuntimePort(runtimeStore, workSource, campaignStates);
        ActionRequestResolver resolver = new ActionRequestResolver(actionRuntime, recipients);
        ActionPolicies policies = ActionPolicies.of(
                properties.getSubmissionAttempts(), properties.getSubmissionBackoff(),
                properties.getVerificationAttempts(), properties.getVerificationBackoff());
        ActionApplicationService actionApplicationService = new ActionApplicationService(
                actionRuntime, actionStore, resolver, policies, messageProvider, rewardProvider, clock,
                properties.getLeaseDuration(), properties.getClaimScanLimit());
        MessageActionService messageActionService = new MessageActionService(actionApplicationService);
        RewardActionService rewardActionService = new RewardActionService(actionApplicationService);
        ActionWorker actionWorker =
                new ActionWorker(actionRuntime, actionApplicationService, clock, properties.getIngestLimit());
        ProviderRuntimeCycleService cycleService = new ProviderRuntimeCycleService(
                (tenantId, worker) -> runtimeWorker.runOnce(tenantId, worker).isPresent(),
                (tenantId, worker) -> actionWorker.runOnce(tenantId, worker).isPresent(),
                tenantSource, properties.getMaxTenants(),
                properties.getMaxRuntimeItemsPerCycle(), properties.getMaxActionItemsPerCycle());
        ProviderRuntimeObservability observability =
                new ProviderRuntimeObservability(observabilityPort, properties.configurationHash());
        ProviderRuntimeTopologyEvidence topologyEvidence = new ProviderRuntimeTopologyEvidence(
                ProviderRuntimeTopologyEvidence.FORMAT_VERSION,
                properties.workerId(),
                true,
                true,
                properties.configurationHash());
        return new ProviderRuntimeComponents(
                runtimeStore, actionStore, clock, workSource, ingress, materialization, runtimeWorker,
                actionRuntime, resolver, actionApplicationService, messageActionService, rewardActionService,
                actionWorker, cycleService, observability, topologyEvidence);
    }

    /** The one accepted T21 work source and definition-lookup adapter. */
    @Bean
    public T21RuntimeWorkSource campaignProductionRuntimeWorkSource(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.workSource();
    }

    /** The one Runtime ingress service over the durable Runtime store. */
    @Bean
    public RuntimeIngressService campaignProductionRuntimeIngressService(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.ingress();
    }

    /** The one Runtime materialization service over the durable Runtime store and audience port. */
    @Bean
    public RuntimeMaterializationService campaignProductionRuntimeMaterializationService(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.materialization();
    }

    /** The one leased Runtime worker over the durable Runtime store. */
    @Bean
    public RuntimeWorker campaignProductionRuntimeWorker(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.runtimeWorker();
    }

    /** The one host Runtime port the external-action layer reads and finishes Runtime nodes through. */
    @Bean
    public HostActionRuntimePort campaignProductionHostActionRuntimePort(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.actionRuntime();
    }

    /** The one action request resolver over the host Runtime port and recipient resolver. */
    @Bean
    public ActionRequestResolver campaignProductionActionRequestResolver(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.requestResolver();
    }

    /** The one Action application service over the durable Action store and the production Providers. */
    @Bean
    public ActionApplicationService campaignProductionActionApplicationService(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.actionApplicationService();
    }

    /** The one Message facade over the single Action application service. */
    @Bean
    public MessageActionService campaignProductionMessageActionService(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.messageActionService();
    }

    /** The one Reward facade over the single Action application service. */
    @Bean
    public RewardActionService campaignProductionRewardActionService(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.rewardActionService();
    }

    /** The one Action worker over the durable Action store. */
    @Bean
    public ActionWorker campaignProductionActionWorker(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.actionWorker();
    }

    /** The one bounded cycle service over the Runtime worker and the Action worker. */
    @Bean(name = CYCLE_BEAN_NAME)
    @Qualifier(CYCLE_BEAN_NAME)
    public ProviderRuntimeCycleService campaignProductionProviderRuntimeCycle(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.cycleService();
    }

    /** The one bounded observability state and snapshot provider. */
    @Bean(name = OBSERVABILITY_BEAN_NAME)
    @Qualifier(OBSERVABILITY_BEAN_NAME)
    public ProviderRuntimeObservability campaignProductionProviderRuntimeObservability(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.observability();
    }

    /** The one bounded, redacted topology evidence record a host projects into the readiness catalog. */
    @Bean(name = TOPOLOGY_EVIDENCE_BEAN_NAME)
    @Qualifier(TOPOLOGY_EVIDENCE_BEAN_NAME)
    public ProviderRuntimeTopologyEvidence campaignProductionProviderRuntimeTopologyEvidence(
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components) {
        return components.topologyEvidence();
    }

    /**
     * The one bounded background scheduler, present only when scheduling is separately opted in.
     *
     * <p>A host-supplied {@link ScheduledExecutorService} has documented precedence; more than one is an ambiguous
     * wiring that aborts startup. With none supplied, the host owns exactly one single-thread scheduled executor.
     * The bean is closed gracefully on shutdown.
     */
    @Bean(name = SCHEDULER_BEAN_NAME, destroyMethod = "close")
    @Qualifier(SCHEDULER_BEAN_NAME)
    @ConditionalOnProperty(
            prefix = "campaign.production.provider-runtime", name = "scheduler-enabled", havingValue = "true")
    public ProviderRuntimeScheduler campaignProductionProviderRuntimeScheduler(
            ProductionProviderRuntimeProperties properties,
            @Qualifier(COMPONENTS_BEAN_NAME) ProviderRuntimeComponents components,
            ObjectProvider<ScheduledExecutorService> hostExecutors) {
        properties.validate();
        List<ScheduledExecutorService> supplied = hostExecutors.orderedStream().limit(2).toList();
        if (supplied.size() > 1) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.SCHEDULER_INVALID,
                    "The production provider runtime requires at most one host scheduled executor.");
        }
        boolean ownsExecutor = supplied.isEmpty();
        ScheduledExecutorService executor =
                ownsExecutor ? ProviderRuntimeScheduler.ownedExecutor() : supplied.getFirst();
        return new ProviderRuntimeScheduler(
                components.cycleService(), components.observability(), components.clock(),
                properties.getSchedulerCadence(), properties.workerId(), executor, ownsExecutor);
    }

    // ------------------------------------------------------------------ exact-one resolution

    /**
     * Resolves exactly one required collaborator of a port, or aborts the start with one bounded code.
     *
     * <p>More than one candidate is an ambiguous wiring and reports the same bounded code as a missing one, so
     * the host can never be left guessing which of two differently configured boundaries is the real one.
     */
    private static <T> T requiredOne(ObjectProvider<T> provider, Class<T> portType, String code, String message) {
        List<T> supplied = provider.orderedStream().limit(2).toList();
        if (supplied.size() != 1) {
            throw new ProductionProviderRuntimeConfigurationException(code, message);
        }
        return supplied.getFirst();
    }

    /**
     * The one host-supplied durable Runtime store, or a fail-closed abort.
     *
     * <p>An in-memory registry is refused because it is not a {@link DurableRuntimeStore} at all, and a store that
     * implements the type still passes only when its claim is the accepted PostgreSQL claim. This repository ships
     * no PostgreSQL implementation of the large transactional port, so a missing store is a fail-closed error and
     * never a silent in-memory fallback.
     */
    private static DurableRuntimeStore requireDurableRuntimeStore(ListableBeanFactory beanFactory) {
        List<String> names = Arrays.asList(beanFactory.getBeanNamesForType(DurableRuntimeStore.class, true, true));
        if (names.size() > 1) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_AMBIGUOUS,
                    "The production provider runtime requires at most one durable Runtime store.");
        }
        if (names.isEmpty()) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED,
                    "The production provider runtime requires exactly one durable Runtime store that exposes the "
                            + "accepted PostgreSQL durability claim.");
        }
        DurableRuntimeStore store = beanFactory.getBean(names.getFirst(), DurableRuntimeStore.class);
        if (store.durability() == null || !store.durability().accepted()) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED,
                    "The production provider runtime requires a Runtime store that exposes the accepted "
                            + "PostgreSQL durability claim.");
        }
        return store;
    }

    /** The one host-supplied durable Action store, with the same fail-closed claim rule. */
    private static DurableActionStore requireDurableActionStore(ListableBeanFactory beanFactory) {
        List<String> names = Arrays.asList(beanFactory.getBeanNamesForType(DurableActionStore.class, true, true));
        if (names.size() > 1) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_ACTION_STORE_AMBIGUOUS,
                    "The production provider runtime requires at most one durable Action store.");
        }
        if (names.isEmpty()) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_ACTION_STORE_REQUIRED,
                    "The production provider runtime requires exactly one durable Action store that exposes the "
                            + "accepted PostgreSQL durability claim.");
        }
        DurableActionStore store = beanFactory.getBean(names.getFirst(), DurableActionStore.class);
        if (store.durability() == null || !store.durability().accepted()) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_ACTION_STORE_REQUIRED,
                    "The production provider runtime requires an Action store that exposes the accepted "
                            + "PostgreSQL durability claim.");
        }
        return store;
    }

    /**
     * Requires the accepted production durable Runtime audit adapter.
     *
     * <p>The adapter is resolved by the exact bean name the durable audit module publishes, so no in-memory audit
     * sink and no second transaction path can stand in for it. An absent or in-memory adapter aborts the start.
     */
    private static RuntimeAuditSink requireProductionRuntimeAudit(ListableBeanFactory beanFactory) {
        Object bean = productionAuditBean(beanFactory, ProductionAuditConfiguration.RUNTIME_SINK_BEAN_NAME);
        if (!(bean instanceof DurableRuntimeAuditSink sink)) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_AUDIT_REQUIRED,
                    "The production provider runtime requires the accepted production durable Runtime audit "
                            + "adapter.");
        }
        return sink;
    }

    /** The accepted production durable Action audit adapter, with the same fail-closed rule. */
    private static ActionAuditSink requireProductionActionAudit(ListableBeanFactory beanFactory) {
        Object bean = productionAuditBean(beanFactory, ProductionAuditConfiguration.ACTION_SINK_BEAN_NAME);
        if (!(bean instanceof DurableActionAuditSink sink)) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_AUDIT_REQUIRED,
                    "The production provider runtime requires the accepted production durable Action audit "
                            + "adapter.");
        }
        return sink;
    }

    private static Object productionAuditBean(ListableBeanFactory beanFactory, String beanName) {
        if (!beanFactory.containsBean(beanName)) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.DURABLE_AUDIT_REQUIRED,
                    "The production provider runtime requires the accepted production durable audit adapters.");
        }
        return beanFactory.getBean(beanName);
    }

    /**
     * The one production Message provider adapter, or a fail-closed abort.
     *
     * <p>Any Message provider that is not the accepted production adapter — a simulated fixture, a development
     * double or any other non-production implementation — is refused with its own bounded code, so a fixture
     * cannot become the production sender merely by being the only Message provider bean. The check names no
     * simulated class: production runtime code imports no development or simulated provider type at all.
     */
    private static MessageProvider requireProductionMessageProvider(ObjectProvider<MessageProvider> providers) {
        MessageProvider provider = requiredOne(
                providers, MessageProvider.class,
                ProductionProviderRuntimeErrorCodes.MESSAGE_PROVIDER_REQUIRED,
                "The production provider runtime requires exactly one production Message provider.");
        if (!(provider instanceof ProductionProviderMessageAdapter)) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.SIMULATED_PROVIDER_REJECTED,
                    "The production provider runtime refuses a simulated or non-production Message provider.");
        }
        return provider;
    }

    /** The one production Reward provider adapter, with the same simulated/non-production refusal. */
    private static RewardProvider requireProductionRewardProvider(ObjectProvider<RewardProvider> providers) {
        RewardProvider provider = requiredOne(
                providers, RewardProvider.class,
                ProductionProviderRuntimeErrorCodes.REWARD_PROVIDER_REQUIRED,
                "The production provider runtime requires exactly one production Reward provider.");
        if (!(provider instanceof ProductionProviderRewardAdapter)) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.SIMULATED_PROVIDER_REJECTED,
                    "The production provider runtime refuses a simulated or non-production Reward provider.");
        }
        return provider;
    }
}
