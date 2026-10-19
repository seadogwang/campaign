package com.company.campaign.readiness;

import com.company.campaign.security.context.TrustedExecutionContext;

import java.time.Instant;

/**
 * The trusted host context one readiness write is performed under.
 *
 * <p>Tenant, program and environment are supplied by the host — from the accepted
 * {@link TrustedExecutionContext} and an explicit host environment pin — and are never read from an evidence
 * payload. {@link EvidenceSubmission} deliberately has no field for any of them, so a forged same-named
 * field in a caller's payload has nothing to bind to.
 *
 * <p>The context is bounded and carries no credential, session token or DataScope value beyond the actor
 * reference an audit record needs.
 */
public record ReadinessHostContext(
        String tenantId,
        String programId,
        ReadinessEnvironment environment,
        String actorRef,
        String requestCorrelation,
        Instant asOf) {

    public ReadinessHostContext {
        ReadinessSupport.requireId(tenantId, "tenantId");
        ReadinessSupport.requireId(programId, "programId");
        ReadinessSupport.requireId(actorRef, "actorRef");
        ReadinessSupport.requireId(requestCorrelation, "requestCorrelation");
        ReadinessSupport.requireNonNull(environment, "environment");
        ReadinessSupport.requireInstant(asOf, "asOf");
    }

    /**
     * Derives a readiness context from an already validated trusted execution context.
     *
     * <p>The accepted resolver is what validates identity, membership and snapshot agreement, so a context
     * built this way is exactly as trustworthy as the security core that produced it. The environment is the
     * one value the host must supply separately: it names where the run happened, which is the axis a
     * production claim is checked against.
     */
    public static ReadinessHostContext of(
            TrustedExecutionContext trustedContext,
            ReadinessEnvironment environment,
            String actorRef,
            String requestCorrelation) {
        TrustedExecutionContext context = ReadinessSupport.requireNonNull(trustedContext, "trustedContext");
        return new ReadinessHostContext(
                context.tenantId(),
                context.programId(),
                environment,
                actorRef,
                requestCorrelation,
                context.snapshotRef().asOf());
    }

    /** The scope every evidence and assessment written under this context belongs to. */
    public EvidenceScope scope() {
        return new EvidenceScope(tenantId, programId, environment);
    }
}