package com.company.campaign.query.plan;

import com.company.campaign.query.QueryErrorCodes;
import com.company.campaign.query.QueryException;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.segment.reference.AudienceVersionAvailability;
import com.company.campaign.segment.reference.AudienceVersionSnapshot;
import com.company.campaign.segment.reference.EffectiveDataScope;
import com.company.campaign.segment.reference.ReferenceExecutionContext;

import java.util.Objects;
import java.util.Optional;

/**
 * The execution-time guard for a reference-bearing Semantic Query Plan.
 *
 * <p>Resolving and planning a reference is not permanent authority. Between planning and execution a caller
 * can lose the {@code SEGMENT} permission, a referenced version can be withdrawn or hard-deleted, its bytes
 * can change under one version identity, a retention hold that kept it alive can be released, and the
 * effective DataScope of the caller can move. Every one of those makes the frozen plan material no longer
 * describe what could actually be executed, so an execution re-proves all of it against the trusted ports
 * immediately before anything runs.
 *
 * <p>It re-checks, for every frozen dependency, in one fixed order:
 *
 * <ol>
 *   <li>the exact version is still visible and its bytes still hash to the frozen pin;</li>
 *   <li>the host still reports it as {@link AudienceVersionAvailability#AVAILABLE};</li>
 *   <li>the host still permits the caller's program to reference the owning program;</li>
 *   <li>the caller is still authorized for {@code ResourceKind.SEGMENT} through the T02 boundary;</li>
 *   <li>every retention hold the plan froze is still active, so a released hold is drift;</li>
 * </ol>
 *
 * <p>and then re-derives the host-computed effective DataScope intersection and requires it to equal the plan's
 * frozen effective scope, identity and content hash included.
 *
 * <p>A plan with no audience dependency is a no-op: no port is consulted, so a reference-free execution is
 * exactly what it was before references existed, and a host whose ports throw cannot affect it.
 *
 * <p>Every refusal is the one stable, non-disclosing {@link QueryErrorCodes#QUERY_REFERENCE_EXECUTION_DENIED}
 * code whose {@code invariant} detail names the failed re-check. No version identifier, tenant, program, scope,
 * hold identifier or thrown cause ever escapes, so a caller cannot probe what a referenced version is by
 * observing how execution failed.
 *
 * <p>The guard is immutable and thread-safe.
 */
public final class ReferenceExecutionGuard {

    private final AuthorizationService authorizationService;

    /**
     * Creates a guard backed by the supplied T02 authorization boundary.
     *
     * <p>The service is mandatory: a guard that cannot authorize cannot exist, so an execution path can never
     * silently degrade to "the permission was checked once, at planning time".
     */
    public ReferenceExecutionGuard(AuthorizationService authorizationService) {
        if (authorizationService == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, "authorizationService",
                    "The reference execution guard requires an authorization service.");
        }
        this.authorizationService = authorizationService;
    }

    /**
     * Re-proves every frozen reference dependency of one plan against the trusted ports right now.
     *
     * @throws QueryException with {@link QueryErrorCodes#QUERY_REFERENCE_EXECUTION_DENIED} when any re-check
     *                         fails, and with {@link QueryErrorCodes#QUERY_INPUT_MISSING} when a required input
     *                         is absent
     */
    public void requireExecutable(
            SemanticQueryPlan plan,
            TrustedExecutionContext context,
            ReferenceExecutionContext referenceContext) {
        if (plan == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, "plan",
                    "The reference execution guard requires a plan.");
        }
        if (!plan.hasAudienceReferences()) {
            return;
        }
        if (context == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, "trustedContext",
                    "The reference execution guard requires the trusted execution context.");
        }
        if (referenceContext == null) {
            throw new QueryException(QueryErrorCodes.QUERY_INPUT_MISSING, "referenceContext",
                    "The reference execution guard requires the host's trusted reference ports.");
        }

        for (AudienceVersionDependency dependency : plan.audienceDependencies()) {
            requireExactVersion(dependency, referenceContext);
            requireAvailability(dependency, referenceContext);
            requireProgramRelation(dependency, context, referenceContext);
            requireSegmentAuthorization(dependency, context);
            requireRetention(dependency, referenceContext);
        }
        requireEffectiveDataScope(plan, context, referenceContext);
    }

    // ------------------------------------------------------------------ re-checks

    /** The exact version must still be visible, still owned by its frozen owner and still hashed as pinned. */
    private void requireExactVersion(
            AudienceVersionDependency dependency, ReferenceExecutionContext referenceContext) {
        Optional<AudienceVersionSnapshot> found;
        try {
            found = referenceContext.catalog().findExact(dependency.reference());
        } catch (RuntimeException exception) {
            throw denied("versionIdentity");
        }
        if (found == null || found.isEmpty()) {
            throw denied("versionIdentity");
        }
        // Every published fact the plan froze must still be the version's own fact: the pin, the subject,
// both content hashes, the dependency graph hash, the published DataScope, the publication instant
// and the complexity the version contributes. Any of them moving means the frozen material no longer
// describes the version that would actually be read, so the reference refuses rather than executing
// against material the plan never authorized.
        AudienceVersionSnapshot snapshot = found.get();
        if (!snapshot.version().equals(dependency.reference())
                || !Objects.equals(snapshot.canonicalAstHash(), dependency.canonicalAstHash())
                || !Objects.equals(snapshot.semanticQueryPlanHash(), dependency.semanticQueryPlanHash())
                || !Objects.equals(snapshot.dependencyGraphHash(), dependency.dependencyGraphHash())
                || !Objects.equals(snapshot.publishedAt(), dependency.publishedAt())
                || !Objects.equals(snapshot.effectiveDataScope(), dependency.publishedDataScope())
                || snapshot.expandedComplexity() != dependency.expandedComplexity()
                || !snapshot.directDependencies().equals(dependency.directDependencies())
                || snapshot.subject() != dependency.subject()) {
            throw denied("versionIdentity");
        }
        if (!Objects.equals(snapshot.tenantId(), dependency.ownerTenantId())
                || !Objects.equals(snapshot.programId(), dependency.ownerProgramId())) {
            throw denied("versionOwner");
        }
    }

    /** The host must still report the exact version as available. */
    private void requireAvailability(
            AudienceVersionDependency dependency, ReferenceExecutionContext referenceContext) {
        AudienceVersionAvailability current;
        try {
            current = referenceContext.availabilityPort().currentAvailability(dependency.reference());
        } catch (RuntimeException exception) {
            throw denied("versionAvailability");
        }
        if (current == null || current != dependency.availability() || !current.isUsable()) {
            throw denied("versionAvailability");
        }
    }

    /** The host policy must still allow the caller's program to reach the version's program. */
    private void requireProgramRelation(
            AudienceVersionDependency dependency,
            TrustedExecutionContext context,
            ReferenceExecutionContext referenceContext) {
        boolean allowed;
        try {
            allowed = referenceContext.programPolicy()
                    .allowsProgramRelation(context.programId(), dependency.ownerProgramId());
        } catch (RuntimeException exception) {
            throw denied("programRelation");
        }
        if (!allowed) {
            throw denied("programRelation");
        }
    }

    /** The caller must still be authorized for the exact {@code SEGMENT} through the T02 boundary. */
    private void requireSegmentAuthorization(
            AudienceVersionDependency dependency, TrustedExecutionContext context) {
        AuthorizationDecision decision;
        try {
            decision = authorizationService.authorize(AuthorizationRequest.of(
                    context,
                    ResourceKind.SEGMENT,
                    dependency.reference().audienceId(),
                    dependency.ownerTenantId(),
                    dependency.ownerProgramId()));
        } catch (RuntimeException exception) {
            // A boundary that throws is an unavailable decision, never an approval.
            throw denied("segmentAuthorization");
        }
        if (decision == null
                || decision.disposition() != AuthorizationDisposition.ALLOWED
                || !decision.allowed()
                || decision.reason() != AuthorizationReason.ALLOWED
                || decision.resourceKind() != ResourceKind.SEGMENT
                || decision.requiredPermission() != ResourceKind.SEGMENT.requiredPermission()) {
            throw denied("segmentAuthorization");
        }
    }

    /** Every retention hold the plan froze must still be active; an additional hold is harmless. */
    private void requireRetention(
            AudienceVersionDependency dependency, ReferenceExecutionContext referenceContext) {
        for (String holdId : dependency.retentionHoldIds()) {
            boolean active;
            try {
                active = referenceContext.retentionPort().activeHolds(dependency.reference()).stream()
                        .anyMatch(hold -> hold != null && holdId.equals(hold.holdId()));
            } catch (RuntimeException exception) {
                throw denied("referenceRetention");
            }
            if (!active) {
                throw denied("referenceRetention");
            }
        }
    }

    /**
     * Re-derives the host-computed effective DataScope and requires it to equal the plan's frozen one.
     *
     * <p>The fold starts from the caller scope and pins it as the base of every step, exactly as planning did,
     * so the re-derived intersection can never be wider than the frozen one. Both the identity and the content
     * hash are compared, so a scope that kept its name but changed its members fails.
     */
    private void requireEffectiveDataScope(
            SemanticQueryPlan plan, TrustedExecutionContext context, ReferenceExecutionContext referenceContext) {
        Optional<EffectiveDataScope> caller;
        try {
            caller = referenceContext.dataScopePort().callerScope(
                    context.tenantId(), context.programId(), context.userId(), context.dataScopeId());
        } catch (RuntimeException exception) {
            throw denied("effectiveDataScope");
        }
        if (caller == null || caller.isEmpty() || !caller.get().dataScopeId().equals(context.dataScopeId())) {
            throw denied("effectiveDataScope");
        }

        EffectiveDataScope base = caller.get();
        EffectiveDataScope effective = base;
        for (AudienceVersionDependency dependency : plan.audienceDependencies()) {
            Optional<EffectiveDataScope> next;
            try {
                next = referenceContext.dataScopePort()
                        .intersect(base, effective, dependency.effectiveDataScope());
            } catch (RuntimeException exception) {
                throw denied("effectiveDataScope");
            }
            if (next == null || next.isEmpty()) {
                throw denied("effectiveDataScope");
            }
            effective = next.get();
        }

        DataScopePredicate predicate = plan.dataScopePredicate();
        if (!predicate.isEffectiveIntersection()
                || !effective.dataScopeId().equals(predicate.dataScopeId())
                || !effective.dataScopeHash().equals(predicate.dataScopeHash())) {
            throw denied("effectiveDataScope");
        }
    }

    /** The one stable, non-disclosing refusal for every failed re-check. */
    private static QueryException denied(String invariant) {
        return new QueryException(QueryErrorCodes.QUERY_REFERENCE_EXECUTION_DENIED, invariant,
                "A frozen audience reference no longer matches the trusted execution facts.");
    }
}