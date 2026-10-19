package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * Host-injected port that explicitly authorizes one accountable owner to approve one exact Gate decision.
 *
 * <p>There is no permissive default and no in-memory implementation in production code: the host must inject
 * an adapter that authenticates the human and answers against its own policy. The approval service calls this
 * port exactly once per approval and fails closed on a null, malformed, denied, wrong-owner, wrong-role,
 * wrong-hash or thrown answer, so a missing or misbehaving authority can never produce an accepted approval.
 */
@FunctionalInterface
public interface GateApprovalAuthorizationPort {

    /**
     * Returns an explicit approval or denial for the exact owner, role, decision hash and outcome.
     *
     * <p>A denial is expressed by {@link GateApprovalAuthorizationDecision#allowed()} being false or by any
     * echoed field disagreeing with the request; a thrown failure is treated as a denial.
     */
    GateApprovalAuthorizationDecision authorize(
            String ownerRef, GateOwnerRole ownerRole, ContentHash decisionHash);
}