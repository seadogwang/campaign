package com.company.campaign.journey.graph;

import com.company.campaign.contracts.Issue;

import java.util.List;

/**
 * The bounded, immutable result of validating one Journey graph.
 *
 * <p>It carries the derived canonical node set, the topology edges, the one Start node identity, the terminal
 * identities and a deterministic, bounded issue list. It is valid exactly when the issue list is empty; a
 * valid result is what the compiler turns into a {@code JourneyManifest}, and an invalid one is reported as
 * issues rather than as a partially-built graph.
 */
public record JourneyGraphResult(
        List<JourneyNode> nodes,
        List<JourneyTopologyEdge> edges,
        String startNodeLogicalId,
        List<String> terminalNodeLogicalIds,
        List<Issue> issues) {

    public JourneyGraphResult {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        terminalNodeLogicalIds = List.copyOf(terminalNodeLogicalIds);
        issues = List.copyOf(issues);
    }

    /** True when no issue was reported and the graph may be compiled. */
    public boolean isValid() {
        return issues.isEmpty();
    }

    /** The node with the given stable identity, or null when the derived node set does not own it. */
    public JourneyNode node(String logicalId) {
        for (JourneyNode node : nodes) {
            if (node.logicalId().equals(logicalId)) {
                return node;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "JourneyGraphResult[nodes=" + nodes.size()
                + ", edges=" + edges.size()
                + ", start=" + startNodeLogicalId
                + ", terminals=" + terminalNodeLogicalIds.size()
                + ", issues=" + issues.size() + ']';
    }
}
