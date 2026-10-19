package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.shared.canonical.CanonicalJson;

/**
 * One exact, immutable review snapshot for a human UI/query adapter.
 *
 * <p>The snapshot is the read side of the review boundary: it carries the reviewed Case and Expected
 * revision together with the current review ledger, so a human adapter never has to reassemble them and can
 * never be shown a case paired with another revision's ledger. The snapshot is source data, not a decision:
 * constructing one records nothing and authorizes nothing.
 *
 * <p>Construction re-proves the binding rather than trusting its inputs. The declared key must agree with
 * the case and expected ids, the canonical hashes of the Case and Expected values must equal the key's
 * hashes, and the ledger must bind those same two hashes and its own declared content hash. A snapshot that
 * disagreed with the key it names cannot exist, so a caller cannot prefetch a revision and later paste a
 * different key onto it.
 */
public record ReviewCaseSnapshot(
        ReviewCaseKey key,
        CaseInput caseInput,
        ExpectedResult expectedResult,
        CaseReviewEvidence review
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReviewCaseSnapshot {
        if (key == null || caseInput == null || expectedResult == null || review == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review snapshot requires an exact key, case, expected result and current ledger");
        }
        if (!key.caseId().equals(caseInput.caseId())
                || !key.caseId().equals(expectedResult.caseId())
                || !key.caseId().equals(review.caseId())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review snapshot must name one case across its key, case, expected result and ledger");
        }
        if (!CANONICAL_JSON.hash(caseInput).equals(key.caseHash())
                || !CANONICAL_JSON.hash(expectedResult).equals(key.expectedHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review snapshot's case or expected result does not match its exact case key");
        }
        if (!review.caseHash().equals(key.caseHash()) || !review.expectedHash().equals(key.expectedHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_BINDING_STALE,
                    "A review snapshot's ledger does not bind the exact revision its key names");
        }
        review.requireValidContentHash();
    }

    /** Builds a snapshot from a key and the exact revision it names, re-proving the binding. */
    public static ReviewCaseSnapshot of(
            ReviewCaseKey key, CaseInput caseInput, ExpectedResult expectedResult, CaseReviewEvidence review) {
        return new ReviewCaseSnapshot(key, caseInput, expectedResult, review);
    }
}