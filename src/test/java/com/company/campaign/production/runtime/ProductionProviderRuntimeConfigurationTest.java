package com.company.campaign.production.runtime;

import com.company.campaign.action.ActionStoreDurability;
import com.company.campaign.action.ActionWorker;
import com.company.campaign.action.DurableActionStore;
import com.company.campaign.action.MessageProvider;
import com.company.campaign.production.audit.ProductionAuditConfiguration;
import com.company.campaign.production.runtime.testsupport.ProviderRuntimeTestSupport;
import com.company.campaign.production.runtime.testsupport.ProviderRuntimeTestSupport.ScriptedDurableActionStore;
import com.company.campaign.production.runtime.testsupport.ProviderRuntimeTestSupport.ScriptedDurableRuntimeStore;
import com.company.campaign.production.testsupport.ProductionFailureCodes;
import com.company.campaign.runtime.DurableRuntimeStore;
import com.company.campaign.runtime.InMemoryRuntimeStore;
import com.company.campaign.runtime.RuntimeIngressService;
import com.company.campaign.runtime.RuntimeStoreDurability;
import com.company.campaign.runtime.RuntimeWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T35C2: the default-off production provider runtime host.
 *
 * <p>Default inertness, one coherent wired graph, and every missing, ambiguous, simulated or non-durable
 * collaborator is proved to fail closed with one bounded code.
 */
class ProductionProviderRuntimeConfigurationTest {

    @Test
    void theDefaultApplicationCreatesNoRuntimeGraphOrThread() {
        ProviderRuntimeTestSupport.enabled().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(ProviderRuntimeComponents.class)).isEmpty();
            assertThat(context.getBeanNamesForType(RuntimeWorker.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ActionWorker.class)).isEmpty();
            assertThat(context.getBeanNamesForType(RuntimeIngressService.class)).isEmpty();
            assertThat(context.getBeanNamesForType(ProviderRuntimeScheduler.class)).isEmpty();
        });
    }

    @Test
    void theHostStaysInertWithoutTheProductionBoundary() {
        ProviderRuntimeTestSupport
                .optedIn(new String[] {"campaign.production.provider-runtime.enabled=true"})
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(ProviderRuntimeComponents.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(ProviderRuntimeScheduler.class)).isEmpty();
                });
    }

    @Test
    void aFullyWiredHostCreatesOneCoherentGraph() {
        ProviderRuntimeTestSupport.wired(ProviderRuntimeTestSupport.ENABLED).run(context -> {
            assertThat(context).hasNotFailed();

            ProviderRuntimeComponents components = context.getBean(ProviderRuntimeComponents.class);
            assertThat(components.runtimeWorker()).isSameAs(context.getBean(RuntimeWorker.class));
            assertThat(components.actionWorker()).isSameAs(context.getBean(ActionWorker.class));
            assertThat(components.cycleService()).isSameAs(context.getBean(ProviderRuntimeCycleService.class));
            assertThat(components.observability()).isSameAs(context.getBean(ProviderRuntimeObservability.class));
            assertThat(components.ingress()).isSameAs(context.getBean(RuntimeIngressService.class));
            assertThat(components.runtimeStore())
                    .isSameAs(context.getBean(DurableRuntimeStore.class));
            assertThat(components.actionStore())
                    .isSameAs(context.getBean(DurableActionStore.class));

            // The accepted T22/T23 services are used, not a fork, and the two facades share the one service.
            assertThat(components.actionApplicationService())
                    .isSameAs(context.getBean(
                            com.company.campaign.action.ActionApplicationService.class));
            assertThat(components.messageActionService().applicationService())
                    .isSameAs(components.actionApplicationService());
            assertThat(components.rewardActionService().applicationService())
                    .isSameAs(components.actionApplicationService());

            // Absolutely no background scheduling appears unless it is separately opted in.
            assertThat(context.getBeanNamesForType(ProviderRuntimeScheduler.class)).isEmpty();
        });
    }

    @Test
    void anInMemoryAuditSinkIsRefusedEvenUnderTheProductionBeanName() {
        ApplicationContextRunner runner = withRuntimeStore(RuntimeStoreDurability.POSTGRESQL)
                .withBean("scriptedDurableActionStore", DurableActionStore.class,
                        () -> new ScriptedDurableActionStore(
                                new InMemoryRuntimeStore(), ActionStoreDurability.POSTGRESQL))
                .withBean(ProductionAuditConfiguration.RUNTIME_SINK_BEAN_NAME,
                        com.company.campaign.runtime.RuntimeAuditSink.class,
                        com.company.campaign.runtime.InMemoryRuntimeAuditSink::new)
                .withBean(ProductionAuditConfiguration.ACTION_SINK_BEAN_NAME,
                        com.company.campaign.action.ActionAuditSink.class,
                        ProviderRuntimeTestSupport.ScriptedActionAuditSink::new);
        assertThat(failureCodeOf(runner))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_AUDIT_REQUIRED);
    }

    @Test
    void theSchedulerIsCreatedAndAutoStartedOnlyWhenSeparatelyOptedIn() {
        ProviderRuntimeTestSupport.wired(ProviderRuntimeTestSupport.SCHEDULER_ENABLED).run(context -> {
            assertThat(context).hasNotFailed();
            ProviderRuntimeScheduler scheduler = context.getBean(ProviderRuntimeScheduler.class);
            assertThat(scheduler.isAutoStartup()).isTrue();
        });
    }

    @Test
    void aMissingDurableRuntimeStoreFailsClosed() {
        ProviderRuntimeTestSupport.optedIn(ProviderRuntimeTestSupport.ENABLED).run(context -> {
            assertThat(context).hasFailed();
            assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                    .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED);
        });
    }

    @Test
    void anInMemoryOrNoClaimDurableRuntimeStoreIsRefused() {
        assertThat(failureCodeOf(withRuntimeStore(RuntimeStoreDurability.IN_MEMORY)))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED);
        assertThat(failureCodeOf(withRuntimeStore(null)))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED);
        assertThat(failureCodeOf(withRuntimeStore(RuntimeStoreDurability.DEVELOPMENT_ONLY)))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED);
    }

    @Test
    void theRepositoryInMemoryStoresCannotSatisfyTheDurableContracts() {
        assertThat(new InMemoryRuntimeStore()).isNotInstanceOf(DurableRuntimeStore.class);
        assertThat(new com.company.campaign.action.InMemoryActionStore(new InMemoryRuntimeStore()))
                .isNotInstanceOf(DurableActionStore.class);

        ProviderRuntimeTestSupport.optedIn(ProviderRuntimeTestSupport.ENABLED)
                .withBean("plainInMemoryRuntimeStore", InMemoryRuntimeStore.class, InMemoryRuntimeStore::new)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_RUNTIME_STORE_REQUIRED);
                });
    }

    @Test
    void aMissingDurableActionStoreFailsClosed() {
        assertThat(failureCodeOf(withRuntimeStore(RuntimeStoreDurability.POSTGRESQL)))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_ACTION_STORE_REQUIRED);
    }

    @Test
    void aMissingProductionDurableAuditAdapterFailsClosed() {
        ApplicationContextRunner runner = withRuntimeStore(RuntimeStoreDurability.POSTGRESQL)
                .withBean("scriptedDurableActionStore", DurableActionStore.class,
                        () -> new ScriptedDurableActionStore(
                                new InMemoryRuntimeStore(), ActionStoreDurability.POSTGRESQL));
        assertThat(failureCodeOf(runner))
                .isEqualTo(ProductionProviderRuntimeErrorCodes.DURABLE_AUDIT_REQUIRED);
    }

    @Test
    void aSimulatedOrNonProductionProviderIsRefused() {
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED,
                        new ProviderRuntimeTestSupport.NonProductionMessageProvider(),
                        ProviderRuntimeTestSupport.scriptedRewardProvider(),
                        ProviderRuntimeTestSupport.oneTenant())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderRuntimeErrorCodes.SIMULATED_PROVIDER_REJECTED);
                });
    }

    @Test
    void everyMissingRequiredCollaboratorFailsClosed() {
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED, null,
                        ProviderRuntimeTestSupport.scriptedRewardProvider(),
                        ProviderRuntimeTestSupport.oneTenant())
                .run(context -> assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                        .isEqualTo(ProductionProviderRuntimeErrorCodes.MESSAGE_PROVIDER_REQUIRED));
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED,
                        ProviderRuntimeTestSupport.scriptedMessageProvider(), null,
                        ProviderRuntimeTestSupport.oneTenant())
                .run(context -> assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                        .isEqualTo(ProductionProviderRuntimeErrorCodes.REWARD_PROVIDER_REQUIRED));
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED,
                        ProviderRuntimeTestSupport.scriptedMessageProvider(),
                        ProviderRuntimeTestSupport.scriptedRewardProvider(), null)
                .run(context -> assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                        .isEqualTo(ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_REQUIRED));
    }

    @Test
    void anInvalidTenantListFailsClosed() {
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED,
                        ProviderRuntimeTestSupport.scriptedMessageProvider(),
                        ProviderRuntimeTestSupport.scriptedRewardProvider(),
                        () -> List.of("tenant-1", "tenant-1"))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderRuntimeErrorCodes.TENANT_SOURCE_INVALID);
                });
    }

    @Test
    void anAmbiguousMessageProviderFailsClosed() {
        ProviderRuntimeTestSupport
                .graph(ProviderRuntimeTestSupport.ENABLED,
                        ProviderRuntimeTestSupport.scriptedMessageProvider(),
                        ProviderRuntimeTestSupport.scriptedRewardProvider(),
                        ProviderRuntimeTestSupport.oneTenant())
                .withBean("secondMessageProvider", MessageProvider.class,
                        ProviderRuntimeTestSupport::scriptedMessageProvider)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(ProductionFailureCodes.codeOf(context.getStartupFailure()))
                            .isEqualTo(ProductionProviderRuntimeErrorCodes.MESSAGE_PROVIDER_REQUIRED);
                });
    }

    @Test
    void theGraphDisclosesNoOwnerOrSecretValue() {
        ProviderRuntimeTestSupport.wired(ProviderRuntimeTestSupport.ENABLED).run(context -> {
            ProviderRuntimeComponents components = context.getBean(ProviderRuntimeComponents.class);
            assertThat(components.toString())
                    .doesNotContain("tenant-1")
                    .doesNotContain("scripted-credential")
                    .doesNotContain("127.0.0.1");
        });
    }

    private static ApplicationContextRunner withRuntimeStore(RuntimeStoreDurability claim) {
        return ProviderRuntimeTestSupport.optedIn(ProviderRuntimeTestSupport.ENABLED)
                .withBean("scriptedDurableRuntimeStore", DurableRuntimeStore.class,
                        () -> new ScriptedDurableRuntimeStore(claim));
    }

    private static String failureCodeOf(ApplicationContextRunner runner) {
        String[] code = new String[1];
        runner.run(context -> code[0] = ProductionFailureCodes.codeOf(context.getStartupFailure()));
        return code[0];
    }
}