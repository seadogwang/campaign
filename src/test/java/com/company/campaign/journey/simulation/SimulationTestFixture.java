package com.company.campaign.journey.simulation;

import com.company.campaign.builder.capability.flow.FlowConditionPack;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.JourneyManifestFixtures;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.journey.compiler.JourneyCompilation;
import com.company.campaign.journey.compiler.JourneyCompiler;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.compiler.JourneyVersions;
import com.company.campaign.journey.compiler.JourneyTestFixture;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Deterministic, value-safe T20 section-4/5 simulation test fixtures.
 *
 * <p>It fabricates no production availability, Gate approval, budget reservation or external side effect: it
 * builds resolved components, a frozen pack index, a topology and a compilation purely in memory.
 */
public final class SimulationTestFixture {

    public static final String MESSAGE_TEMPLATE = "message-template-1";
    public static final String PROMOTION = "promotion-1";

    private SimulationTestFixture() {
    }

    // ------------------------------------------------------------------ flow components

    public static ResolvedComponent start(String id) {
        return flow(id, FlowPackVersions.START, CapabilityPayload.empty());
    }

    public static ResolvedComponent message(String id) {
        return flow(id, FlowPackVersions.MESSAGE,
                CapabilityPayload.of(Map.of("messageLogicalId", MESSAGE_TEMPLATE)));
    }

    public static ResolvedComponent reward(String id) {
        return flow(id, FlowPackVersions.REWARD,
                CapabilityPayload.of(Map.of("promotionLogicalId", PROMOTION)));
    }

    public static ResolvedComponent wait(String id, String duration, String anchor) {
        return flow(id, FlowPackVersions.WAIT,
                CapabilityPayload.of(Map.of("duration", duration, "anchor", anchor)));
    }

    public static ResolvedComponent end(String id, String terminal) {
        return flow(id, FlowPackVersions.END, CapabilityPayload.of(Map.of("terminal", terminal)));
    }

    public static ResolvedComponent condition(
            String id, String factCode, String operator, String valueType, String value, String onUnknown) {
        return flow(id, FlowPackVersions.CONDITION, CapabilityPayload.of(Map.of(
                FlowConditionPack.FACT_CODE_FIELD, factCode,
                FlowConditionPack.OPERATOR_FIELD, operator,
                FlowConditionPack.VALUE_TYPE_FIELD, valueType,
                FlowConditionPack.VALUE_FIELD, value,
                FlowConditionPack.ON_UNKNOWN_FIELD, onUnknown)));
    }

    public static ResolvedComponent messageTemplate(String id) {
        return new ResolvedComponent(id, "message.template", FlowPackVersions.CONTRACT_VERSION,
                new com.company.campaign.builder.compiler.CompositionSlot("campaign.message", true, false),
                CapabilityPayload.of(Map.of("body", "welcome")), List.of());
    }

    public static ResolvedComponent promotion(String id) {
        return new ResolvedComponent(id, "promotion.reference", FlowPackVersions.CONTRACT_VERSION,
                new com.company.campaign.builder.compiler.CompositionSlot("campaign.promotion", true, false),
                CapabilityPayload.of(Map.of("faceValue", "10")), List.of());
    }

    public static ResolvedComponent flow(String id, String capability, CapabilityPayload payload) {
        return new ResolvedComponent(id, capability, FlowPackVersions.CONTRACT_VERSION,
                FlowPackVersions.SLOT, payload, List.of());
    }

    // ------------------------------------------------------------------ topology, compile, request

    public static JourneyTopology topology(JourneyTopologyEdge... edges) {
        return JourneyTopology.of(JourneyTopology.TOPOLOGY_FORMAT_VERSION, edges);
    }

    public static JourneyTopologyEdge edge(String id, String from, String to, String branch) {
        return new JourneyTopologyEdge(id, from, to,
                com.company.campaign.journey.graph.JourneyBranchKind.valueOf(branch));
    }

    public static ResolvedCampaignManifest manifest(List<ResolvedComponent> components) {
        return JourneyManifestFixtures.manifest(components);
    }

    public static JourneyManifest compile(
            List<ResolvedComponent> components, JourneyTopology topology) {
        return compileBound(components, topology).journey();
    }

    /** Compiles a Journey and keeps it together with the exact source Manifest it bound. */
    public static Linear compileBound(
            List<ResolvedComponent> components, JourneyTopology topology) {
        ResolvedCampaignManifest manifest = manifest(components);
        JourneyCompilation compilation = new JourneyCompiler().compile(
                JourneyTestFixture.request(manifest, topology, JourneyTestFixture.index()));
        return new Linear(manifest, compilation.requireManifest());
    }

    public static SimulationRequest request(
            JourneyManifest journey,
            ResolvedCampaignManifest manifest,
            ScenarioFacts facts) {
        return request(journey, manifest, facts, SimulationPolicy.defaults());
    }

    public static SimulationRequest request(
            JourneyManifest journey,
            ResolvedCampaignManifest manifest,
            ScenarioFacts facts,
            SimulationPolicy policy) {
        return new SimulationRequest(journey, manifest, Instant.parse("2026-01-01T00:00:00Z"),
                ZoneId.of("UTC"), facts, policy, SimulationVersions.SIMULATOR_VERSION);
    }

    public static SimulationRequest request(
            Linear bound, ScenarioFacts facts, SimulationPolicy policy) {
        return request(bound.journey(), bound.sourceManifest(), facts, policy);
    }

    /** A linear Start -> Message -> End journey with its bound source Manifest. */
    public static Linear linear() {
        List<ResolvedComponent> components = List.of(
                start("s"), message("m"), end("e", "END"), messageTemplate(MESSAGE_TEMPLATE));
        JourneyTopology topology = topology(
                edge("s-m", "s", "m", "DEFAULT"),
                edge("m-e", "m", "e", "DEFAULT"));
        return new Linear(manifest(components), compile(components, topology));
    }

    /** A bound Journey and its exact source Manifest, kept together for request construction. */
    public record Linear(ResolvedCampaignManifest sourceManifest, JourneyManifest journey) {
    }
}
