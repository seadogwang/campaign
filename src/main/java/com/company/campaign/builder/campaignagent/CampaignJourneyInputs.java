package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.journey.JourneyEdgeDeclaration;
import com.company.campaign.builder.journey.JourneyGraphDraft;
import com.company.campaign.builder.journey.JourneyNodeDeclaration;
import com.company.campaign.builder.journey.JourneySimulationInput;

import java.util.List;
import java.util.Map;

/**
 * The host-owned typed Journey inputs of one utterance.
 *
 * <p>It carries the complete node and topology declaration plus an optional pure simulation input. It carries no
 * trusted catalog, pack index, policy, context, current Campaign, reference binding or lifecycle fact: those are
 * host evidence reached only through {@code CampaignToolRunContext}, so the routing model can select the Skill
 * but can never manufacture the facts the Skill is decided against.
 *
 * <p>An unresolved instance is the honest "nothing usable was described yet" answer, which the runner turns into
 * a typed clarification rather than an invented graph.
 */
public record CampaignJourneyInputs(JourneyGraphDraft draft, JourneySimulationInput simulation) {

    /** The honest empty input: no node, no edge and no preview was described. */
    public static CampaignJourneyInputs unresolved() {
        return new CampaignJourneyInputs(null, null);
    }

    /** The host-owned no-action Journey used when the accepted brief explicitly requires no action or branch. */
    public static CampaignJourneyInputs minimal() {
        String startRef = "campaign-entry";
        String endRef = "campaign-end";
        return new CampaignJourneyInputs(
                new JourneyGraphDraft(
                        List.of(
                                new JourneyNodeDeclaration(
                                        startRef, FlowPackVersions.START, CapabilityPayload.empty()),
                                new JourneyNodeDeclaration(
                                        endRef, FlowPackVersions.END,
                                        CapabilityPayload.of(Map.of(
                                                FlowEndPack.TERMINAL_FIELD, FlowEndPack.TERMINAL_END)))),
                        List.of(new JourneyEdgeDeclaration(
                                "campaign-entry-end", startRef, endRef, "DEFAULT")),
                        List.of()),
                null);
    }

    /** Binds one host-validated registered event to the Journey's single Start declaration. */
    public CampaignJourneyInputs withTriggerEvent(String eventCode) {
        if (draft == null || eventCode == null) {
            return this;
        }
        List<JourneyNodeDeclaration> nodes = new java.util.ArrayList<>(draft.nodes().size());
        int starts = 0;
        for (JourneyNodeDeclaration node : draft.nodes()) {
            if (FlowPackVersions.START.equals(node.capabilityCode())) {
                starts++;
                nodes.add(new JourneyNodeDeclaration(
                        node.ref(), node.capabilityCode(),
                        CapabilityPayload.of(Map.of(
                                com.company.campaign.builder.capability.flow.FlowStartPack.TRIGGER_EVENT_FIELD,
                                eventCode))));
            } else {
                nodes.add(node);
            }
        }
        if (starts != 1) {
            return unresolved();
        }
        return new CampaignJourneyInputs(
                new JourneyGraphDraft(nodes, draft.edges(), draft.references()), simulation);
    }

    /** True when the host resolved a complete bounded Journey declaration. */
    public boolean resolved() {
        return draft != null;
    }

    /** True when the host also supplied an explicit bounded simulation preview. */
    public boolean hasSimulation() {
        return simulation != null;
    }

    /** A bounded diagnostic: the declaration counts only, never a declaration value. */
    @Override
    public String toString() {
        return "CampaignJourneyInputs[resolved=" + resolved()
                + ", nodes=" + (draft == null ? 0 : draft.nodes().size())
                + ", edges=" + (draft == null ? 0 : draft.edges().size())
                + ", simulation=" + hasSimulation() + ']';
    }
}
