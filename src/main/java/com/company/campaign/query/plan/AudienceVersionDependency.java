package com.company.campaign.query.plan;

import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceVersionAvailability;
import com.company.campaign.segment.reference.AudienceVersionRef;
import com.company.campaign.segment.reference.AudienceVersionSnapshot;
import com.company.campaign.segment.reference.EffectiveDataScope;
import com.company.campaign.segment.reference.ResolvedAudienceReference;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One frozen Existing Audience Reference dependency of a Semantic Query Plan.
 *
 * <p>It is the plan-level mirror of {@link ResolvedAudienceReference}: the exact immutable version pin, the
 * owning tenant and program, the referenced version's published subject, canonical AST hash, Semantic Query
 * Plan hash and DataScope, the trusted <em>effective</em> DataScope the reference is evaluated under, the
 * direct dependency pins, the version's own dependency graph hash, the retention and availability evidence
 * observed when the plan was built, and the deterministic complexity it contributes. Everything here is a
 * value the trusted resolver already proved; nothing is read from the DSL, the canonical AST or a model
 * payload, and the record has no mutable-pointer field at all.
 *
 * <p>Freezing the owning tenant/program and the version's published facts is what lets an execution guard
 * re-authorize and re-prove the very same material without re-reading Metadata or the DSL: it can compare a
 * freshly read catalog answer against these fields, and any of them moving is observable drift.
 *
 * <p>Availability and retention are frozen as <em>evidence</em> rather than as authority: they record what the
 * host ports answered at plan time so an execution guard can re-ask and detect drift. Availability is frozen
 * as the observed state, and must be usable — a plan never freezes a version it could not have used.
 * Retention is frozen as the exact ordered hold identifiers that protected the version, and an execution
 * re-check requires those holds to still be present (an additional hold is harmless; a released one is drift).
 *
 * <p>Dependencies are canonical and duplicate-free: ordered by audience, then version, then content hash, so
 * the same semantic graph always serialises and hashes identically regardless of traversal order, and a plan
 * can never carry two dependencies for one referenced version.
 */
public record AudienceVersionDependency(
        AudienceVersionRef reference,
        SubjectKind subject,
        String ownerTenantId,
        String ownerProgramId,
        ContentHash canonicalAstHash,
        ContentHash semanticQueryPlanHash,
        EffectiveDataScope publishedDataScope,
        EffectiveDataScope effectiveDataScope,
        List<AudienceVersionRef> directDependencies,
        ContentHash dependencyGraphHash,
        Instant publishedAt,
        AudienceVersionAvailability availability,
        List<String> retentionHoldIds,
        int expandedComplexity,
        int depth
) {

    /** The deterministic dependency order: audience, then version, then exact content hash. */
    public static final Comparator<AudienceVersionDependency> ORDER =
            Comparator.comparing(AudienceVersionDependency::reference, AudienceVersionRef.ORDER);

    public AudienceVersionDependency {
        if (reference == null) {
            throw inconsistent("reference", "An audience dependency requires the exact version pin it froze.");
        }
        if (subject == null) {
            throw inconsistent("subject", "An audience dependency requires the referenced version's subject.");
        }
        if (ownerTenantId == null || ownerTenantId.isBlank()
                || ownerProgramId == null || ownerProgramId.isBlank()) {
            throw inconsistent("versionOwner",
                    "An audience dependency requires the referenced version's owning tenant and program.");
        }
        if (canonicalAstHash == null || semanticQueryPlanHash == null) {
            throw inconsistent("versionHashes",
                    "An audience dependency requires the referenced version's canonical and plan hashes.");
        }
        if (publishedDataScope == null) {
            throw inconsistent("publishedDataScope",
                    "An audience dependency requires the referenced version's published DataScope.");
        }
        if (effectiveDataScope == null) {
            throw inconsistent("effectiveDataScope",
                    "An audience dependency requires its trusted effective DataScope.");
        }
        if (dependencyGraphHash == null) {
            throw inconsistent("dependencyGraphHash",
                    "An audience dependency requires the referenced version's dependency graph hash.");
        }
        if (publishedAt == null) {
            throw inconsistent("publishedAt",
                    "An audience dependency requires the referenced version's publication instant.");
        }
        if (availability == null || !availability.isUsable()) {
            // A plan never freezes a version it could not have used: an unusable version is not plan material.
            throw inconsistent("availability",
                    "An audience dependency requires a usable availability observation.");
        }
        if (expandedComplexity < 1) {
            throw inconsistent("expandedComplexity",
                    "An audience dependency requires a positive complexity contribution.");
        }
        if (depth < 1) {
            throw inconsistent("depth", "An audience dependency requires a positive discovery depth.");
        }
        directDependencies = canonicalPins(directDependencies, reference);
        retentionHoldIds = canonicalHoldIds(retentionHoldIds);
    }

    /** Builds the one dependency for a trusted resolution and the retention holds observed with it. */
    static AudienceVersionDependency of(
            ResolvedAudienceReference resolved, List<AudienceRetentionHold> holds) {
        if (resolved == null) {
            throw inconsistent("resolvedReference",
                    "An audience dependency requires the trusted resolution it freezes.");
        }
        AudienceVersionSnapshot snapshot = resolved.snapshot();
        List<String> holdIds = new ArrayList<>(holds == null ? 0 : holds.size());
        if (holds != null) {
            for (AudienceRetentionHold hold : holds) {
                if (hold == null) {
                    throw inconsistent("retentionHold", "A retention hold must be a non-null observation.");
                }
                holdIds.add(hold.holdId());
            }
        }
        return new AudienceVersionDependency(
                resolved.reference(),
                snapshot.subject(),
                snapshot.tenantId(),
                snapshot.programId(),
                snapshot.canonicalAstHash(),
                snapshot.semanticQueryPlanHash(),
                snapshot.effectiveDataScope(),
                resolved.effectiveDataScope(),
                snapshot.directDependencies(),
                snapshot.dependencyGraphHash(),
                snapshot.publishedAt(),
                snapshot.availability(),
                holdIds,
                snapshot.expandedComplexity(),
                resolved.depth());
    }

    /** The exact ordered hold identifiers a re-check must still find, or {@code List.of()} when unheld. */
    public List<String> retentionHoldIds() {
        return retentionHoldIds;
    }

    private static List<AudienceVersionRef> canonicalPins(
            List<AudienceVersionRef> pins, AudienceVersionRef reference) {
        if (pins == null || pins.isEmpty()) {
            return List.of();
        }
        List<AudienceVersionRef> ordered = new ArrayList<>(pins.size());
        Set<String> identities = new HashSet<>();
        for (AudienceVersionRef pin : pins) {
            if (pin == null) {
                throw inconsistent("directDependencies",
                        "A frozen direct dependency must be an exact version pin.");
            }
            if (pin.equals(reference)) {
                throw inconsistent("directDependencies",
                        "A referenced version must not be frozen as its own direct dependency.");
            }
            if (!identities.add(pin.identityKey())) {
                throw inconsistent("directDependencies",
                        "A referenced version must not repeat one direct dependency identity.");
            }
            ordered.add(pin);
        }
        ordered.sort(AudienceVersionRef.ORDER);
        return List.copyOf(ordered);
    }

    private static List<String> canonicalHoldIds(List<String> holdIds) {
        if (holdIds == null || holdIds.isEmpty()) {
            return List.of();
        }
        List<String> ordered = new ArrayList<>(holdIds.size());
        Set<String> seen = new HashSet<>();
        for (String holdId : holdIds) {
            if (holdId == null || holdId.isBlank()) {
                throw inconsistent("retentionHoldIds", "A retention hold identifier must be non-blank.");
            }
            if (!seen.add(holdId)) {
                throw inconsistent("retentionHoldIds", "A retention hold identifier must not repeat.");
            }
            ordered.add(holdId);
        }
        ordered.sort(String::compareTo);
        return List.copyOf(ordered);
    }

    /** Canonical, duplicate-free dependency order; package-internal so only the plan boundary normalizes. */
    static List<AudienceVersionDependency> canonical(List<AudienceVersionDependency> dependencies) {
        if (dependencies == null) {
            throw inconsistent("audienceDependencies", "A plan requires its audience dependency list.");
        }
        List<AudienceVersionDependency> ordered = new ArrayList<>(dependencies.size());
        for (AudienceVersionDependency dependency : dependencies) {
            if (dependency == null) {
                throw inconsistent("audienceDependencies", "A plan must not carry a null audience dependency.");
            }
            ordered.add(dependency);
        }
        ordered.sort(ORDER);
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).reference().equals(ordered.get(index).reference())) {
                throw inconsistent("audienceDependencies",
                        "A plan must not repeat one referenced audience version.");
            }
        }
        return List.copyOf(ordered);
    }

    private static QueryException inconsistent(String invariant, String message) {
        return new QueryException(QueryErrorCodes.QUERY_PLAN_INCONSISTENT, invariant, message);
    }

    @Override
    public String toString() {
        return "AudienceVersionDependency[version=" + reference.identityKey()
                + ", depth=" + depth
                + ", expandedComplexity=" + expandedComplexity
                + ", effectiveDataScopeId=" + effectiveDataScope.dataScopeId()
                + ']';
    }
}