package com.company.campaign.journey.compiler;

import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.builder.capability.flow.FlowEndPack;
import com.company.campaign.builder.capability.flow.FlowMessagePack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.flow.FlowRewardPack;
import com.company.campaign.builder.capability.flow.FlowWaitPack;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CompositionSlot;
import com.company.campaign.builder.compiler.ResolvedCatalogReference;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.builder.compiler.RuntimeCompatibility;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, value-safe T20 Journey test fixtures.
 *
 * <p>It fabricates no production availability, Gate approval, budget reservation or catalog mutation: it is a
 * contract fixture that builds resolved components, a frozen pack index and a topology purely in memory.
 */
public final class JourneyTestFixture {

    public static final String MESSAGE_TEMPLATE = "message.template";
    public static final String PROMOTION_REFERENCE = "promotion.reference";

    private JourneyTestFixture() {
    }

    // ------------------------------------------------------------------ flow node components

    public static ResolvedComponent start(String id) {
        return flow(id, FlowPackVersions.START, CapabilityPayload.empty());
    }

    public static ResolvedComponent message(String id, String reference) {
        return flow(id, FlowPackVersions.MESSAGE,
                CapabilityPayload.of(Map.of(FlowMessagePack.REFERENCE_FIELD, reference)));
    }

    public static ResolvedComponent message(String id, String reference, List<ResolvedCatalogReference> refs) {
        return flow(id, FlowPackVersions.MESSAGE,
                CapabilityPayload.of(Map.of(FlowMessagePack.REFERENCE_FIELD, reference)), refs);
    }

    public static ResolvedComponent wait(String id, String duration, String anchor) {
        return flow(id, FlowPackVersions.WAIT,
                CapabilityPayload.of(Map.of(FlowWaitPack.DURATION_FIELD, duration, FlowWaitPack.ANCHOR_FIELD, anchor)));
    }

    public static ResolvedComponent condition(String id) {
        return flow(id, FlowPackVersions.CONDITION, CapabilityPayload.of(Map.of(
                FlowConditionPack.FACT_CODE_FIELD, "member.tier",
                FlowConditionPack.OPERATOR_FIELD, FlowConditionPack.OPERATOR_EQUALS,
                FlowConditionPack.VALUE_TYPE_FIELD, FlowConditionPack.TYPE_STRING,
                FlowConditionPack.VALUE_FIELD, "GOLD",
                FlowConditionPack.ON_UNKNOWN_FIELD, FlowConditionPack.ON_UNKNOWN)));
    }

    public static ResolvedComponent reward(String id, String reference) {
        return flow(id, FlowPackVersions.REWARD,
                CapabilityPayload.of(Map.of(FlowRewardPack.REFERENCE_FIELD, reference)));
    }

    public static ResolvedComponent end(String id, String terminal) {
        return flow(id, FlowPackVersions.END,
                CapabilityPayload.of(Map.of(FlowEndPack.TERMINAL_FIELD, terminal)));
    }

    /** A Flow component at an explicit contract version and slot, for fail-closed tests. */
    public static ResolvedComponent flowAt(
            String id, String capability, String contractVersion, CompositionSlot slot, Map<String, Object> payload) {
        return new ResolvedComponent(id, capability, contractVersion, slot, CapabilityPayload.of(payload), List.of());
    }

    public static ResolvedComponent flow(
            String id, String capability, CapabilityPayload payload) {
        return new ResolvedComponent(id, capability, FlowPackVersions.CONTRACT_VERSION,
                FlowPackVersions.SLOT, payload, List.of());
    }

    public static ResolvedComponent flow(
            String id, String capability, CapabilityPayload payload, List<ResolvedCatalogReference> references) {
        return new ResolvedComponent(id, capability, FlowPackVersions.CONTRACT_VERSION,
                FlowPackVersions.SLOT, payload, references);
    }

    // ------------------------------------------------------------------ referenced components

    public static ResolvedComponent messageTemplate(String id) {
        return new ResolvedComponent(id, MESSAGE_TEMPLATE, FlowPackVersions.CONTRACT_VERSION,
                new CompositionSlot("campaign.message", true, false),
                CapabilityPayload.of(Map.of("body", "welcome")), List.of());
    }

    public static ResolvedComponent promotion(String id) {
        return new ResolvedComponent(id, PROMOTION_REFERENCE, FlowPackVersions.CONTRACT_VERSION,
                new CompositionSlot("campaign.promotion", true, false),
                CapabilityPayload.of(Map.of("faceValue", "10")), List.of());
    }

    // ------------------------------------------------------------------ index

    public static T19PackIndex index() {
        return indexWith();
    }

    public static T19PackFacts facts(String capability) {
        return new T19PackFacts(capability, FlowPackVersions.CONTRACT_VERSION, FlowPackVersions.SLOT,
                true, FlowPackVersions.RUNTIME_COMPATIBILITY);
    }

    public static T19PackFacts facts(
            String capability, String contractVersion, CompositionSlot slot, boolean enabled,
            RuntimeCompatibility runtime) {
        return new T19PackFacts(capability, contractVersion, slot, enabled, runtime);
    }

    /** An index of the six Flow facts, with any caller-supplied facts replacing their capability entry. */
    public static T19PackIndex indexWith(T19PackFacts... replacements) {
        List<T19PackFacts> facts = new ArrayList<>();
        for (String capability : FlowPackVersions.CAPABILITY_CODES) {
            T19PackFacts replacement = null;
            for (T19PackFacts candidate : replacements) {
                if (candidate.capabilityCode().equals(capability)) {
                    replacement = candidate;
                }
            }
            facts.add(replacement == null ? facts(capability) : replacement);
        }
        return T19PackIndex.of(FlowPackVersions.RUNTIME_SCHEMA_VERSION, facts);
    }

    // ------------------------------------------------------------------ topology and request

    public static JourneyTopology topology(JourneyTopologyEdge... edges) {
        return JourneyTopology.of(JourneyTopology.TOPOLOGY_FORMAT_VERSION, edges);
    }

    public static JourneyTopologyEdge edge(String id, String from, String to, String branch) {
        return new JourneyTopologyEdge(id, from, to,
                com.company.campaign.journey.graph.JourneyBranchKind.valueOf(branch));
    }

    public static JourneyCompileRequest request(
            com.company.campaign.builder.compiler.ResolvedCampaignManifest manifest,
            JourneyTopology topology,
            T19PackIndex index) {
        return new JourneyCompileRequest(manifest, topology, index,
                JourneyVersions.JOURNEY_COMPILER_VERSION, JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION);
    }
}
