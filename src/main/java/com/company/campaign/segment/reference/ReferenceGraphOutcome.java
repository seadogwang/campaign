package com.company.campaign.segment.reference;

import java.util.List;

/**
 * The complete result of one reference-graph resolution: the deterministic graph and every finding.
 *
 * <p>The graph is always present — for a document with no references it is the one empty graph, whose hash is
 * still a stable value — so a caller never has to branch on null. Findings are ordered by discovery, which is
 * deterministic because the traversal order is deterministic, and each one is bound to the stable node
 * identity of the site it belongs to.
 */
public record ReferenceGraphOutcome(ReferenceGraph graph, List<ReferenceGraphIssue> issues) {

    public ReferenceGraphOutcome {
        if (graph == null) {
            throw ReferenceException.invalid("A reference graph outcome requires its resolved graph");
        }
        if (issues == null) {
            throw ReferenceException.invalid("A reference graph outcome requires its finding list");
        }
        issues = List.copyOf(issues);
    }

    /** True when no finding blocks the document. */
    public boolean clean() {
        return issues.isEmpty();
    }
}