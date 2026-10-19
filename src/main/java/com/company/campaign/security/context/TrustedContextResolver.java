package com.company.campaign.security.context;

import com.company.campaign.contracts.TrustedExecutionContextRef;

import java.util.Objects;

/**
 * Fail-closed factory for {@link TrustedExecutionContext}.
 *
 * <p>The resolver derives the trusted reference from verified host values only. It refuses to combine an
 * identity with a membership it does not belong to, an inactive membership, or a membership that disagrees
 * with the execution-context snapshot on tenant, program, user or DataScope. Each refusal has a stable
 * machine code (see {@link TrustedContextException}).
 *
 * <p>Validation lives here so that both {@link TrustedExecutionContext#of} and any adapter call it, and so
 * that constructing the record directly cannot bypass a check.
 */
public final class TrustedContextResolver {

    private TrustedContextResolver() {
    }

    /** Derives a trusted context from verified host values. */
    public static TrustedExecutionContext resolve(
            VerifiedIdentity identity,
            PrincipalMembership membership,
            TrustedExecutionContextRef reference) {
        return new TrustedExecutionContext(identity, membership, reference);
    }

    /** Derives a trusted context from a provider port implemented by an HTTP or background adapter. */
    public static TrustedExecutionContext resolve(TrustedContextProvider provider) {
        Objects.requireNonNull(provider, "provider");
        return resolve(provider.verifiedIdentity(), provider.verifiedMembership(), provider.executionReference());
    }

    /**
     * Validates the three trusted inputs. Package-private: callers must go through a resolve method or the
     * {@link TrustedExecutionContext} constructor, which invokes this.
     */
    static void validate(
            VerifiedIdentity identity,
            PrincipalMembership membership,
            TrustedExecutionContextRef reference) {
        if (identity == null) {
            throw TrustedContextException.invalid("identity");
        }
        if (membership == null) {
            throw TrustedContextException.invalid("membership");
        }
        if (reference == null) {
            throw TrustedContextException.invalid("reference");
        }
        if (!identity.subjectId().equals(membership.userId())) {
            throw TrustedContextException.identityMismatch();
        }
        if (!membership.isActive()) {
            throw TrustedContextException.inactiveMembership(membership.membershipId());
        }
        requireText(reference.tenantId(), "tenantId");
        requireText(reference.programId(), "programId");
        requireText(reference.userId(), "userId");
        requireText(reference.dataScopeId(), "dataScopeId");
        requireText(reference.metadataSnapshotId(), "metadataSnapshotId");
        if (!reference.tenantId().equals(membership.tenantId())) {
            throw TrustedContextException.contextMismatch("tenantId");
        }
        if (!reference.programId().equals(membership.programId())) {
            throw TrustedContextException.contextMismatch("programId");
        }
        if (!reference.userId().equals(membership.userId())) {
            throw TrustedContextException.contextMismatch("userId");
        }
        if (!reference.dataScopeId().equals(membership.dataScopeId())) {
            throw TrustedContextException.contextMismatch("dataScopeId");
        }
        if (reference.locale() == null || reference.timezone() == null
                || reference.reportingCurrency() == null || reference.asOf() == null
                || reference.dataWatermark() == null) {
            throw TrustedContextException.invalid("reference");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw TrustedContextException.invalid(field);
        }
    }
}