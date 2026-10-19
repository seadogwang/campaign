package com.company.campaign.journey.graph;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The strict, immutable, hash-bound topology of one Journey.
 *
 * <p>It is the single truth of the graph edges: the T19 Flow node payloads hold no edge data, and this object
 * holds nothing but the topology format version and the ordered bounded typed edge set. It carries no node
 * payload, no repository, no session field, no controller and no mutation path. It is frozen compiler input
 * from the same authoring snapshot as a {@code ResolvedCampaignManifest} and is bound to the compiler output
 * by its canonical hash.
 *
 * <p>Unknown fields are unrepresentable because the only constructor takes exactly the supported version and
 * the edge collection. Construction fails closed on a null or unsupported version, a null edge, a null edge
 * field, a duplicate edge logical identity, a duplicate semantic edge, a self-edge, an unbounded edge
 * collection and an unbounded identifier. Canonical ordering is deterministic and independent of the caller's
 * collection implementation, and the canonical bytes and hash cover every edge semantic.
 */
public final class JourneyTopology {

    /** The one supported topology format version. */
    public static final String TOPOLOGY_FORMAT_VERSION = "1.0";

    /** The largest permitted number of edges in one topology. */
    public static final int MAX_EDGES = 512;

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String topologyFormatVersion;
    private final List<JourneyTopologyEdge> edges;
    private final ContentHash topologyHash;

    private JourneyTopology(String topologyFormatVersion, List<JourneyTopologyEdge> edges) {
        if (!TOPOLOGY_FORMAT_VERSION.equals(topologyFormatVersion)) {
            throw new JourneyGraphException(
                    JourneyGraphErrorCodes.TOPOLOGY_VERSION_INVALID, "topologyFormatVersion");
        }
        if (edges == null || edges.size() > MAX_EDGES) {
            throw new JourneyGraphException(JourneyGraphErrorCodes.TOPOLOGY_EDGES_INVALID, "edges");
        }
        List<JourneyTopologyEdge> copy = new ArrayList<>(edges.size());
        Set<String> logicalIds = new HashSet<>();
        Set<String> semanticEdges = new HashSet<>();
        for (JourneyTopologyEdge edge : edges) {
            if (edge == null) {
                throw new JourneyGraphException(JourneyGraphErrorCodes.TOPOLOGY_EDGE_INVALID, "edge");
            }
            if (!logicalIds.add(edge.logicalId())) {
                throw new JourneyGraphException(
                        JourneyGraphErrorCodes.TOPOLOGY_DUPLICATE_EDGE_ID, "edgeLogicalId");
            }
            if (edge.isSelfEdge()) {
                throw new JourneyGraphException(JourneyGraphErrorCodes.TOPOLOGY_SELF_EDGE, "selfEdge");
            }
            if (!semanticEdges.add(semanticKey(edge))) {
                throw new JourneyGraphException(
                        JourneyGraphErrorCodes.TOPOLOGY_DUPLICATE_SEMANTIC_EDGE, "semanticEdge");
            }
            copy.add(edge);
        }
        copy.sort(JourneyTopologyEdge::order);
        this.topologyFormatVersion = topologyFormatVersion;
        this.edges = List.copyOf(copy);
        this.topologyHash = CANONICAL_JSON.hash(new Material(topologyFormatVersion, this.edges));
    }

    /** Builds a topology from the explicit, supported format version and its typed edge set. */
    public static JourneyTopology of(String topologyFormatVersion, List<JourneyTopologyEdge> edges) {
        Objects.requireNonNull(edges, "edges");
        return new JourneyTopology(topologyFormatVersion, edges);
    }

    /** Builds a topology from the supported format version and its typed edge array. */
    public static JourneyTopology of(String topologyFormatVersion, JourneyTopologyEdge... edges) {
        Objects.requireNonNull(edges, "edges");
        return new JourneyTopology(topologyFormatVersion, List.of(edges));
    }

    /** The topology format version. */
    public String topologyFormatVersion() {
        return topologyFormatVersion;
    }

    /** Every edge, in deterministic canonical order. */
    public List<JourneyTopologyEdge> edges() {
        return edges;
    }

    /** The canonical topology hash, computed from strict canonical bytes that cover every edge semantic. */
    public ContentHash topologyHash() {
        return topologyHash;
    }

    /** The canonical UTF-8 bytes every edge semantic is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(new Material(topologyFormatVersion, edges));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JourneyTopology topology && topologyHash.equals(topology.topologyHash);
    }

    @Override
    public int hashCode() {
        return topologyHash.hashCode();
    }

    @Override
    public String toString() {
        return "JourneyTopology[formatVersion=" + topologyFormatVersion
                + ", edges=" + edges.size()
                + ", hash=" + topologyHash + ']';
    }

    private static String semanticKey(JourneyTopologyEdge edge) {
        return edge.fromNodeLogicalId() + '|' + edge.toNodeLogicalId() + '|' + edge.branch().name();
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(String topologyFormatVersion, List<JourneyTopologyEdge> edges) {
    }
}
