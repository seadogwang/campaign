package com.company.campaign.builder.proposal;

import com.company.campaign.journey.graph.JourneyTopology;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one strict, versioned Journey-topology mutation of a Campaign authoring request.
 *
 * <p>It is a closed top-level proposal value: not a capability payload, not raw JSON, not an unrestricted map
 * and not a second endpoint. A request that carries no mutation inherits the current topology and never clears
 * it; {@link JourneyTopologyAction#REPLACE} supplies one complete bounded draft; and
 * {@link JourneyTopologyAction#REMOVE} is the only clear path, valid only when the resulting Campaign holds no
 * Flow node at all.
 *
 * <p>Construction fails closed on an absent action, a replacement whose format version is not the one supported
 * version, an over-bound or empty replacement edge set, a repeated edge identity, a repeated semantic edge and an
 * edge that is not a bounded typed declaration. The accepted replacement edge set is then normalised into one
 * documented deterministic order, so the caller's collection order can never reach the canonical bytes. A
 * caller cannot supply a topology hash, a compiler identity, a final derived logical identifier, a Manifest/Plan
 * field, trusted context, a revision, a runtime fact, a Provider fact or a lifecycle action: none of those is
 * representable here.
 */
public record JourneyTopologyMutation(
        JourneyTopologyAction action,
        String topologyFormatVersion,
        List<ProposalTopologyEdge> edges) {

    public JourneyTopologyMutation {
        Objects.requireNonNull(action, "action");
        switch (action) {
            case REPLACE -> {
                if (!JourneyTopology.TOPOLOGY_FORMAT_VERSION.equals(topologyFormatVersion)) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_VERSION_INVALID);
                }
                if (edges == null || edges.isEmpty() || edges.size() > JourneyTopology.MAX_EDGES) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_LIMIT_EXCEEDED);
                }
                edges = canonicalEdges(edges);
            }
            case REMOVE -> {
                if (topologyFormatVersion != null) {
                    // A removal names no format: it is the absence of a topology, not a topology of another
                    // version, and reinterpreting a version here would silently accept contradictory material.
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
                }
                if (edges != null && !edges.isEmpty()) {
                    throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
                }
                edges = List.of();
            }
        }
    }

    /** A complete replacement topology draft of the one supported format version. */
    public static JourneyTopologyMutation replace(List<ProposalTopologyEdge> edges) {
        return new JourneyTopologyMutation(
                JourneyTopologyAction.REPLACE, JourneyTopology.TOPOLOGY_FORMAT_VERSION, edges);
    }

    /** The explicit removal of the Campaign's current topology. */
    public static JourneyTopologyMutation remove() {
        return new JourneyTopologyMutation(JourneyTopologyAction.REMOVE, null, List.of());
    }

    /** True when this mutation supplies a complete replacement topology. */
    public boolean replaces() {
        return action == JourneyTopologyAction.REPLACE;
    }

    /** True when this mutation explicitly removes the current topology. */
    public boolean removes() {
        return action == JourneyTopologyAction.REMOVE;
    }

    private static List<ProposalTopologyEdge> canonicalEdges(List<ProposalTopologyEdge> edges) {
        List<ProposalTopologyEdge> copy = new ArrayList<>(edges.size());
        Set<String> identities = new HashSet<>();
        Set<String> semantics = new HashSet<>();
        for (ProposalTopologyEdge edge : edges) {
            Objects.requireNonNull(edge, "edge");
            if (!identities.add(edge.edgeId())) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_DUPLICATE_EDGE);
            }
            if (!semantics.add(semanticKey(edge))) {
                throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_DUPLICATE_EDGE);
            }
            copy.add(edge);
        }
        // Duplicate detection above is order-independent and always runs before this sort, so the fail-closed
        // rule never depends on the order the caller supplied; only then is the meaning-free collection order
        // normalised into the one documented total order.
        copy.sort(JourneyTopologyMutation::order);
        return List.copyOf(copy);
    }

    /**
     * The one documented total order of a replacement edge set: the typed identity of the source endpoint, the
     * canonical branch rank, the typed identity of the destination endpoint, then the edge identity.
     *
     * <p>Edge collection order carries no semantic meaning, so two mutations that describe the same typed edges
     * in a different input order are normalised into the same order here and thereby produce byte-identical
     * canonical Proposal text and hash. It mirrors the accepted T20 topology order, which is source, branch
     * rank, destination, then edge identity, extended with the typed endpoint form because an authoring
     * endpoint is either an existing {@code logicalId} or a declared {@code clientRef}.
     */
    private static int order(ProposalTopologyEdge left, ProposalTopologyEdge right) {
        int from = endpointKey(left.from()).compareTo(endpointKey(right.from()));
        if (from != 0) {
            return from;
        }
        int branchRank = Integer.compare(left.branch().canonicalRank(), right.branch().canonicalRank());
        if (branchRank != 0) {
            return branchRank;
        }
        int to = endpointKey(left.to()).compareTo(endpointKey(right.to()));
        return to != 0 ? to : left.edgeId().compareTo(right.edgeId());
    }

    private static String semanticKey(ProposalTopologyEdge edge) {
        return endpointKey(edge.from()) + '|' + endpointKey(edge.to()) + '|' + edge.branch().name();
    }

    private static String endpointKey(ProposalIdentity identity) {
        return (identity.isLogicalId() ? "L:" : "C:") + identity.value();
    }

    /** A bounded diagnostic: the action and the edge count only, never an endpoint value. */
    @Override
    public String toString() {
        return "JourneyTopologyMutation[action=" + action + ", edges=" + edges.size() + ']';
    }
}