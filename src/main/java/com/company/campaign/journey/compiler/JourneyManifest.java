package com.company.campaign.journey.compiler;

import com.company.campaign.journey.graph.JourneyNode;
import com.company.campaign.journey.graph.JourneyTopologyEdge;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The complete, immutable, non-forgeable compiled form of one Journey.
 *
 * <p>It freezes the Journey format, compiler and runtime schema versions, the exact source Campaign manifest
 * hash and topology hash, the one Start logical identity, the canonical node records, the canonical typed
 * edges, a deterministic topological order with stable logical-identity tie break, the terminal identities and
 * a complete Journey content hash. It is a derived artifact bound to its source hashes; it is not a second
 * editable Campaign truth.
 *
 * <p>The class is final, the only constructor is package-private and there is no public factory, builder,
 * deserializer or subclass escape hatch. Construction re-proves its version discriminators and re-computes the
 * content hash from the canonical bytes, so a Manifest that does not match its own hash is not representable.
 * Collections are defensively copied and ordered, so a caller cannot mutate a compiled Journey.
 */
public final class JourneyManifest {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private final String journeyFormatVersion;
    private final String compilerVersion;
    private final String runtimeSchemaVersion;
    private final ContentHash sourceManifestHash;
    private final ContentHash topologyHash;
    private final String startNodeLogicalId;
    private final List<JourneyNode> nodes;
    private final List<JourneyTopologyEdge> edges;
    private final List<String> topologicalOrder;
    private final List<String> terminalNodeLogicalIds;
    private final ContentHash contentHash;

    /** The one production path: canonicalize the inputs, hash them and then construct. */
    static JourneyManifest create(
            String journeyFormatVersion,
            String compilerVersion,
            String runtimeSchemaVersion,
            ContentHash sourceManifestHash,
            ContentHash topologyHash,
            String startNodeLogicalId,
            List<JourneyNode> nodes,
            List<JourneyTopologyEdge> edges,
            List<String> topologicalOrder,
            List<String> terminalNodeLogicalIds) {
        List<JourneyNode> canonicalNodes = canonicalNodes(nodes);
        List<JourneyTopologyEdge> canonicalEdges = canonicalEdges(edges);
        List<String> canonicalOrder = List.copyOf(topologicalOrder);
        List<String> canonicalTerminals = List.copyOf(terminalNodeLogicalIds);
        ContentHash hash = CANONICAL_JSON.hash(new Material(
                journeyFormatVersion, compilerVersion, runtimeSchemaVersion, sourceManifestHash, topologyHash,
                startNodeLogicalId, canonicalNodes, canonicalEdges, canonicalOrder, canonicalTerminals));
        return new JourneyManifest(
                journeyFormatVersion, compilerVersion, runtimeSchemaVersion, sourceManifestHash, topologyHash,
                startNodeLogicalId, canonicalNodes, canonicalEdges, canonicalOrder, canonicalTerminals, hash);
    }

    private JourneyManifest(
            String journeyFormatVersion,
            String compilerVersion,
            String runtimeSchemaVersion,
            ContentHash sourceManifestHash,
            ContentHash topologyHash,
            String startNodeLogicalId,
            List<JourneyNode> nodes,
            List<JourneyTopologyEdge> edges,
            List<String> topologicalOrder,
            List<String> terminalNodeLogicalIds,
            ContentHash contentHash) {
        if (!JourneyVersions.JOURNEY_FORMAT_VERSION.equals(journeyFormatVersion)
                || !JourneyVersions.JOURNEY_COMPILER_VERSION.equals(compilerVersion)
                || !JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw inconsistent("journeyVersions", "A Journey manifest must carry supported versions.");
        }
        this.journeyFormatVersion = journeyFormatVersion;
        this.compilerVersion = compilerVersion;
        this.runtimeSchemaVersion = runtimeSchemaVersion;
        this.sourceManifestHash = Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
        this.topologyHash = Objects.requireNonNull(topologyHash, "topologyHash");
        if (startNodeLogicalId == null) {
            throw inconsistent("startNodeLogicalId", "A Journey manifest requires exactly one Start identity.");
        }
        this.startNodeLogicalId = startNodeLogicalId;
        this.nodes = canonicalNodes(nodes);
        this.edges = canonicalEdges(edges);
        this.topologicalOrder = List.copyOf(topologicalOrder);
        this.terminalNodeLogicalIds = List.copyOf(terminalNodeLogicalIds);
        Objects.requireNonNull(contentHash, "contentHash");
        ContentHash expected = ContentHash.sha256(CANONICAL_JSON.write(material()));
        if (!contentHash.equals(expected)) {
            throw inconsistent("contentHash", "The declared content hash is not the hash of the manifest.");
        }
        this.contentHash = contentHash;
    }

    public String journeyFormatVersion() {
        return journeyFormatVersion;
    }

    public String compilerVersion() {
        return compilerVersion;
    }

    public String runtimeSchemaVersion() {
        return runtimeSchemaVersion;
    }

    /** The exact source Campaign manifest hash this Journey was compiled from. */
    public ContentHash sourceManifestHash() {
        return sourceManifestHash;
    }

    /** The exact topology hash this Journey was compiled from. */
    public ContentHash topologyHash() {
        return topologyHash;
    }

    /** The one Start node logical identity. */
    public String startNodeLogicalId() {
        return startNodeLogicalId;
    }

    /** The canonical node records, ordered by stable logical identity. */
    public List<JourneyNode> nodes() {
        return nodes;
    }

    /** The canonical typed edges. */
    public List<JourneyTopologyEdge> edges() {
        return edges;
    }

    /** The deterministic topological node order with stable logical-identity tie break. */
    public List<String> topologicalOrder() {
        return topologicalOrder;
    }

    /** The terminal END and EXIT node identities, in deterministic order. */
    public List<String> terminalNodeLogicalIds() {
        return terminalNodeLogicalIds;
    }

    /** The complete Journey content hash, computed from strict canonical bytes. */
    public ContentHash contentHash() {
        return contentHash;
    }

    /** The canonical UTF-8 bytes every Journey semantic is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The node with the given stable identity, or null when the Journey does not own it. */
    public JourneyNode node(String logicalId) {
        for (JourneyNode node : nodes) {
            if (node.logicalId().equals(logicalId)) {
                return node;
            }
        }
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JourneyManifest manifest && contentHash.equals(manifest.contentHash);
    }

    @Override
    public int hashCode() {
        return contentHash.hashCode();
    }

    @Override
    public String toString() {
        return "JourneyManifest[formatVersion=" + journeyFormatVersion
                + ", compilerVersion=" + compilerVersion
                + ", nodes=" + nodes.size()
                + ", edges=" + edges.size()
                + ", terminals=" + terminalNodeLogicalIds.size()
                + ", contentHash=" + contentHash + ']';
    }

    private Material material() {
        return new Material(journeyFormatVersion, compilerVersion, runtimeSchemaVersion, sourceManifestHash,
                topologyHash, startNodeLogicalId, nodes, edges, topologicalOrder, terminalNodeLogicalIds);
    }

    private static List<JourneyNode> canonicalNodes(List<JourneyNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            throw inconsistent("nodes", "A Journey manifest requires its canonical node collection.");
        }
        List<JourneyNode> copy = new ArrayList<>(nodes.size());
        Set<String> ids = new HashSet<>();
        for (JourneyNode node : nodes) {
            if (node == null) {
                throw inconsistent("nodes", "A Journey manifest must not carry a null node.");
            }
            if (!ids.add(node.logicalId())) {
                throw inconsistent("duplicateNodeLogicalId", "A Journey manifest must not repeat a node identity.");
            }
            copy.add(node);
        }
        copy.sort(JourneyNode::order);
        return List.copyOf(copy);
    }

    private static List<JourneyTopologyEdge> canonicalEdges(List<JourneyTopologyEdge> edges) {
        if (edges == null) {
            throw inconsistent("edges", "A Journey manifest requires its canonical edge collection.");
        }
        List<JourneyTopologyEdge> copy = new ArrayList<>(edges.size());
        for (JourneyTopologyEdge edge : edges) {
            if (edge == null) {
                throw inconsistent("edges", "A Journey manifest must not carry a null edge.");
            }
            copy.add(edge);
        }
        copy.sort(JourneyTopologyEdge::order);
        return List.copyOf(copy);
    }

    private static JourneyCompileException inconsistent(String invariant, String message) {
        return new JourneyCompileException(JourneyCompileErrorCodes.GRAPH_INVALID, invariant, message);
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(
            String journeyFormatVersion,
            String compilerVersion,
            String runtimeSchemaVersion,
            ContentHash sourceManifestHash,
            ContentHash topologyHash,
            String startNodeLogicalId,
            List<JourneyNode> nodes,
            List<JourneyTopologyEdge> edges,
            List<String> topologicalOrder,
            List<String> terminalNodeLogicalIds) {
    }
}
