package com.company.campaign.evaluation.segment.gate;

import java.util.Optional;

/**
 * Host-injected port that returns the latest approved reference Gate decision for one capability.
 *
 * <p>It is the reference counterpart of {@link GateApprovalLookupPort} and the only way reference readiness
 * learns what was approved: the reference projection never reads a stored verdict itself, never reruns an
 * evaluation and never manufactures an approval. There is no permissive default — a host that cannot answer
 * returns {@link Optional#empty()}, which projects {@code EXPERIMENTAL} rather than {@code ACTIVE}. A thrown
 * failure is treated exactly like malformed evidence, so a missing or misbehaving authority can never
 * activate the reference capability.
 *
 * <p>The port is consulted only for a reference-bearing compile: a compile with no reference dependencies
 * supplies no reference inputs and never asks the reference Gate port at all.
 */
@FunctionalInterface
public interface ReferenceApprovalLookupPort {

    /** The latest approved reference decision for the capability, or empty when none exists. */
    Optional<ReferenceApprovedDecision> latestApproved(String capabilityId);
}