package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;

/**
 * The explicit answer of the trusted review-authorization port for one exact actor/role/key request.
 *
 * <p>The decision echoes back the actor, role and case key it approved, so the service can prove the port
 * answered the exact question it asked. An approval for a different role, a different case or a different
 * actor is treated as a denial: a port that returns a generic "allowed" cannot silently widen the request.
 * A decision is a value, not a mutable flag, so it cannot be flipped after the port returns it.
 */
public record ReviewAuthorizationDecision(
        boolean allowed,
        ReviewActor actor,
        ReviewRole role,
        ReviewCaseKey caseKey
) {

    public ReviewAuthorizationDecision {
        if (actor == null || role == null || caseKey == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_AUTHORIZATION_DENIED,
                    "A review authorization decision must name the exact actor, role and case key");
        }
    }

    /** An explicit approval of the exact actor, role and case key. */
    public static ReviewAuthorizationDecision allow(ReviewActor actor, ReviewRole role, ReviewCaseKey caseKey) {
        return new ReviewAuthorizationDecision(true, actor, role, caseKey);
    }

    /** An explicit denial. The echoed identity is still required so the answer is unambiguous. */
    public static ReviewAuthorizationDecision deny(ReviewActor actor, ReviewRole role, ReviewCaseKey caseKey) {
        return new ReviewAuthorizationDecision(false, actor, role, caseKey);
    }
}