package com.company.campaign.action;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.CampaignExecution;
import com.company.campaign.runtime.ExecutionCounters;
import com.company.campaign.runtime.ExternalActionCompletion;
import com.company.campaign.runtime.ExternalActionCompletionResult;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.runtime.NodeExecution;
import com.company.campaign.runtime.RuntimeCampaignState;
import com.company.campaign.runtime.RuntimeCampaignStatePort;
import com.company.campaign.runtime.RuntimeDefinitionLookupPort;
import com.company.campaign.runtime.RuntimeStore;
import com.company.campaign.runtime.RuntimeTask;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The accepted host wiring of {@link ActionRuntimePort} over the T22 Runtime collaborators.
 *
 * <p>It adds no behaviour of its own: every read is the same bounded lookup the T22 worker already performs,
 * and the one write is the additive external-action node completion the T22 store already implements. It
 * resolves no mutable pointer and holds no cached state, so the revision a caller observes is the revision the
 * very same store re-proves at commit time.
 *
 * <p>A host that cannot answer fails closed exactly as T22 does: a throwing Campaign-state collaborator yields
 * an empty projection rather than an unrestricted exception, so absence and refusal are indistinguishable to
 * the caller.
 */
public final class HostActionRuntimePort implements ActionRuntimePort {

    private final RuntimeStore store;
    private final RuntimeDefinitionLookupPort definitions;
    private final RuntimeCampaignStatePort campaignStates;

    public HostActionRuntimePort(
            RuntimeStore store,
            RuntimeDefinitionLookupPort definitions,
            RuntimeCampaignStatePort campaignStates) {
        this.store = Objects.requireNonNull(store, "store");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.campaignStates = Objects.requireNonNull(campaignStates, "campaignStates");
    }

    @Override
    public List<RuntimeTask> findActionReadyTasks(String tenantId, Instant claimInstant, int limit) {
        return store.findActionReadyTasks(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                Objects.requireNonNull(claimInstant, "claimInstant"), limit);
    }

    @Override
    public Optional<RuntimeTask> findRuntimeTask(String tenantId, String taskId) {
        return store.findTask(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(taskId, "taskId"));
    }

    @Override
    public Optional<ActionHandoff> findHandoff(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        for (ActionHandoff handoff : store.findActionHandoffs(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(executionId, "executionId"))) {
            if (handoff.participantId().equals(participantId) && handoff.nodeLogicalId().equals(nodeLogicalId)) {
                return Optional.of(handoff);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<CampaignExecution> findExecutionById(String tenantId, String executionId) {
        return store.findExecutionById(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(executionId, "executionId"));
    }

    @Override
    public List<JourneyParticipant> findParticipants(String tenantId, String executionId) {
        return store.findParticipants(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(executionId, "executionId"));
    }

    @Override
    public Optional<NodeExecution> findNodeExecution(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        return store.findNodeExecution(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(executionId, "executionId"),
                ActionSupport.requireIdentifier(participantId, "participantId"),
                ActionSupport.requireIdentifier(nodeLogicalId, "nodeLogicalId"));
    }

    @Override
    public Optional<ExecutionCounters> findCounters(String tenantId, String executionId) {
        return store.findCounters(
                ActionSupport.requireIdentifier(tenantId, "tenantId"),
                ActionSupport.requireIdentifier(executionId, "executionId"));
    }

    @Override
    public Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId) {
        return definitions.findFullDefinition(
                ActionSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId"));
    }

    @Override
    public Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId) {
        return definitions.findBaseDefinition(
                ActionSupport.requireIdentifier(publishedId, "publishedId"));
    }

    @Override
    public Optional<RuntimeCampaignState> findCampaignState(String tenantId, String campaignId) {
        RuntimeCampaignState state;
        try {
            state = campaignStates.findCampaignState(tenantId, campaignId).orElse(null);
        } catch (RuntimeException hostFailure) {
            // A host that cannot answer is indistinguishable from a host that holds nothing; both fail closed.
            return Optional.empty();
        }
        if (state == null || !state.tenantId().equals(tenantId) || !state.campaignId().equals(campaignId)) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    @Override
    public ExternalActionCompletionResult commitExternalActionCompletion(ExternalActionCompletion commit) {
        return store.commitExternalActionCompletion(Objects.requireNonNull(commit, "commit"));
    }

    /** Redacted diagnostics: the adapter holds no owner value. */
    @Override
    public String toString() {
        return "HostActionRuntimePort[store=" + store.getClass().getSimpleName() + ']';
    }
}