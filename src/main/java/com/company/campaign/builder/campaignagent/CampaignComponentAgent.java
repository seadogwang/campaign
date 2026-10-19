package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One bounded component agent: it runs exactly the Skills one {@link CampaignComponentCode} owns.
 *
 * <p>It is the smallest execution unit of the orchestration model. It is handed an immutable
 * {@link CampaignComponentTask} and it can do exactly one thing: invoke the commands of that task through the
 * closed server-owned {@link CampaignSkillRouter}. It cannot mutate the session or the workspace, cannot read
 * another component's artifact, cannot invent another component's requirement and cannot write user-facing
 * assistant copy: it returns an immutable {@link CampaignComponentResult} and nothing else.
 *
 * <p>Ownership is enforced twice: the task itself cannot be constructed with a foreign Skill command, and this
 * agent re-checks every command before it routes. A component that was already complete or is not applicable is
 * handed an empty command list and returns a deterministic no-op {@link CampaignComponentResult.Status#COMPLETE}
 * result.
 *
 * <p>Within one component the Skills run in the component's own declaration order and a clarification or a
 * genuine terminal stops that component, because an owning Skill of the same component may be a real
 * prerequisite of the next one. The orchestrator, not this agent, is what keeps unrelated components progressing.
 *
 * <p><strong>Component wording ownership.</strong> This agent owns the final wording of each clarification it
 * returns. It may call the model wording port ({@link CampaignRequirementExtractorPort}) from inside the
 * component execution boundary, using only its own {@code NeedsInput}, stable error code/question codes/options,
 * component identity and accepted brief. A model-reworded question is accepted only after bounded/safe-copy
 * validation and semantic retention. If the wording port is unwired, returns empty, or fails validation, the
 * component-owned deterministic fallback from {@link CampaignComponentClarification#promptFor} is used.
 */
public final class CampaignComponentAgent {

    private final CampaignSkillRouter router;
    private final CampaignRequirementExtractorPort wordingPort;

    public CampaignComponentAgent(CampaignSkillRouter router) {
        this(router, null);
    }

    /**
     * Creates a component agent with an optional wording port for model-reworded clarifications.
     *
     * <p>When {@code wordingPort} is non-null and wired, each component clarification is offered to the
     * port before the fallback prompt is committed. The port receives only this component's own data:
     * its {@code NeedsInput} outcome, stable error code/question codes/options, component identity,
     * and the accepted brief. The Main Agent is never consulted during this call.
     */
    public CampaignComponentAgent(CampaignSkillRouter router, CampaignRequirementExtractorPort wordingPort) {
        this.router = Objects.requireNonNull(router, "router");
        this.wordingPort = wordingPort;
    }

    /** Runs the eligible Skills of one component task and returns its independent component result. */
    public CampaignComponentResult run(CampaignComponentTask task) {
        Objects.requireNonNull(task, "task");
        CampaignComponentCode component = task.component();
        for (CampaignSkillCommand command : task.commands()) {
            if (!task.ownedSkillIds().contains(command.skillId())) {
                throw CampaignAgentException.of(
                        CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED,
                        "A component agent may only route a Skill its own component owns.");
            }
        }
        if (task.noOp()) {
            // Not applicable or already complete: a deterministic completed/no-op result, never an absent one.
            return CampaignComponentResult.noOp(component);
        }
        List<CampaignSkillOutcome> outcomes = new ArrayList<>();
        List<CampaignComponentClarification> clarifications = new ArrayList<>();
        boolean blocked = false;
        for (CampaignSkillCommand command : task.commands()) {
            CampaignSkillOutcome outcome = router.run(command);
            outcomes.add(outcome);
            if (outcome instanceof CampaignSkillOutcome.NeedsInput needsInput) {
                CampaignComponentClarification clarification =
                        CampaignComponentClarification.from(component, needsInput);
                clarifications.add(wordClarification(clarification, task));
                blocked = false;
                break;
            }
            if (outcome instanceof CampaignSkillOutcome.Terminal) {
                blocked = true;
                break;
            }
            if (outcome instanceof CampaignSkillOutcome.Unavailable) {
                // An unavailable owning Skill is a bounded invalid observation, but it does not stop a sibling
                // Skill of the same component the way a genuine clarification or terminal does.
                blocked = true;
            }
        }
        CampaignComponentResult.Status status = !clarifications.isEmpty()
                ? CampaignComponentResult.Status.NEEDS_INPUT
                : blocked ? CampaignComponentResult.Status.BLOCKED : CampaignComponentResult.Status.COMPLETE;
        return new CampaignComponentResult(component, status, outcomes, clarifications);
    }

    /**
     * Attempts to word one component clarification through the wired model wording port.
     *
     * <p>The port receives only this component's own data: its error code, question codes, options,
     * component identity, and the accepted brief. The user text is passed only as a language cue.
     * A model-reworded prompt is accepted only after bounded/safe-copy validation and semantic
     * retention. When the port is unwired, returns empty, or fails validation, the deterministic
     * fallback prompt from {@link CampaignComponentClarification#from} is preserved unchanged.
     *
     * <p>This method is component-private: it is called from {@link #run(CampaignComponentTask)}
     * inside this component's own execution boundary. The Main Agent never calls it.
     */
    private CampaignComponentClarification wordClarification(
            CampaignComponentClarification clarification, CampaignComponentTask task) {
        if (wordingPort == null) {
            return clarification;
        }
        try {
            java.util.Optional<String> worded = wordingPort.componentClarificationWording(
                    task.brief(), clarification, task.userText());
            if (worded.isEmpty()) {
                return clarification;
            }
            return CampaignComponentClarification.validateAndAcceptWording(
                    clarification, worded.get());
        } catch (RuntimeException ignored) {
            // The wording port failed or the wording was rejected: use the deterministic fallback.
            // A wording outage degrades the copy instead of failing an accepted turn.
            return clarification;
        }
    }

    /** A bounded diagnostic: the wiring facts only, never a value. */
    @Override
    public String toString() {
        return "CampaignComponentAgent[skills=" + router.skills().size()
                + ", wordingPort=" + (wordingPort != null) + ']';
    }
}