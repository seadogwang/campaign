package com.company.campaign.journey.compiler;

import com.company.campaign.journey.graph.JourneyGraphResult;
import com.company.campaign.journey.graph.JourneyGraphValidator;
import com.company.campaign.journey.graph.JourneyNode;
import com.company.campaign.journey.graph.JourneyTopologyEdge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * The deterministic, pure T20 Journey compiler.
 *
 * <p>It is a pure function of one explicit {@link JourneyCompileRequest}: the same request always yields
 * byte-identical canonical material and content hash, and any node payload, reference, version, edge endpoint,
 * branch kind, topology or source Manifest change changes the appropriate hash. It reads no repository, clock,
 * random source, ThreadLocal, model, network or Provider and holds no state between compiles.
 *
 * <p>It derives and validates the graph through {@link JourneyGraphValidator}, then freezes the canonical node
 * records, the canonical typed edges, a deterministic topological order with stable logical-identity tie break
 * and the terminal identities into a non-forgeable {@link JourneyManifest} bound to the source Manifest and
 * topology hashes. It adds no graph data to T19 payloads and mutates nothing.
 */
public final class JourneyCompiler {

    private final JourneyGraphValidator validator = new JourneyGraphValidator();

    /** Compiles one Journey from its explicit frozen inputs. */
    public JourneyCompilation compile(JourneyCompileRequest request) {
        if (request == null) {
            throw new JourneyCompileException(JourneyCompileErrorCodes.INPUT_MISSING,
                    "request", "The Journey compile request was absent.");
        }
        JourneyGraphResult graph = validator.validate(
                request.manifest(), request.topology(), request.packIndex());
        if (!graph.isValid()) {
            return new JourneyCompilation(null, graph.issues());
        }

        List<JourneyNode> nodes = graph.nodes();
        List<JourneyTopologyEdge> edges = graph.edges();
        List<String> order = topologicalOrder(nodes, edges);
        JourneyManifest manifest = JourneyManifest.create(
                JourneyVersions.JOURNEY_FORMAT_VERSION,
                request.compilerVersion(),
                request.runtimeSchemaVersion(),
                request.manifest().manifestHash(),
                request.topology().topologyHash(),
                graph.startNodeLogicalId(),
                nodes,
                edges,
                order,
                graph.terminalNodeLogicalIds());
        return new JourneyCompilation(manifest, List.of());
    }

    /**
     * The deterministic topological order over the derived node set, with stable logical-identity tie break.
     *
     * <p>Kahn's algorithm over a priority queue keyed by the stable logical identity: two graphs with the same
     * semantics produce the same order regardless of the caller's collection order, and an already-validated
     * acyclic graph guarantees every node appears exactly once.
     */
    private static List<String> topologicalOrder(
            List<JourneyNode> nodes, List<JourneyTopologyEdge> edges) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (JourneyNode node : nodes) {
            indegree.put(node.logicalId(), 0);
        }
        for (JourneyTopologyEdge edge : edges) {
            if (indegree.containsKey(edge.fromNodeLogicalId())
                    && indegree.containsKey(edge.toNodeLogicalId())) {
                adjacency.computeIfAbsent(edge.fromNodeLogicalId(), key -> new ArrayList<>())
                        .add(edge.toNodeLogicalId());
                indegree.merge(edge.toNodeLogicalId(), 1, Integer::sum);
            }
        }
        PriorityQueue<String> ready = new PriorityQueue<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String id = ready.poll();
            order.add(id);
            for (String next : adjacency.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) {
                    ready.add(next);
                }
            }
        }
        return List.copyOf(order);
    }

    @Override
    public String toString() {
        return "JourneyCompiler[versions=" + JourneyVersions.JOURNEY_FORMAT_VERSION + ']';
    }
}
