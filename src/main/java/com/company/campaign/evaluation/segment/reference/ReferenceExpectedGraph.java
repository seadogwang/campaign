package com.company.campaign.evaluation.segment.reference;

import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The frozen dependency graph one clean reference-corpus document must produce.
 *
 * <p>It is a <em>wire</em> record rather than a reuse of the reference core's own
 * {@link com.company.campaign.segment.reference.ReferenceGraph}, for the same reason {@link ReferencePin} is:
 * the corpus tree must never depend on the core's Java type identity for its bytes, and the expectation may
 * state only what a reader can check without re-deriving the core's snapshots.
 *
 * <p>The expectation therefore states the distinct referenced versions exactly as the graph accounts for them
 * — each exact pin in the graph's canonical audience-then-version order — and the graph facts that depend on
 * the whole list: how many distinct versions it depends on, the deepest discovery depth, the summed expanded
 * complexity and the deterministic graph hash. Stating a count or a depth its own version list contradicts is
 * refused, so a corpus can never pin a graph whose shape and whose summary disagree.
 *
 * <p>The graph is present exactly for a {@link ReferenceOutcome#CLEAN} document, which is what
 * {@link ReferenceCorpusExpected} enforces: an expectation that omits the graph of a clean resolution, or
 * that carries one for a refusal, is a corpus defect rather than an unchecked claim.
 *
 * @param versions          the distinct referenced versions, in the graph's canonical order
 * @param distinctVersions  how many distinct referenced versions the graph depends on
 * @param maxDepth          the deepest discovery depth the graph reached, counted from the document root as 1
 * @param expandedComplexity the summed expanded complexity the graph contributes
 * @param graphHash         the deterministic hash of exactly that material
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReferenceExpectedGraph(
        List<ReferencePin> versions,
        int distinctVersions,
        int maxDepth,
        int expandedComplexity,
        ContentHash graphHash
) {

    public ReferenceExpectedGraph {
        if (versions == null || versions.isEmpty()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A frozen reference graph must state the distinct versions the document depends on");
        }
        List<ReferencePin> declared = new ArrayList<>(versions.size());
        Set<ReferencePin> seen = new LinkedHashSet<>();
        for (ReferencePin version : versions) {
            if (version == null) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                        "A frozen reference graph must not carry an unnamed version");
            }
            if (!seen.add(version)) {
                throw new ReferenceCorpusException(
                        ReferenceCorpusErrorCodes.EXPECTATION_OVER_SPECIFIED,
                        "A frozen reference graph must state each distinct pinned version exactly once",
                        Map.of("version", version.identityKey()));
            }
            declared.add(version);
        }
        versions = List.copyOf(declared);
        if (distinctVersions != versions.size()) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A frozen reference graph states a distinct-version count its own version list contradicts",
                    Map.of("declared", distinctVersions, "actual", versions.size()));
        }
        if (maxDepth < 1) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A frozen reference graph must state the positive depth its references were discovered at");
        }
        if (expandedComplexity < 0) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A frozen reference graph must state a non-negative expanded complexity");
        }
        if (graphHash == null) {
            throw new ReferenceCorpusException(
                    ReferenceCorpusErrorCodes.EXPECTATION_INCOMPLETE,
                    "A frozen reference graph must state its deterministic graph hash");
        }
    }

    /** A bounded diagnostic naming the graph's shape, never a referenced version's payload. */
    @Override
    public String toString() {
        return "ReferenceExpectedGraph[distinctVersions=" + distinctVersions
                + ", maxDepth=" + maxDepth
                + ", expandedComplexity=" + expandedComplexity
                + ", graphHash=" + graphHash
                + ']';
    }
}