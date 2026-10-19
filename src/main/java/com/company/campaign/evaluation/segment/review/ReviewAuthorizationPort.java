package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.ReviewRole;

/**
 * Host-injected port that explicitly authorizes one trusted reviewer for one exact review role and case key.
 *
 * <p>There is no permissive default and no in-memory implementation in production code: the host must inject
 * an adapter that authenticates the human and answers against its own policy. The service calls this port
 * exactly once per decision and fails closed on a null, malformed, denied, wrong-role, wrong-case or thrown
 * answer, so a missing or misbehaving authority can never produce an accepted decision.
 */
@FunctionalInterface
public interface ReviewAuthorizationPort {

    /**
     * Returns an explicit approval or denial for the exact reviewer, role and case key.
     *
     * <p>A denial is expressed by {@link ReviewAuthorizationDecision#allowed()} being false or by any echoed
     * field disagreeing with the request; a thrown failure is treated as a denial.
     */
    ReviewAuthorizationDecision authorize(ReviewActor actor, ReviewRole role, ReviewCaseKey caseKey);
}