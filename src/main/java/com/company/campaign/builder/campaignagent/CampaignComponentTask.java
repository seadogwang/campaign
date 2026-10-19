package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One immutable, component-scoped task the orchestrator issues to exactly one component agent.
 *
 * <p>It is deliberately separate from {@link CampaignComponentTodo}: the TODO is the host-derived progress view
 * of the conversation, while this task is what the component agent is handed for one turn. It carries the
 * component code, the current host-derived TODO, the accepted Campaign brief, the latest bounded user text and
 * answers, and only the closed set of commands belonging to Skills the component owns.
 *
 * <p>The ownership rule is absolute: every command must address a Skill declared by
 * {@link CampaignComponentCode#skillIds()} of this component, in the component's own declaration order. A task
 * that names a Skill the component does not own cannot be constructed at all, so a component agent can never be
 * handed a foreign Skill to route.
 *
 * <p>It never carries another component's artifact, a raw model payload, a Tool argument, a member identifier,
 * a secret or an exception message.
 */
public record CampaignComponentTask(
        CampaignComponentCode component,
        CampaignComponentTodo todo,
        CampaignRequirementBrief brief,
        String userText,
        List<CampaignClarificationAnswer> answers,
        List<CampaignSkillCommand> commands
) {

    public CampaignComponentTask {
        if (component == null || todo == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentTask",
                    "A component task requires its component code and host-derived TODO.");
        }
        if (todo.component() != component) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentTaskTodo",
                    "A component task TODO must describe the component it is issued to.");
        }
        if (userText != null && userText.length() > CampaignAgentLimits.MAX_UTTERANCE_LENGTH) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentTaskUserText",
                    "A component task user text must be bounded.");
        }
        brief = brief == null ? CampaignRequirementBrief.empty() : brief;
        answers = answers == null ? List.of() : List.copyOf(new ArrayList<>(answers));
        commands = commands == null ? List.of() : List.copyOf(new ArrayList<>(commands));
        Set<String> seen = new HashSet<>();
        int previousRank = -1;
        for (CampaignSkillCommand command : commands) {
            if (command == null) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "componentTaskCommand",
                        "A component task carries well-formed commands only.");
            }
            String skillId = command.skillId();
            if (!component.skillIds().contains(skillId)) {
                // The single guard that makes "a component agent cannot route outside its ownership" structural.
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED, "componentTaskSkill",
                        "A component task may only carry commands of Skills its own component owns.");
            }
            if (!seen.add(skillId)) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.REQUEST_INVALID, "componentTaskSkill",
                        "A component task must not repeat a Skill.");
            }
            int rank = component.skillIds().indexOf(skillId);
            if (rank <= previousRank) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.ROUTING_ORDER_INVALID, "componentTaskSkill",
                        "A component task lists its Skills in the component's own declaration order.");
            }
            previousRank = rank;
        }
    }

    /** The closed Skill identities this component owns, in the server-owned declaration order. */
    public List<String> ownedSkillIds() {
        return component.skillIds();
    }

    /** True when this component has no eligible Skill to run this turn. */
    public boolean noOp() {
        return commands.isEmpty();
    }

    /** A bounded diagnostic: the component, the TODO status and the command count, never a value. */
    @Override
    public String toString() {
        return "CampaignComponentTask[component=" + component
                + ", status=" + todo.status()
                + ", commands=" + commands.size() + ']';
    }
}