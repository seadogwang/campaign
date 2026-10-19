package com.company.campaign.production.security;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.context.PrincipalMembership;

import java.util.Objects;

/**
 * One server-owned membership together with the execution-context snapshot it pins.
 *
 * <p>The two are resolved in one server-side lookup so a caller can never combine a membership with a snapshot
 * the directory did not publish together. {@link com.company.campaign.security.context.TrustedContextResolver}
 * still re-validates their agreement.
 */
public record ResolvedMembership(PrincipalMembership membership, TrustedExecutionContextRef reference) {

    public ResolvedMembership {
        Objects.requireNonNull(membership, "membership");
        Objects.requireNonNull(reference, "reference");
    }
}