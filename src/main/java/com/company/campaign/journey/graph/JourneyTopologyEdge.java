package com.company.campaign.journey.graph;

import com.company.campaign.contracts.ContractPatterns;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One immutable, strictly typed edge of a {@link JourneyTopology}.
 *
 * <p>An edge names three bounded stable identifiers and exactly one closed branch kind. It carries no payload,
 * no label, no weight, no condition and no ordering priority: the branch kind is the whole of its semantics,
 * which is what lets the graph contract prove degree and branch invariants without interpreting anything.
 *
 * <p>A topology edge is compiler input from the same authoring snapshot as the source Manifest. It is not an
 * independently mutable Campaign truth and it is never stored in a T19 Flow node payload.
 */
public record JourneyTopologyEdge(
        String logicalId,
        String fromNodeLogicalId,
        String toNodeLogicalId,
        JourneyBranchKind branch) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public JourneyTopologyEdge {
        requireIdentifier(logicalId, "logicalId");
        requireIdentifier(fromNodeLogicalId, "fromNodeLogicalId");
        requireIdentifier(toNodeLogicalId, "toNodeLogicalId");
        Objects.requireNonNull(branch, "branch");
    }

    /** True when the edge begins and ends at the same node. */
    @JsonIgnore
    public boolean isSelfEdge() {
        return fromNodeLogicalId.equals(toNodeLogicalId);
    }

    /**
     * Deterministic canonical order: source, branch rank, destination, then edge identity.
     *
     * <p>The caller's collection order carries no meaning, so it is never trusted: two topologies that describe
     * the same edges in a different arrangement have the same canonical order and therefore the same bytes.
     */
    public static int order(JourneyTopologyEdge left, JourneyTopologyEdge right) {
        int from = left.fromNodeLogicalId.compareTo(right.fromNodeLogicalId);
        if (from != 0) {
            return from;
        }
        int branchRank = Integer.compare(left.branch.canonicalRank(), right.branch.canonicalRank());
        if (branchRank != 0) {
            return branchRank;
        }
        int to = left.toNodeLogicalId.compareTo(right.toNodeLogicalId);
        return to != 0 ? to : left.logicalId.compareTo(right.logicalId);
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new JourneyGraphException(
                    JourneyGraphErrorCodes.TOPOLOGY_EDGE_INVALID, field + "BoundedIdentifier");
        }
    }

    @Override
    public String toString() {
        return "JourneyTopologyEdge[logicalId=" + logicalId
                + ", from=" + fromNodeLogicalId
                + ", to=" + toNodeLogicalId
                + ", branch=" + branch + ']';
    }
}
