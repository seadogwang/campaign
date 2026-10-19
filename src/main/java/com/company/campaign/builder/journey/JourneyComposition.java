package com.company.campaign.builder.journey;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.List;

/**
 * The complete, bounded, immutable outcome of one Journey node composition.
 *
 * <p>Every declared node was normalized by its accepted T19 Flow pack, so this carries exactly the canonical
 * node payloads and their references. It is explicitly <em>not</em> a Journey: composition proves nothing about
 * the graph, so the composition is never stageable and never a substitute for validation.
 */
public record JourneyComposition(
        List<JourneyNodeComponent> nodes,
        int edgeCount,
        ContentHash compositionHash) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public JourneyComposition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        if (edgeCount < 0) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_LIMIT_EXCEEDED);
        }
        if (compositionHash == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.NODE_INVALID);
        }
    }

    /** Publishes a composition, hashing exactly the observable normalized material. */
    public static JourneyComposition of(List<JourneyNodeComponent> nodes, int edgeCount) {
        List<JourneyNodeComponent> copy = nodes == null ? List.of() : List.copyOf(new ArrayList<>(nodes));
        ContentHash hash = CANONICAL_JSON.hash(new Material(copy, edgeCount));
        return new JourneyComposition(copy, edgeCount, hash);
    }

    /** A bounded diagnostic: the counts and the composition hash, never a payload value. */
    @Override
    public String toString() {
        return "JourneyComposition[nodes=" + nodes.size()
                + ", edges=" + edgeCount
                + ", compositionHash=" + compositionHash + ']';
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(List<JourneyNodeComponent> nodes, int edgeCount) {
    }
}