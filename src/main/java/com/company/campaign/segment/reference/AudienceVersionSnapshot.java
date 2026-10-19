package com.company.campaign.segment.reference;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Immutable host-owned catalog material for exactly one Audience Version.
 *
 * <p>It is the only source of trusted facts about a reference. Nothing here is ever read from the DSL, from a
 * model payload or from a client flag, and nothing here is editable: an instance describes one frozen version
 * identified by its exact content hash.
 *
 * <p>It carries the owning tenant and program, the immutable version identity and content hash, the root
 * subject, the canonical AST and Semantic Query Plan hashes the version was published with, the effective
 * DataScope it was published under, its direct reference dependencies, the hash of its own dependency graph,
 * its publication instant, its current availability and the expanded complexity it contributes.
 *
 * <p>The direct dependency list is held in strictly ascending audience-then-version order and repeats no
 * identity, so the same semantic graph always serialises and hashes identically. A version that lists itself
 * is rejected at construction: a directly self-referential version cannot be published material.
 *
 * <p>Two snapshots with the same version identity but different content are different values, so a same-ID
 * changed-bytes conflict is observable rather than hidden by an identity comparison.
 */
public record AudienceVersionSnapshot(
        String tenantId,
        String programId,
        AudienceVersionRef version,
        SubjectKind subject,
        ContentHash canonicalAstHash,
        ContentHash semanticQueryPlanHash,
        EffectiveDataScope effectiveDataScope,
        List<AudienceVersionRef> directDependencies,
        ContentHash dependencyGraphHash,
        Instant publishedAt,
        AudienceVersionAvailability availability,
        int expandedComplexity
) {

    private static final Pattern REFERENCE_ID = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public AudienceVersionSnapshot {
        tenantId = requireId(tenantId, "tenantId");
        programId = requireId(programId, "programId");
        if (version == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its exact version pin");
        }
        if (subject == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its root subject");
        }
        if (canonicalAstHash == null || semanticQueryPlanHash == null) {
            throw ReferenceException.invalid(
                    "An audience version snapshot requires its canonical AST and Semantic Query Plan hashes");
        }
        if (effectiveDataScope == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its effective DataScope");
        }
        if (dependencyGraphHash == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its dependency graph hash");
        }
        if (publishedAt == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its publication instant");
        }
        if (availability == null) {
            throw ReferenceException.invalid("An audience version snapshot requires its availability state");
        }
        if (expandedComplexity < 1) {
            throw ReferenceException.invalid(
                    "An audience version snapshot requires a positive expanded complexity contribution");
        }
        directDependencies = canonicalDependencies(directDependencies, version);
    }

    /** True only when this version is published and usable for resolution and execution. */
    public boolean usable() {
        return availability.isUsable();
    }

    private static List<AudienceVersionRef> canonicalDependencies(
            List<AudienceVersionRef> dependencies, AudienceVersionRef version) {
        if (dependencies == null || dependencies.isEmpty()) {
            return List.of();
        }
        List<AudienceVersionRef> ordered = new ArrayList<>(dependencies.size());
        for (AudienceVersionRef dependency : dependencies) {
            if (dependency == null) {
                throw ReferenceException.invalid("A direct dependency must be an exact version pin");
            }
            if (dependency.sameIdentity(version)) {
                throw ReferenceException.invalid("A version must not declare itself as its own dependency");
            }
            ordered.add(dependency);
        }
        ordered.sort(AudienceVersionRef.IDENTITY_ORDER);
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).sameIdentity(ordered.get(index))) {
                throw ReferenceException.invalid("A version must not repeat one direct dependency identity");
            }
        }
        return List.copyOf(ordered);
    }

    private static String requireId(String value, String field) {
        if (value == null || !REFERENCE_ID.matcher(value).matches()) {
            throw ReferenceException.invalid("A bounded '" + field + "' identifier is required");
        }
        return value;
    }

    @Override
    public String toString() {
        return "AudienceVersionSnapshot[version=" + version.identityKey()
                + ", availability=" + availability
                + ", directDependencyCount=" + directDependencies.size()
                + ", expandedComplexity=" + expandedComplexity
                + ']';
    }
}