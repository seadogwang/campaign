package com.company.campaign.journey.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T20 section-1 tests: the single-truth topology boundary, canonical order and hash. */
class JourneyTopologyTest {

    private static JourneyTopologyEdge edge(String id, String from, String to, JourneyBranchKind branch) {
        return new JourneyTopologyEdge(id, from, to, branch);
    }

    @Test
    void canonicalOrderIsIndependentOfCallerCollectionOrder() {
        JourneyTopologyEdge first = edge("edge-a", "start", "message", JourneyBranchKind.DEFAULT);
        JourneyTopologyEdge second = edge("edge-b", "message", "end", JourneyBranchKind.DEFAULT);

        JourneyTopology forwards = JourneyTopology.of("1.0", List.of(first, second));
        JourneyTopology backwards = JourneyTopology.of("1.0", List.of(second, first));

        assertThat(forwards.edges()).isEqualTo(backwards.edges());
        assertThat(forwards.edges()).containsExactlyInAnyOrder(first, second);
        assertThat(forwards.topologyHash()).isEqualTo(backwards.topologyHash());
        assertThat(forwards.canonicalBytes()).isEqualTo(backwards.canonicalBytes());
    }

    @Test
    void branchKindIsCoveredByTheCanonicalHash() {
        JourneyTopology a = JourneyTopology.of("1.0", edge("edge-a", "c", "x", JourneyBranchKind.TRUE));
        JourneyTopology b = JourneyTopology.of("1.0", edge("edge-a", "c", "x", JourneyBranchKind.FALSE));
        assertThat(a.topologyHash()).isNotEqualTo(b.topologyHash());
    }

    @Test
    void duplicateEdgeIdentityIsRefused() {
        assertThatThrownBy(() -> JourneyTopology.of("1.0",
                edge("edge-a", "start", "a", JourneyBranchKind.DEFAULT),
                edge("edge-a", "a", "end", JourneyBranchKind.DEFAULT)))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_DUPLICATE_EDGE_ID);
    }

    @Test
    void duplicateSemanticEdgeIsRefused() {
        assertThatThrownBy(() -> JourneyTopology.of("1.0",
                edge("edge-a", "start", "a", JourneyBranchKind.DEFAULT),
                edge("edge-b", "start", "a", JourneyBranchKind.DEFAULT)))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_DUPLICATE_SEMANTIC_EDGE);
    }

    @Test
    void selfEdgeIsRefused() {
        assertThatThrownBy(() -> JourneyTopology.of("1.0",
                edge("edge-a", "a", "a", JourneyBranchKind.DEFAULT)))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_SELF_EDGE);
    }

    @Test
    void unsupportedVersionAndNullEdgeAreRefused() {
        assertThatThrownBy(() -> JourneyTopology.of("2.0", List.of()))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_VERSION_INVALID);
        assertThatThrownBy(() -> JourneyTopology.of("1.0", (JourneyTopologyEdge[]) null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unboundedIdentifierIsRefused() {
        assertThatThrownBy(() -> edge("has spaces", "start", "end", JourneyBranchKind.DEFAULT))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_EDGE_INVALID);
    }

    @Test
    void moreThan512EdgesIsRefused() {
        JourneyTopologyEdge[] edges = new JourneyTopologyEdge[513];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = edge("edge-" + i, "node-" + i, "node-" + (i + 1), JourneyBranchKind.DEFAULT);
        }
        assertThatThrownBy(() -> JourneyTopology.of("1.0", edges))
                .isInstanceOf(JourneyGraphException.class)
                .hasFieldOrPropertyWithValue("code", JourneyGraphErrorCodes.TOPOLOGY_EDGES_INVALID);
    }
}
