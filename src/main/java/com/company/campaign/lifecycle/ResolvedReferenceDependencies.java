package com.company.campaign.lifecycle;

import com.company.campaign.builder.compiler.ReferencePlanEvidence;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The exact transitive referenced Audience Versions one production path re-resolved for a compiled Plan.
 *
 * <p>It is the answer a host-owned resolution port gives at Prepare and re-gives at Publish: the dedicated
 * reference identity, the canonical hash of the resolved dependency graph, and the exact immutable version
 * pins that graph depends on. Nothing here is a mutable pointer — every pin names one immutable version by
 * audience, version and content hash — so a production path can freeze a dependency hash, compare it with the
 * plan's own frozen reference evidence, and later acquire one retention hold per exact pin.
 *
 * <p>The version list is canonical and duplicate-free: ordered by audience, then version, then content hash,
 * so the same semantic dependency closure always produces the same list regardless of the order the resolver
 * discovered it in, and one version can never be counted or held twice. De-duplication here is by the full
 * immutable pin, never by mutable identity: two pins that share an audience and version ID but differ in
 * content hash are two distinct versions.
 *
 * <p>It carries no owner, scope, permission, member set or DSL material. It is a bounded host fact and never
 * a caller- or model-supplied value, so a client cannot declare, widen or replace what a plan depends on.
 */
public record ResolvedReferenceDependencies(
        String capabilityId,
        ContentHash dependenciesHash,
        List<AudienceVersionRef> versions
) {

    public ResolvedReferenceDependencies {
        if (!ReferencePlanEvidence.CAPABILITY_ID.equals(capabilityId)) {
            // A resolution answer is only meaningful for the one dedicated reference capability identity.
            throw invalid("resolvedReferenceCapability");
        }
        if (dependenciesHash == null) {
            throw invalid("resolvedReferenceDependenciesHash");
        }
        versions = canonicalVersions(versions);
    }

    /** True when the resolved closure depends on no referenced version at all. */
    public boolean isEmpty() {
        return versions.isEmpty();
    }

    /** The exact pins of one closure, in canonical order and de-duplicated by full immutable pin. */
    private static List<AudienceVersionRef> canonicalVersions(List<AudienceVersionRef> versions) {
        if (versions == null) {
            throw invalid("resolvedReferenceVersions");
        }
        List<AudienceVersionRef> ordered = new ArrayList<>(versions.size());
        Set<String> pins = new LinkedHashSet<>();
        for (AudienceVersionRef version : versions) {
            if (version == null) {
                throw invalid("resolvedReferenceVersions");
            }
            // The full pin key, so two versions sharing one mutable identity but differing in bytes stay two
            // distinct dependencies rather than silently collapsing into one.
            if (!pins.add(version.pinKey())) {
                continue;
            }
            ordered.add(version);
        }
        ordered.sort(AudienceVersionRef.ORDER);
        return List.copyOf(ordered);
    }

    private static LifecycleException invalid(String invariant) {
        return new LifecycleException(
                LifecycleErrorCodes.LIFECYCLE_INTERNAL_ERROR,
                invariant,
                "A resolved reference dependency answer must be a bounded, canonical, host-owned fact.");
    }

    /** A bounded diagnostic: the count of distinct exact pins and nothing else. */
    @Override
    public String toString() {
        return "ResolvedReferenceDependencies[versions=" + versions.size() + "]";
    }
}