package com.company.campaign.action;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.runtime.ActionHandoff;
import com.company.campaign.runtime.CampaignExecution;
import com.company.campaign.runtime.JourneyParticipant;

import java.util.Objects;

/**
 * Everything one exact handoff resolves to: the immutable request, the exact frozen pins it was resolved
 * against and the frozen reward-budget declaration a REWARD action must reserve under.
 *
 * <p>It is a transient resolution result, never persisted: the request and the action are what persist. It
 * carries the frozen full definition and base definition only so the caller can re-prove the same pins when it
 * finishes the Runtime node, so no second, later lookup can silently resolve a different frozen version.
 */
public record ActionMaterial(
        ActionHandoff handoff,
        CampaignExecution execution,
        JourneyParticipant participant,
        FullPublishedDefinition definition,
        PublishedCampaignDefinition base,
        ActionRequest request,
        RewardBudgetLimits budgetLimits
) {

    public ActionMaterial {
        Objects.requireNonNull(handoff, "handoff");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(request, "request");
        ExecutionPins.requireBinding(handoff, execution, participant);
        if (request.actionKind().reservesRewardBudget() != (budgetLimits != null)) {
            throw ActionSupport.invalid(ActionErrorCodes.ACTION_BUDGET_INVALID, "budgetLimits");
        }
    }

    /** The exact Campaign Execution one resolved handoff belongs to. */
    public CampaignExecution campaignExecution() {
        return execution;
    }

    /** One bounded re-proof that a handoff, an Execution and a Participant witness each other exactly. */
    static final class ExecutionPins {

        private ExecutionPins() {
        }

        static void requireBinding(
                ActionHandoff handoff, CampaignExecution execution, JourneyParticipant participant) {
            if (!execution.tenantId().equals(handoff.tenantId())
                    || !execution.executionId().equals(handoff.executionId())
                    || !participant.tenantId().equals(handoff.tenantId())
                    || !participant.executionId().equals(handoff.executionId())
                    || !participant.participantId().equals(handoff.participantId())
                    || !participant.fullDefinitionId().equals(execution.fullDefinitionId())
                    || !participant.fullDefinitionHash().equals(execution.fullDefinitionHash())
                    || !participant.journeyHash().equals(execution.journeyHash())) {
                throw ActionSupport.invalid(
                        ActionErrorCodes.ACTION_HANDOFF_BINDING_MISMATCH, "handoffBinding");
            }
        }
    }
}
