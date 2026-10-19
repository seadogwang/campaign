package com.company.campaign.evaluation.segment.gate;

import java.util.Optional;

/**
 * Host-injected port that returns the latest approved Gate decision for one capability.
 *
 * <p>It is the only way readiness learns what was approved: the Gate projection never reads a stored verdict
 * itself, never reruns an evaluation and never fabricates an approval. There is no permissive default — a host
 * that cannot answer returns {@link Optional#empty()}, which projects {@code EXPERIMENTAL} rather than
 * {@code ACTIVE}. A thrown failure is treated exactly like an empty answer, so a missing or misbehaving
 * authority can never activate a capability.
 */
@FunctionalInterface
public interface GateApprovalLookupPort {

    /** The latest approved decision for the capability, or empty when none exists. */
    Optional<GateApprovedDecision> latestApproved(String capabilityId);
}