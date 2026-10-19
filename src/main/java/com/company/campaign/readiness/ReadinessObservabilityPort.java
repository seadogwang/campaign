package com.company.campaign.readiness;

/**
 * The bounded internal observability port for readiness status and blocker counts.
 *
 * <p>It answers for one scope and one level, and reports labels that are closed machine codes only. A host
 * that exposes this to a metrics system therefore cannot leak a tenant, a program, an actor, an evidence
 * identifier, an evidence reference, a member identifier or a Provider target through a readiness metric.
 */
public interface ReadinessObservabilityPort {

    /** The current readiness snapshot for one scope and level. */
    ReadinessObservabilitySnapshot snapshot(EvidenceScope scope, ReadinessLevel level);
}