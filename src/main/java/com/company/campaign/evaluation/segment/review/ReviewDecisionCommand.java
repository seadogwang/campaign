package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One host-submitted human review decision for an exact case revision and role.
 *
 * <p>The command is the entire submission surface. It carries a {@link ReviewCaseKey}, the exact
 * {@code reviewHash} the reviewer saw, one role, an {@code APPROVED} or {@code REJECTED} status, an evidence
 * reference, an explicit decision time and bounded notes. It deliberately has no reviewer identity, no
 * permissions, no tenant/user/DataScope, no Case or Expected replacement, no Gate outcome, no model metadata
 * and no activation flag, and strict unknown-field rejection means none of those can be smuggled in as an
 * extra JSON property. The reviewer is supplied separately by the authenticated host as a
 * {@link ReviewActor}, so a command cannot impersonate one.
 *
 * <p>{@link ReviewStatus#PENDING} is not a decision and is rejected here: a submitted command either
 * approves or rejects. The decision time is always supplied by the caller — the boundary never reads a
 * clock — so replaying a command cannot invent a new decision instant.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewDecisionCommand(
        ReviewCaseKey caseKey,
        ContentHash expectedReviewHash,
        ReviewRole role,
        ReviewStatus status,
        String evidenceRef,
        Instant decisionTime,
        String notes
) {

    public ReviewDecisionCommand {
        if (caseKey == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review decision must name its exact case key");
        }
        if (expectedReviewHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review decision must bind the expected current review hash");
        }
        if (role == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review decision must name exactly one review role");
        }
        if (status == null || status == ReviewStatus.PENDING) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_STATUS_INVALID,
                    "A review decision for role " + role + " must be APPROVED or REJECTED, never PENDING");
        }
        evidenceRef = ReviewInputs.requireReference(evidenceRef, "evidence reference");
        if (decisionTime == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review decision for role " + role + " must state its explicit decision time");
        }
        notes = ReviewInputs.requireNotes(role, notes);
    }
}