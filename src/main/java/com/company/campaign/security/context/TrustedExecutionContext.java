package com.company.campaign.security.context;

import com.company.campaign.contracts.TrustedExecutionContextRef;

import java.util.Set;

/**
 * Immutable trusted execution context for one authorization or data-access decision.
 *
 * <p>It combines the accepted {@link PrincipalMembership} with the existing
 * {@link TrustedExecutionContextRef} snapshot fields. The reference is derived from the verified host values
 * by {@link TrustedContextResolver}; it is never copied from a model-controlled payload.
 *
 * <p>The canonical constructor runs the same fail-closed validation as the resolver, so a caller cannot
 * build a context that skips the identity/membership/snapshot checks.
 */
public record TrustedExecutionContext(
        VerifiedIdentity identity,
        PrincipalMembership membership,
        TrustedExecutionContextRef reference
) {
    public TrustedExecutionContext {
        TrustedContextResolver.validate(identity, membership, reference);
    }

    /** Derives a validated context from verified host values. */
    public static TrustedExecutionContext of(
            VerifiedIdentity identity,
            PrincipalMembership membership,
            TrustedExecutionContextRef reference) {
        return new TrustedExecutionContext(identity, membership, reference);
    }

    public String tenantId() {
        return membership.tenantId();
    }

    public String programId() {
        return membership.programId();
    }

    public String userId() {
        return membership.userId();
    }

    public String dataScopeId() {
        return membership.dataScopeId();
    }

    public Set<CampaignPermission> permissions() {
        return membership.permissions();
    }

    public boolean hasPermission(CampaignPermission permission) {
        return membership.hasPermission(permission);
    }

    /** The immutable snapshot reference that accompanies this context. */
    public TrustedExecutionContextRef snapshotRef() {
        return reference;
    }
}