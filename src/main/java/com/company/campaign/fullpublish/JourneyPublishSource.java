package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.journey.graph.JourneyTopology;

import java.util.Objects;

/**
 * The two frozen host-side inputs one Journey compile needs beyond the saved Draft itself.
 *
 * <p>T20 deliberately keeps the graph edges out of the T19 node payloads, so the exact {@link JourneyTopology}
 * is a separate frozen input from the same authoring snapshot as the source Manifest. The
 * {@link T19PackIndex} is supplied the same way, so a pack that is disabled, that moved contract version or
 * that declares an unsupported runtime is decided against bytes that cannot change under the compile.
 *
 * <p>Both are resolved by the host. Neither is reachable from a command, a model output or a Tool payload.
 */
public record JourneyPublishSource(JourneyTopology topology, T19PackIndex packIndex) {

    public JourneyPublishSource {
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(packIndex, "packIndex");
    }

    /** Redacted diagnostics: only already-safe machine facts are printed. */
    @Override
    public String toString() {
        return "JourneyPublishSource[topologyEdges=" + topology.edges().size()
                + ", packs=" + packIndex.packs().size()
                + ", topologyHash=" + topology.topologyHash() + ']';
    }
}