package com.company.campaign.security.context;

import com.company.campaign.contracts.TrustedExecutionContextRef;

/**
 * Port implemented by future HTTP and background adapters to supply the trusted context.
 *
 * <p>The adapter reads the verified identity and membership from its own transport — an OIDC principal on
 * an HTTP request, or a persisted Execution/Outbox/Task record in a worker. The port deliberately requires
 * no ThreadLocal and no global security holder: an adapter returns the values, and the default method runs
 * the same fail-closed resolver the rest of the application uses.
 *
 * <p>An implementation must never return values taken from a model-controlled payload. The resolver still
 * re-validates identity/membership/snapshot agreement on every call, so a mistaken adapter fails closed.
 */
public interface TrustedContextProvider {

    /** The identity the adapter has verified. */
    VerifiedIdentity verifiedIdentity();

    /** The active membership the adapter resolved for that identity. */
    PrincipalMembership verifiedMembership();

    /** The execution-context snapshot bound to this execution. */
    TrustedExecutionContextRef executionReference();

    /** Resolves and validates the trusted context for the current execution. */
    default TrustedExecutionContext trustedContext() {
        return TrustedContextResolver.resolve(verifiedIdentity(), verifiedMembership(), executionReference());
    }
}