package com.company.campaign.production.runtime.testsupport;

import com.company.campaign.action.ActionAuditRecord;
import com.company.campaign.action.ActionAuditSink;
import com.company.campaign.action.ActionStoreDurability;
import com.company.campaign.action.DurableActionAuditSink;
import com.company.campaign.action.DurableActionStore;
import com.company.campaign.action.InMemoryActionStore;
import com.company.campaign.action.MessageProvider;
import com.company.campaign.action.ProviderCapability;
import com.company.campaign.action.ProviderRequest;
import com.company.campaign.action.ProviderResult;
import com.company.campaign.action.RewardProvider;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.RuntimeWorkDeliveryPort;
import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.production.audit.ProductionAuditConfiguration;
import com.company.campaign.production.provider.ProductionProviderKind;
import com.company.campaign.production.provider.ProductionProviderMessageAdapter;
import com.company.campaign.production.provider.ProductionProviderProperties;
import com.company.campaign.production.provider.ProductionProviderRewardAdapter;
import com.company.campaign.production.runtime.ProductionProviderRuntimeConfiguration;
import com.company.campaign.runtime.DurableRuntimeStore;
import com.company.campaign.runtime.DurableRuntimeAuditSink;
import com.company.campaign.runtime.InMemoryRuntimeStore;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeAuditSink;
import com.company.campaign.runtime.RuntimeStoreDurability;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * T35C2 deterministic test doubles for the production provider runtime host.
 *
 * <p>Nothing here is production evidence. The durable-store doubles delegate to the repository's in-memory
 * registries and merely <em>declare</em> the accepted PostgreSQL durability claim so wiring can be exercised;
 * they are never an implemented PostgreSQL adapter and never a live-database proof. The Provider adapters are
 * built from a validated loopback configuration and never contact a network during wiring.
 */
public final class ProviderRuntimeTestSupport {

    /** The two properties that opt into the production provider runtime host. */
    public static final String[] ENABLED = {
            "campaign.production.enabled=true",
            "campaign.production.provider-runtime.enabled=true"
    };

    /** The two properties that additionally opt into background scheduling. */
    public static final String[] SCHEDULER_ENABLED = {
            "campaign.production.enabled=true",
            "campaign.production.provider-runtime.enabled=true",
            "campaign.production.provider-runtime.scheduler-enabled=true"
    };

    private ProviderRuntimeTestSupport() {
    }

    /** A runner over the production runtime configuration only; inert unless a test opts in. */
    public static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner()
                .withUserConfiguration(ProductionProviderRuntimeConfiguration.class);
    }

    /** A runner with the two opt-in properties set. */
    public static ApplicationContextRunner optedIn(String[] properties) {
        return enabled().withPropertyValues(properties);
    }

    /**
     * A production provider runtime graph over deterministic doubles and loopback Provider adapters.
     *
     * <p>Any of {@code message}, {@code reward} or {@code tenants} may be {@code null}, in which case that
     * collaborator is deliberately not registered so a missing-collaborator failure can be exercised.
     */
    public static ApplicationContextRunner graph(
            String[] properties,
            MessageProvider message,
            RewardProvider reward,
            com.company.campaign.production.runtime.ProviderRuntimeTenantSource tenants) {
        ApplicationContextRunner runner = optedIn(properties)
                .withBean("scriptedDurableRuntimeStore", DurableRuntimeStore.class,
                        () -> new ScriptedDurableRuntimeStore(RuntimeStoreDurability.POSTGRESQL))
                .withBean("scriptedDurableActionStore", DurableActionStore.class,
                        () -> new ScriptedDurableActionStore(
                                new InMemoryRuntimeStore(), ActionStoreDurability.POSTGRESQL))
                .withBean(ProductionAuditConfiguration.RUNTIME_SINK_BEAN_NAME, RuntimeAuditSink.class,
                        ScriptedRuntimeAuditSink::new)
                .withBean(ProductionAuditConfiguration.ACTION_SINK_BEAN_NAME, ActionAuditSink.class,
                        ScriptedActionAuditSink::new)
                .withBean(RuntimeWorkDeliveryPort.class, ScriptedWorkDeliveryPort::new)
                .withBean(com.company.campaign.runtime.RuntimeCampaignStatePort.class,
                        () -> (tenantId, campaignId) -> Optional.empty())
                .withBean(com.company.campaign.runtime.RuntimeFactPort.class, () -> request -> null)
                .withBean(com.company.campaign.runtime.AudienceMaterializationPort.class, () -> request -> null)
                .withBean(com.company.campaign.action.ActionRecipientResolverPort.class,
                        () -> (tenantId, participantId, nodeLogicalId, actionKind, channelCode) ->
                                Optional.empty())
                .withBean(com.company.campaign.production.runtime.ProviderRuntimeObservabilityPort.class,
                        () -> com.company.campaign.production.runtime.ProviderRuntimeBacklog::empty);
        if (message != null) {
            runner = runner.withBean(MessageProvider.class, () -> message);
        }
        if (reward != null) {
            runner = runner.withBean(RewardProvider.class, () -> reward);
        }
        if (tenants != null) {
            runner = runner.withBean(
                    com.company.campaign.production.runtime.ProviderRuntimeTenantSource.class, () -> tenants);
        }
        return runner;
    }

    /** A fully wired graph over the deterministic doubles and loopback Provider adapters. */
    public static ApplicationContextRunner wired(String[] properties) {
        return graph(properties, scriptedMessageProvider(), scriptedRewardProvider(), oneTenant());
    }

    /** The one trusted tenant source double. */
    public static com.company.campaign.production.runtime.ProviderRuntimeTenantSource oneTenant() {
        return () -> List.of("tenant-1");
    }

    /** One production Message adapter over a validated loopback configuration; it never contacts a network. */
    public static MessageProvider scriptedMessageProvider() {
        return ProductionProviderMessageAdapter.create(
                loopbackProperties(ProductionProviderKind.MESSAGE), HttpClient.newHttpClient());
    }

    /** One production Reward adapter over a validated loopback configuration; it never contacts a network. */
    public static RewardProvider scriptedRewardProvider() {
        return ProductionProviderRewardAdapter.create(
                loopbackProperties(ProductionProviderKind.REWARD), HttpClient.newHttpClient());
    }

    private static ProductionProviderProperties loopbackProperties(ProductionProviderKind kind) {
        ProductionProviderProperties properties = new ProductionProviderProperties();
        properties.setQueryMaxAttempts(1);
        properties.setInitialBackoff(Duration.ofMillis(1));
        properties.setMaxBackoff(Duration.ofMillis(2));
        ProductionProviderProperties.Adapter adapter = properties.adapter(kind);
        adapter.setEnabled(true);
        adapter.setRequireHttps(false);
        adapter.setEndpoint("http://127.0.0.1:9/");
        adapter.setAllowedHosts(List.of("127.0.0.1"));
        adapter.setProviderId("scripted-provider");
        adapter.setOwnerTenantId("tenant-1");
        adapter.setCredential("scripted-credential");
        adapter.setMaxResponseBytes(65_536);
        adapter.setAssertBusinessKeyIdempotency(true);
        adapter.setAssertReliableQueryByKey(true);
        properties.validate();
        return properties;
    }

    /** A durable-store double over the in-memory registry that declares one explicit durability claim. */
    public static final class ScriptedDurableRuntimeStore extends InMemoryRuntimeStore
            implements DurableRuntimeStore {

        private final RuntimeStoreDurability claim;

        public ScriptedDurableRuntimeStore(RuntimeStoreDurability claim) {
            this.claim = claim;
        }

        @Override
        public RuntimeStoreDurability durability() {
            return claim;
        }
    }

    /** A durable-store double over the in-memory registry that declares one explicit durability claim. */
    public static final class ScriptedDurableActionStore extends InMemoryActionStore
            implements DurableActionStore {

        private final ActionStoreDurability claim;

        public ScriptedDurableActionStore(
                com.company.campaign.runtime.RuntimeStore runtimeStore, ActionStoreDurability claim) {
            super(runtimeStore);
            this.claim = claim;
        }

        @Override
        public ActionStoreDurability durability() {
            return claim;
        }
    }

    /** A recording runtime audit sink that is not the repository's in-memory implementation. */
    public static final class ScriptedRuntimeAuditSink implements DurableRuntimeAuditSink {

        @Override
        public void record(RuntimeAuditRecord record) {
            // Deterministic no-op double: it asserts nothing and stores nothing.
        }
    }

    /** A recording action audit sink that is not the repository's in-memory implementation. */
    public static final class ScriptedActionAuditSink implements DurableActionAuditSink {

        @Override
        public void recordAll(List<ActionAuditRecord> records) {
            // Deterministic no-op double.
        }

        @Override
        public void rollbackAll(List<ActionAuditRecord> records) {
            // Deterministic no-op double.
        }
    }

    /** A work-delivery port double that holds no committed T21 work. */
    public static final class ScriptedWorkDeliveryPort implements RuntimeWorkDeliveryPort {

        @Override
        public List<ScheduleRegistrationOutboxEvent> findRegistrationWork() {
            return List.of();
        }

        @Override
        public Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId) {
            return Optional.empty();
        }

        @Override
        public List<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId) {
            return List.of();
        }

        @Override
        public Optional<ScheduledOccurrenceRecord> findOccurrenceWork(String campaignId, String executionKey) {
            return Optional.empty();
        }

        @Override
        public Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId) {
            return Optional.empty();
        }

        @Override
        public Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId) {
            return Optional.empty();
        }
    }

    /** A Message provider double that is deliberately not the accepted production adapter. */
    public static final class NonProductionMessageProvider implements MessageProvider {

        @Override
        public ProviderCapability capability(String tenantId) {
            return null;
        }

        @Override
        public ProviderResult send(ProviderRequest request, String idempotencyKey) {
            return null;
        }

        @Override
        public ProviderResult queryByKey(String idempotencyKey) {
            return null;
        }
    }
}
