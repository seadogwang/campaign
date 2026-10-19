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
import com.company.campaign.runtime.RuntimeTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The narrow host port through which the external-action layer reads and finishes exactly one T22 Runtime node.
 *
 * <p>It can read only the persisted {@code ACTION_READY} handoff task and handoff, the exact Execution,
 * Participant, NodeExecution and aggregate it belongs to, the exact frozen full and base definitions those
 * pins name, and the host-owned Campaign activation projection. There is deliberately no lookup of a mutable
 * latest catalog pointer, a Builder Session, a Proposal, a Plan, a Draft, a Prompt, a Skill, a Tool, an Agent
 * or an LLM: none of those is reachable from here at all.
 *
 * <p>The one write is the additive external-action node completion, which is the single atomic unit that
 * retires the exact handoff task, moves the frozen node and Participant, enqueues the exact successor task and
 * restates the aggregate — all inside one store transaction.
 */
public interface ActionRuntimePort {

    /** Every outstanding external-action handoff task of one tenant that is due at the given instant. */
    List<RuntimeTask> findActionReadyTasks(String tenantId, Instant claimInstant, int limit);

    /** The RuntimeTask of one tenant and task identity, or empty when that tenant does not own it. */
    Optional<RuntimeTask> findRuntimeTask(String tenantId, String taskId);

    /** The exact persisted handoff of one Participant's frozen node within one Execution, or empty. */
    Optional<ActionHandoff> findHandoff(
            String tenantId, String executionId, String participantId, String nodeLogicalId);

    /** The Execution of one tenant and Execution identity, or empty. */
    Optional<CampaignExecution> findExecutionById(String tenantId, String executionId);

    /** Every Participant of one Execution, in deterministic Participant order. */
    List<JourneyParticipant> findParticipants(String tenantId, String executionId);

    /** The NodeExecution of one Participant's frozen node, or empty. */
    Optional<NodeExecution> findNodeExecution(
            String tenantId, String executionId, String participantId, String nodeLogicalId);

    /** The aggregate counters of one Execution, or empty when it has none. */
    Optional<ExecutionCounters> findCounters(String tenantId, String executionId);

    /** The immutable full publication with the given identifier, or empty when the host holds none. */
    Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId);

    /** The immutable base published definition with the given published identifier, or empty. */
    Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId);

    /** The host-owned activation projection of one Campaign, or empty when it cannot be proved. */
    Optional<RuntimeCampaignState> findCampaignState(String tenantId, String campaignId);

    /** Commits one external-action node completion as one unit, or writes nothing. */
    ExternalActionCompletionResult commitExternalActionCompletion(ExternalActionCompletion commit);
}