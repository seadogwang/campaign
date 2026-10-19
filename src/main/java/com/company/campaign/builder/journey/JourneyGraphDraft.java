package com.company.campaign.builder.journey;

import com.company.campaign.journey.graph.JourneyTopology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One complete, bounded, immutable Journey authoring draft.
 *
 * <p>It is the typed shape every Journey Tool accepts: node declarations, topology edge declarations and the
 * declared cross-component reference targets. There is no unrestricted map, no raw JSON and no callback, and
 * the bounds are the accepted T20 node and edge maxima, so a draft cannot describe a graph the accepted graph
 * contract could never hold.
 *
 * <p>Construction fails closed on an empty node set, an over-bound node, edge or reference set, a repeated node,
 * edge or reference identity, an edge that names an undeclared node and a reference that collides with a node
 * declaration. It reads no repository, clock, random source, model, network or Provider.
 */
public record JourneyGraphDraft(
        List<JourneyNodeDeclaration> nodes,
        List<JourneyEdgeDeclaration> edges,
        List<JourneyReferenceDeclaration> references) {

    /** The largest number of declared nodes: the accepted T20 hard node bound. */
    public static final int MAX_NODES = 256;

    /** The largest number of declared edges: the accepted T20 hard topology edge bound. */
    public static final int MAX_EDGES = JourneyTopology.MAX_EDGES;

    /** The largest number of declared cross-component reference targets. */
    public static final int MAX_REFERENCES = 256;

    public JourneyGraphDraft {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_EMPTY);
        }
        if (nodes.size() > MAX_NODES) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_LIMIT_EXCEEDED);
        }
        Set<String> nodeRefs = new HashSet<>();
        for (JourneyNodeDeclaration node : nodes) {
            if (!nodeRefs.add(node.ref())) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_DUPLICATE);
            }
        }
        edges = edges == null ? List.of() : List.copyOf(edges);
        if (edges.size() > MAX_EDGES) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_LIMIT_EXCEEDED);
        }
        Set<String> edgeIds = new HashSet<>();
        for (JourneyEdgeDeclaration edge : edges) {
            if (!edgeIds.add(edge.edgeId())) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_DUPLICATE);
            }
            if (!nodeRefs.contains(edge.fromRef()) || !nodeRefs.contains(edge.toRef())) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.EDGE_ENDPOINT_UNKNOWN);
            }
        }
        references = references == null ? List.of() : List.copyOf(references);
        if (references.size() > MAX_REFERENCES) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_LIMIT_EXCEEDED);
        }
        Set<String> referenceRefs = new HashSet<>();
        for (JourneyReferenceDeclaration reference : references) {
            if (!referenceRefs.add(reference.ref())) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.REFERENCE_DUPLICATE);
            }
            if (nodeRefs.contains(reference.ref())) {
                throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.REFERENCE_CONFLICT);
            }
        }
    }

    /** The declared node references, in declaration order. */
    public List<String> nodeRefs() {
        List<String> refs = new ArrayList<>(nodes.size());
        for (JourneyNodeDeclaration node : nodes) {
            refs.add(node.ref());
        }
        return List.copyOf(refs);
    }

    /** Redacted diagnostics: the bounded counts only, never a declaration value. */
    @Override
    public String toString() {
        return "JourneyGraphDraft[nodes=" + nodes.size()
                + ", edges=" + edges.size()
                + ", references=" + references.size() + ']';
    }
}