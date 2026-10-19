package com.company.campaign.segment.reference;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The deterministic dependency graph of one validated document's Existing Audience References.
 *
 * <p>Every distinct referenced version appears exactly once, in ascending audience-then-version order, with
 * its exact pin, its frozen catalog facts, its trusted effective-scope hash and the depth it was discovered
 * at. Duplicate reference sites are de-duplicated here for dependency accounting while remaining structurally
 * present in the editable and canonical trees, so "how many nodes say it" and "how many versions are
 * depended on" are never confused.
 *
 * <p>{@code graphHash} is a canonical SHA-256 over exactly that material, produced through the one T01
 * {@link CanonicalJson}/{@link ContentHash} pair rather than a second hashing primitive. Because the version
 * list is canonically ordered, the same semantic graph always produces byte-identical material — so a
 * reordered AST, a repeated site, a renamed node or a changed discovery order cannot move the hash, while any
 * change to a pinned version, its content hash, its catalog hashes, its effective scope, its dependency
 * hashes or its complexity contribution does.
 *
 * <p>It is a proof, not a container: construction re-derives the hash from the material and refuses a
 * contradiction, so no caller can pin a graph whose hash disagrees with its own contents.
 */
public record ReferenceGraph(
        String graphFormatVersion,
        List<ResolvedAudienceReference> versions,
        ContentHash graphHash,
        int distinctVersions,
        int maxDepth,
        int expandedComplexity
) {

    /** The canonical reference-graph document format version. */
    public static final String GRAPH_FORMAT_VERSION = "segment-audience-reference-graph/1.0";

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReferenceGraph {
        if (!GRAPH_FORMAT_VERSION.equals(graphFormatVersion)) {
            throw ReferenceException.invalid("A reference graph must carry the one supported format version");
        }
        if (versions == null) {
            throw ReferenceException.invalid("A reference graph requires its resolved version list");
        }
        List<ResolvedAudienceReference> ordered = canonicalVersions(versions);
        if (graphHash == null) {
            throw ReferenceException.invalid("A reference graph requires its deterministic graph hash");
        }
        ContentHash expected = hash(ordered, distinctVersions, maxDepth, expandedComplexity);
        if (!graphHash.equals(expected)) {
            throw ReferenceException.invalid("The declared reference graph hash is not the material's hash");
        }
        versions = ordered;
    }

    /** Builds the one graph for an already resolved, de-duplicated version list. */
    static ReferenceGraph of(List<ResolvedAudienceReference> versions) {
        List<ResolvedAudienceReference> ordered = canonicalVersions(versions);
        int maxDepth = 0;
        int complexity = 0;
        for (ResolvedAudienceReference resolved : ordered) {
            maxDepth = Math.max(maxDepth, resolved.depth());
            complexity = Math.addExact(complexity, resolved.snapshot().expandedComplexity());
        }
        return new ReferenceGraph(
                GRAPH_FORMAT_VERSION,
                ordered,
                hash(ordered, ordered.size(), maxDepth, complexity),
                ordered.size(),
                maxDepth,
                complexity);
    }

    /** True when this graph depends on no version at all. */
    public boolean isEmpty() {
        return versions.isEmpty();
    }

    private static List<ResolvedAudienceReference> canonicalVersions(List<ResolvedAudienceReference> versions) {
        if (versions == null) {
            throw ReferenceException.invalid("A reference graph requires its resolved version list");
        }
        List<ResolvedAudienceReference> ordered = new ArrayList<>(versions.size());
        for (ResolvedAudienceReference resolved : versions) {
            if (resolved == null) {
                throw ReferenceException.invalid("A reference graph must not carry a null resolved version");
            }
            ordered.add(resolved);
        }
        ordered.sort(java.util.Comparator.comparing(
                resolved -> resolved.reference(), AudienceVersionRef.ORDER));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).reference().equals(ordered.get(index).reference())) {
                throw ReferenceException.invalid("A reference graph must not repeat one referenced version");
            }
        }
        return List.copyOf(ordered);
    }

    private static ContentHash hash(
            List<ResolvedAudienceReference> ordered, int distinctVersions, int maxDepth, int complexity) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("graphFormatVersion", GRAPH_FORMAT_VERSION);
        material.put("distinctVersions", distinctVersions);
        material.put("maxDepth", maxDepth);
        material.put("expandedComplexity", complexity);
        List<Object> entries = new ArrayList<>(ordered.size());
        for (ResolvedAudienceReference resolved : ordered) {
            entries.add(entry(resolved));
        }
        material.put("versions", entries);
        return CANONICAL_JSON.hash(material);
    }

    private static Map<String, Object> entry(ResolvedAudienceReference resolved) {
        AudienceVersionSnapshot snapshot = resolved.snapshot();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("audienceId", resolved.reference().audienceId());
        entry.put("versionId", resolved.reference().versionId());
        entry.put("versionHash", resolved.reference().versionHash().value());
        entry.put("subject", snapshot.subject().name());
        entry.put("canonicalAstHash", snapshot.canonicalAstHash().value());
        entry.put("semanticQueryPlanHash", snapshot.semanticQueryPlanHash().value());
        entry.put("dependencyGraphHash", snapshot.dependencyGraphHash().value());
        entry.put("effectiveDataScopeId", resolved.effectiveDataScope().dataScopeId());
        entry.put("effectiveDataScopeHash", resolved.effectiveDataScope().dataScopeHash().value());
        entry.put("expandedComplexity", snapshot.expandedComplexity());
        entry.put("depth", resolved.depth());
        List<Object> dependencies = new ArrayList<>(snapshot.directDependencies().size());
        for (AudienceVersionRef dependency : snapshot.directDependencies()) {
            Map<String, Object> pin = new LinkedHashMap<>();
            pin.put("audienceId", dependency.audienceId());
            pin.put("versionId", dependency.versionId());
            pin.put("versionHash", dependency.versionHash().value());
            dependencies.add(pin);
        }
        entry.put("directDependencies", dependencies);
        return entry;
    }

    @Override
    public String toString() {
        return "ReferenceGraph[distinctVersions=" + distinctVersions
                + ", maxDepth=" + maxDepth
                + ", expandedComplexity=" + expandedComplexity
                + ", graphHash=" + graphHash
                + ']';
    }
}