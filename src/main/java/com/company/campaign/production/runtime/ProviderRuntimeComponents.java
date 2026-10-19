package com.company.campaign.production.runtime;

import com.company.campaign.action.ActionApplicationService;
import com.company.campaign.action.ActionRequestResolver;
import com.company.campaign.action.ActionWorker;
import com.company.campaign.action.DurableActionStore;
import com.company.campaign.action.HostActionRuntimePort;
import com.company.campaign.action.MessageActionService;
import com.company.campaign.action.RewardActionService;
import com.company.campaign.runtime.DurableRuntimeStore;
import com.company.campaign.runtime.RuntimeIngressService;
import com.company.campaign.runtime.RuntimeMaterializationService;
import com.company.campaign.runtime.RuntimeWorker;
import com.company.campaign.runtime.T21RuntimeWorkSource;

import java.time.Clock;

/**
 * The one coherent production provider runtime graph.
 *
 * <p>It holds the exact single instances the host wired, so a caller can prove that the Runtime worker, the
 * Action application service, the two facades and the cycle service all share the same durable stores, clock and
 * frozen source/definition collaborators. It is constructed exactly once by
 * {@link ProductionProviderRuntimeConfiguration} and every published bean is one of its members, so no second
 * hidden graph — and no fallback graph over an in-memory store or a simulated Provider — can exist.
 *
 * <p>It adds no behaviour of its own and prints no owner value.
 */
public record ProviderRuntimeComponents(
        DurableRuntimeStore runtimeStore,
        DurableActionStore actionStore,
        Clock clock,
        T21RuntimeWorkSource workSource,
        RuntimeIngressService ingress,
        RuntimeMaterializationService materialization,
        RuntimeWorker runtimeWorker,
        HostActionRuntimePort actionRuntime,
        ActionRequestResolver requestResolver,
        ActionApplicationService actionApplicationService,
        MessageActionService messageActionService,
        RewardActionService rewardActionService,
        ActionWorker actionWorker,
        ProviderRuntimeCycleService cycleService,
        ProviderRuntimeObservability observability,
        ProviderRuntimeTopologyEvidence topologyEvidence
) {

    public ProviderRuntimeComponents {
        if (runtimeStore == null || actionStore == null || clock == null || workSource == null || ingress == null
                || materialization == null || runtimeWorker == null || actionRuntime == null
                || requestResolver == null || actionApplicationService == null
                || messageActionService == null || rewardActionService == null || actionWorker == null
                || cycleService == null || observability == null || topologyEvidence == null) {
            throw new ProductionProviderRuntimeConfigurationException(
                    ProductionProviderRuntimeErrorCodes.CONFIGURATION_INVALID,
                    "The production provider runtime graph is incomplete.");
        }
    }

    /** Redacted diagnostics: no store, Provider, tenant or credential value is printed. */
    @Override
    public String toString() {
        return "ProviderRuntimeComponents[runtimeStore=" + runtimeStore.getClass().getSimpleName()
                + ", actionStore=" + actionStore.getClass().getSimpleName()
                + ", topologyEvidence=" + topologyEvidence + ']';
    }
}