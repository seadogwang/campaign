package com.company.campaign.builder.journey;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/**
 * The complete, bounded, immutable outcome of one Journey authoring validation.
 *
 * <p>A valid outcome carries the normalized node components, the declared topology edges, the entry reference,
 * the terminal references, the bounded node and edge counts and the two genuine T20 hashes the compiled graph
 * and topology produced. An invalid outcome carries no component, no entry, no terminal and no hash, and only
 * the deterministic bounded issue codes that rejected it — never a partially built graph.
 *
 * <p>Nothing here carries a template body, a reward description or secret, a member fact, a Provider response,
 * a credential, SQL, an unrestricted exception or a rejected payload value.
 */
public record JourneyValidationOutcome(
        boolean valid,
        List<JourneyNodeComponent> nodes,
        List<JourneyEdgeDeclaration> edges,
        String entryNodeRef,
        List<String> terminalNodeRefs,
        int nodeCount,
        int edgeCount,
        List<String> issueCodes,
        ContentHash journeyContentHash,
        ContentHash topologyHash) {

    public JourneyValidationOutcome {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        terminalNodeRefs = terminalNodeRefs == null ? List.of() : List.copyOf(terminalNodeRefs);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
        if (valid && (entryNodeRef == null || journeyContentHash == null || topologyHash == null)) {
            // A valid graph is exactly one that compiled: it must carry its entry and its frozen hashes.
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_INVALID);
        }
        if (!valid && (!nodes.isEmpty() || entryNodeRef != null || journeyContentHash != null
                || topologyHash != null)) {
            // An invalid graph is never partial: it must not expose a component or a hash it never proved.
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_INVALID);
        }
        if (!valid && issueCodes.isEmpty()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.GRAPH_INVALID);
        }
    }

    /** A bounded diagnostic: the decision, the counts and the issue count, never a declaration value. */
    @Override
    public String toString() {
        return "JourneyValidationOutcome[valid=" + valid
                + ", nodes=" + nodeCount
                + ", edges=" + edgeCount
                + ", issues=" + issueCodes.size() + ']';
    }
}