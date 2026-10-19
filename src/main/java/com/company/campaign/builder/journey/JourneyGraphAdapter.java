package com.company.campaign.builder.journey;

import com.company.campaign.builder.capability.composition.T19PackFacts;
import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.capability.flow.FlowCapabilityPacks;
import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.builder.capability.pack.CapabilityPackContract;
import com.company.campaign.builder.compiler.CapabilityPayload;
import com.company.campaign.builder.compiler.CapabilityRequest;
import com.company.campaign.builder.compiler.CapabilityResolution;
import com.company.campaign.builder.compiler.FrozenAuthoringPolicy;
import com.company.campaign.builder.compiler.FrozenCapabilityCatalog;
import com.company.campaign.builder.compiler.JourneyAuthoringManifests;
import com.company.campaign.builder.compiler.ResolvedComponent;
import com.company.campaign.contracts.Issue;
import com.company.campaign.journey.compiler.JourneyCompilation;
import com.company.campaign.journey.compiler.JourneyCompileRequest;
import com.company.campaign.journey.compiler.JourneyCompiler;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.compiler.JourneyVersions;
import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.journey.graph.JourneyTopology;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.journey.simulation.ScenarioFact;
import com.company.campaign.journey.simulation.ScenarioFacts;
import com.company.campaign.journey.simulation.SimulatedPath;
import com.company.campaign.journey.simulation.SimulationException;
import com.company.campaign.journey.simulation.SimulationPolicy;
import com.company.campaign.journey.simulation.SimulationRequest;
import com.company.campaign.journey.simulation.SimulationResult;
import com.company.campaign.journey.simulation.SimulationVersions;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The host-owned, pure Journey authoring adapter: the one place the T27/T28 orchestration layer reaches T19 and
 * T20 without taking a dependency on them.
 *
 * <p>It normalizes every declared node through its accepted T19 Flow node pack, builds the derived authoring
 * Manifest, resolves the declared edges into a genuine T20 {@link JourneyTopology}, compiles the graph with the
 * accepted {@link JourneyCompiler} against an explicit frozen {@link T19PackIndex}, and simulates it with the
 * accepted pure {@link com.company.campaign.journey.simulation.JourneySimulator}.
 *
 * <p>It is stateless and reads no repository, clock, random source, ambient thread-bound value, model, network,
 * Provider or mutable registry. Every refusal is a bounded {@link JourneyAuthoringException}; every outcome is a bounded
 * immutable value that exposes codes, counts, identities and hashes only. It never stages, compiles a Campaign
 * Plan, saves, applies, publishes or creates Runtime work.
 */
public final class JourneyGraphAdapter {

    private static final JourneyCompiler COMPILER = new JourneyCompiler();

    /** The closed node capability catalog: the six accepted Flow node capabilities, in declaration order. */
    public List<JourneyNodeCapability> nodeCatalog() {
        List<JourneyNodeCapability> catalog = new ArrayList<>(FlowPackVersions.CAPABILITY_CODES.size());
        for (String capabilityCode : FlowPackVersions.CAPABILITY_CODES) {
            JourneyNodeKind kind = kindOf(capabilityCode);
            if (kind == null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.CATALOG_INVALID);
            }
            catalog.add(new JourneyNodeCapability(
                    capabilityCode, kind.name(), kind.isTerminal(), FlowPackVersions.CONTRACT_VERSION));
        }
        return List.copyOf(catalog);
    }

    /**
     * Normalizes every declared node through its accepted T19 Flow pack.
     *
     * <p>Composition proves nothing about the graph: a composition is not a Journey and is never stageable.
     */
    public JourneyComposition compose(
            JourneyGraphDraft draft,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context) {
        return JourneyComposition.of(normalizedNodes(draft, catalog, policy, context), draft.edges().size());
    }

    /**
     * Validates the complete authoring graph and compiles it with the accepted T20 semantics.
     *
     * <p>A node payload its accepted pack refuses fails closed with a bounded code; a graph that is structurally
     * invalid is reported as a bounded, deterministic outcome with no component and no hash, never as a partial
     * Journey.
     */
    public JourneyValidationOutcome validate(
            JourneyGraphDraft draft,
            T19PackIndex packIndex,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context) {
        if (packIndex == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.PACK_INDEX_MISSING);
        }
        List<JourneyNodeComponent> nodes = normalizedNodes(draft, catalog, policy, context);
        List<ResolvedComponent> components = authoringComponents(draft, nodes, packIndex);
        JourneyTopology topology = topologyOf(draft);
        JourneyCompilation compilation = COMPILER.compile(new JourneyCompileRequest(
                JourneyAuthoringManifests.ofAuthoringComponents(components),
                topology,
                packIndex,
                JourneyVersions.JOURNEY_COMPILER_VERSION,
                JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION));
        if (!compilation.isCompiled()) {
            return new JourneyValidationOutcome(
                    false, List.of(), List.of(), null, List.of(), nodes.size(), draft.edges().size(),
                    issueCodes(compilation.issues()), null, null);
        }
        JourneyManifest journey = compilation.manifest();
        return new JourneyValidationOutcome(
                true,
                nodes,
                draft.edges(),
                journey.startNodeLogicalId(),
                journey.terminalNodeLogicalIds(),
                journey.nodes().size(),
                journey.edges().size(),
                List.of(),
                journey.contentHash(),
                topology.topologyHash());
    }

    /**
     * Runs the accepted pure virtual-clock simulation over one already-valid authoring graph.
     *
     * <p>It creates no Runtime work and is never needed for staging: the simulation is bounded preview evidence
     * projected from explicit scenario facts and an explicit virtual start, timezone and policy.
     */
    public JourneySimulationSummary simulate(
            JourneyGraphDraft draft,
            JourneySimulationInput input,
            T19PackIndex packIndex,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context) {
        if (packIndex == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.PACK_INDEX_MISSING);
        }
        List<JourneyNodeComponent> nodes = normalizedNodes(draft, catalog, policy, context);
        List<ResolvedComponent> components = authoringComponents(draft, nodes, packIndex);
        var authoringManifest = JourneyAuthoringManifests.ofAuthoringComponents(components);
        JourneyTopology topology = topologyOf(draft);
        JourneyCompilation compilation = COMPILER.compile(new JourneyCompileRequest(
                authoringManifest,
                topology,
                packIndex,
                JourneyVersions.JOURNEY_COMPILER_VERSION,
                JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION));
        if (!compilation.isCompiled()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_INVALID);
        }
        JourneyManifest journey = compilation.manifest();
        ScenarioFacts facts = scenarioFacts(input);
        SimulationPolicy simulationPolicy =
                new SimulationPolicy(input.maxPaths(), input.maxStepsPerPath(), input.maxActions());
        Instant start;
        ZoneId zone;
        try {
            start = Instant.parse(input.virtualStartInstant());
            zone = ZoneId.of(input.virtualTimezone());
        } catch (RuntimeException rejected) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        SimulationResult result;
        try {
            result = new com.company.campaign.journey.simulation.JourneySimulator().simulate(
                    new SimulationRequest(
                            journey,
                            authoringManifest,
                            start,
                            zone,
                            facts,
                            simulationPolicy,
                            SimulationVersions.SIMULATOR_VERSION));
        } catch (SimulationException rejected) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        return summaryOf(result, start);
    }

    // ------------------------------------------------------------------ composition

    private List<JourneyNodeComponent> normalizedNodes(
            JourneyGraphDraft draft,
            FrozenCapabilityCatalog catalog,
            FrozenAuthoringPolicy policy,
            TrustedExecutionContext context) {
        List<JourneyNodeComponent> nodes = new ArrayList<>(draft.nodes().size());
        for (JourneyNodeDeclaration declaration : draft.nodes()) {
            CapabilityPackContract pack = flowPack(declaration.capabilityCode());
            if (pack == null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_CAPABILITY_UNKNOWN);
            }
            CapabilityResolution resolution = pack.resolve(
                    new CapabilityRequest(declaration.ref(), declaration.payload(), catalog, policy, context));
            if (!(resolution instanceof CapabilityResolution.Resolved resolved)) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_PAYLOAD_REJECTED);
            }
            nodes.add(new JourneyNodeComponent(
                    declaration.ref(), declaration.capabilityCode(), resolved.value()));
        }
        return List.copyOf(nodes);
    }

    /**
     * The complete authoring component set: the normalized Flow nodes plus one declared identity scaffold per
     * declared reference target.
     *
     * <p>A scaffold exists so the accepted T20 graph validation can re-prove that a Flow Message or Flow Reward
     * node really does reference a component of the capability it requires; it is never staged, never a
     * Proposal operation and never a Campaign truth.
     */
    private List<ResolvedComponent> authoringComponents(
            JourneyGraphDraft draft, List<JourneyNodeComponent> nodes, T19PackIndex packIndex) {
        List<ResolvedComponent> components = new ArrayList<>(nodes.size() + draft.references().size());
        for (JourneyNodeComponent node : nodes) {
            components.add(componentOf(node));
        }
        for (JourneyReferenceDeclaration reference : draft.references()) {
            T19PackFacts facts = packIndex.facts(reference.capabilityCode()).orElse(null);
            if (facts == null) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.REFERENCE_INVALID);
            }
            components.add(new ResolvedComponent(
                    reference.ref(),
                    reference.capabilityCode(),
                    facts.contractVersion(),
                    facts.slot(),
                    CapabilityPayload.empty(),
                    List.of()));
        }
        return List.copyOf(components);
    }

    private static ResolvedComponent componentOf(JourneyNodeComponent node) {
        CapabilityPackContract pack = flowPack(node.capabilityCode());
        if (pack == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_CAPABILITY_UNKNOWN);
        }
        return new ResolvedComponent(
                node.ref(), node.capabilityCode(), pack.contractVersion(), pack.slot(),
                node.normalized(), List.of());
    }

    private static CapabilityPackContract flowPack(String capabilityCode) {
        for (CapabilityPackContract pack : FlowCapabilityPacks.all()) {
            if (pack.capabilityCode().equals(capabilityCode)) {
                return pack;
            }
        }
        return null;
    }

    private static JourneyNodeKind kindOf(String capabilityCode) {
        for (JourneyNodeKind kind : JourneyNodeKind.values()) {
            if (kind.capabilityCode().equals(capabilityCode)) {
                return kind;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ topology and simulation

    private static JourneyTopology topologyOf(JourneyGraphDraft draft) {
        List<JourneyTopologyEdge> edges = new ArrayList<>(draft.edges().size());
        for (JourneyEdgeDeclaration edge : draft.edges()) {
            edges.add(new JourneyTopologyEdge(
                    edge.edgeId(), edge.fromRef(), edge.toRef(), branchOf(edge.branchName())));
        }
        return JourneyTopology.of(JourneyTopology.TOPOLOGY_FORMAT_VERSION, edges);
    }

    private static com.company.campaign.journey.graph.JourneyBranchKind branchOf(String branchName) {
        com.company.campaign.journey.graph.JourneyBranchKind branch =
                com.company.campaign.journey.graph.JourneyBranchKind.fromWireName(branchName);
        if (branch == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_INVALID);
        }
        return branch;
    }

    private static ScenarioFacts scenarioFacts(JourneySimulationInput input) {
        List<ScenarioFact> facts = new ArrayList<>(input.facts().size());
        for (JourneyScenarioFact fact : input.facts()) {
            try {
                facts.add(switch (fact.outcome()) {
                    case JourneyScenarioFact.KNOWN ->
                            ScenarioFact.known(fact.factCode(), fact.valueType(), fact.value());
                    case JourneyScenarioFact.UNKNOWN -> ScenarioFact.unknown(fact.factCode());
                    default -> ScenarioFact.error(fact.factCode(), fact.reasonCode());
                });
            } catch (RuntimeException rejected) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
            }
        }
        try {
            return ScenarioFacts.of(facts);
        } catch (RuntimeException rejected) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
    }

    private static JourneySimulationSummary summaryOf(SimulationResult result, Instant start) {
        List<String> statuses = new ArrayList<>(result.paths().size());
        for (SimulatedPath path : result.paths()) {
            statuses.add(path.status().name());
        }
        List<String> issueCodes = new ArrayList<>(result.issues().size());
        for (Issue issue : result.issues()) {
            issueCodes.add(issue.code());
        }
        return new JourneySimulationSummary(
                result.environment(),
                result.simulatorVersion(),
                start.toString(),
                result.virtualTimezone().getId(),
                result.paths().size(),
                statuses,
                issueCodes,
                result.scenarioHash(),
                result.contentHash());
    }

    /**
     * The bounded, deterministic issue codes of one failed compilation.
     *
     * <p>The codes are a closed machine vocabulary; no payload value, identity or message ever leaves the
     * adapter through them.
     */
    private static List<String> issueCodes(List<Issue> issues) {
        TreeSet<String> codes = new TreeSet<>();
        for (Issue issue : issues) {
            if (issue != null && issue.code() != null) {
                codes.add(issue.code());
            }
        }
        return List.copyOf(codes);
    }

    /** A bounded diagnostic: the closed catalog size, never a declaration value. */
    @Override
    public String toString() {
        return "JourneyGraphAdapter[capabilities=" + FlowPackVersions.CAPABILITY_CODES.size() + ']';
    }
}