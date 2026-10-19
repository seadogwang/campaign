package com.company.campaign.builder.campaignagent;

import java.util.ArrayList;
import java.util.List;

/**
 * One immutable, component-scoped result a component agent returned for one turn.
 *
 * <p>It carries the component code, the closed {@link Status completion/blocking status}, the ordered Skill
 * outcomes the component produced and zero or more typed clarification requests. It is the component agent's own
 * report: the orchestrator reads it, but only the host re-proves every accepted artifact through the T27 assembly
 * workspace before anything is staged, so a result can never mutate the session or the workspace on its own.
 *
 * <p>It never carries another component's artifact, a raw model payload, a Tool argument, a capability payload,
 * a member identifier, a secret or an exception message. A component that was not applicable or already complete
 * returns a deterministic no-op {@link Status#COMPLETE} result, so every component always reports exactly one
 * independent result.
 */
public record CampaignComponentResult(
        CampaignComponentCode component,
        Status status,
        List<CampaignSkillOutcome> outcomes,
        List<CampaignComponentClarification> clarifications
) {

    /** The closed completion/blocking status of one component result. */
    public enum Status {

        /** Every eligible owning Skill of this component produced an accepted READY outcome. */
        COMPLETE,

        /** At least one owning Skill of this component asked a typed clarification. */
        NEEDS_INPUT,

        /** An owning Skill of this component reached a genuine invalid, unavailable or denied terminal. */
        BLOCKED;

        /** The exact stable machine code. */
        public String code() {
            return name();
        }
    }

    public CampaignComponentResult {
        if (component == null || status == null) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentResult",
                    "A component result requires its component code and status.");
        }
        outcomes = outcomes == null ? List.of() : List.copyOf(new ArrayList<>(outcomes));
        clarifications = clarifications == null ? List.of() : List.copyOf(new ArrayList<>(clarifications));
        for (CampaignSkillOutcome outcome : outcomes) {
            if (outcome == null || !component.skillIds().contains(outcome.skillId())) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED, "componentResultOutcome",
                        "A component result may only carry outcomes of Skills its own component owns.");
            }
        }
        for (CampaignComponentClarification clarification : clarifications) {
            if (clarification == null || clarification.component() != component) {
                throw CampaignAgentException.invalid(
                        CampaignAgentErrorCodes.COMPONENT_SKILL_NOT_OWNED, "componentResultClarification",
                        "A component result may only carry clarifications of its own component.");
            }
        }
        if (status == Status.NEEDS_INPUT && clarifications.isEmpty()) {
            throw CampaignAgentException.invalid(
                    CampaignAgentErrorCodes.REQUEST_INVALID, "componentResult",
                    "A needs-input component result must carry its typed clarification request.");
        }
    }

    /** The deterministic completed/no-op result of a component with nothing eligible to run this turn. */
    static CampaignComponentResult noOp(CampaignComponentCode component) {
        return new CampaignComponentResult(component, Status.COMPLETE, List.of(), List.of());
    }

    /** True only when this component completed with no outstanding question and no blocking terminal. */
    public boolean complete() {
        return status == Status.COMPLETE;
    }

    /** A bounded diagnostic: the component, the status and the counts, never a value. */
    @Override
    public String toString() {
        return "CampaignComponentResult[component=" + component
                + ", status=" + status
                + ", outcomes=" + outcomes.size()
                + ", clarifications=" + clarifications.size() + ']';
    }
}