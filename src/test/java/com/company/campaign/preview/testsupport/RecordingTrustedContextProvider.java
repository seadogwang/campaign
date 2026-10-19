package com.company.campaign.preview.testsupport;

import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.TrustedContextProvider;
import com.company.campaign.security.context.TrustedExecutionContext;
import com.company.campaign.security.context.VerifiedIdentity;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test host adapter that counts how many times {@code trustedContext()} was resolved.
 *
 * <p>It carries no ThreadLocal and no global holder: it returns the exact context it was constructed with, so
 * a test can prove the service resolves the trusted context exactly once per call.
 */
public final class RecordingTrustedContextProvider implements TrustedContextProvider {

    private static final Instant AUTHENTICATED_AT = Instant.parse("2026-10-01T01:00:00Z");

    private final TrustedExecutionContext context;
    private final boolean failing;
    private final boolean returnsNull;
    private final AtomicInteger calls = new AtomicInteger();

    private RecordingTrustedContextProvider(TrustedExecutionContext context, boolean failing, boolean returnsNull) {
        this.context = context;
        this.failing = failing;
        this.returnsNull = returnsNull;
    }

    public static RecordingTrustedContextProvider of(TrustedExecutionContext context) {
        return new RecordingTrustedContextProvider(context, false, false);
    }

    public static RecordingTrustedContextProvider failing() {
        return new RecordingTrustedContextProvider(null, true, false);
    }

    public static RecordingTrustedContextProvider returningNull() {
        return new RecordingTrustedContextProvider(null, false, true);
    }

    @Override
    public TrustedExecutionContext trustedContext() {
        calls.incrementAndGet();
        if (failing) {
            throw new IllegalStateException("host context unavailable with secret-like text");
        }
        return returnsNull ? null : context;
    }

    @Override
    public VerifiedIdentity verifiedIdentity() {
        return new VerifiedIdentity(context.userId(), "https://issuer.example", AUTHENTICATED_AT);
    }

    @Override
    public PrincipalMembership verifiedMembership() {
        return new PrincipalMembership(
                context.membership().membershipId(),
                context.userId(),
                context.tenantId(),
                context.programId(),
                context.dataScopeId(),
                context.membership().status(),
                context.permissions());
    }

    @Override
    public TrustedExecutionContextRef executionReference() {
        return context.snapshotRef();
    }

    /** How many times the trusted context was resolved. */
    public int callCount() {
        return calls.get();
    }

    /** The exact context this adapter serves. */
    public TrustedExecutionContext context() {
        return context;
    }
}