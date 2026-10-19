package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.shared.canonical.ContentHash;

/**
 * One atomic review-decision write: compare-and-set the old ledger hash, store the new ledger and append the
 * audit event.
 *
 * <p>The three parts travel together so a repository cannot write the ledger without the audit evidence or
 * vice versa. The commit is self-consistent: the updated ledger must name the same case as the key, the
 * expected hash must be the revision the reviewer saw, and the audit event must bracket the old and new
 * ledger hashes. A repository that receives an incoherent commit refuses it rather than persisting it.
 */
public record ReviewDecisionCommit(
        ReviewCaseKey caseKey,
        ContentHash expectedReviewHash,
        CaseReviewEvidence updatedReview,
        ReviewAuditEvent auditEvent
) {

    public ReviewDecisionCommit {
        if (caseKey == null || expectedReviewHash == null || updatedReview == null || auditEvent == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review commit requires its key, expected hash, updated ledger and audit event");
        }
        if (!caseKey.caseId().equals(updatedReview.caseId())
                || !caseKey.caseHash().equals(updatedReview.caseHash())
                || !caseKey.expectedHash().equals(updatedReview.expectedHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review commit's updated ledger must bind the exact case key it names");
        }
        updatedReview.requireValidContentHash();
        auditEvent.requireValidContentHash();
        if (!auditEvent.previousReviewHash().equals(expectedReviewHash)
                || !auditEvent.newReviewHash().equals(updatedReview.reviewHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review commit's audit event must bracket the expected and updated ledger hashes");
        }
        if (!auditEvent.corpusVersion().equals(caseKey.corpusVersion())
                || !auditEvent.caseId().equals(caseKey.caseId())
                || !auditEvent.caseHash().equals(caseKey.caseHash())
                || !auditEvent.expectedHash().equals(caseKey.expectedHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review commit's audit event must name the same case revision as the commit");
        }
    }
}