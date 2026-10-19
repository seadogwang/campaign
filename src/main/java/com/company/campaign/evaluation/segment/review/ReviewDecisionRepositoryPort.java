package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;

/**
 * Host-injected atomic repository port for review decisions.
 *
 * <p>One call performs the whole write as one operation: compare the exact expected review hash against the
 * currently stored ledger, store the updated ledger, and append the immutable audit event. There is no
 * separate "update" and "audit" method, so a repository cannot persist a decision without its evidence or
 * record an audit event for a write that did not happen.
 *
 * <p>If the stored ledger hash is not the expected one, the commit must lose the race, leave the current
 * ledger untouched and fail with {@code REVIEW_CAS_CONFLICT}. Nothing here reads a clock, generates an
 * identifier or mutates a committed corpus asset; the decision time and every reference arrive in the commit.
 */
public interface ReviewDecisionRepositoryPort {

    /**
     * Atomically compare-and-sets, stores and audits one decision.
     *
     * @throws EvaluationException with {@code REVIEW_CAS_CONFLICT} when the stored ledger hash is not the
     *         commit's expected hash, or {@code REVIEW_SNAPSHOT_NOT_FOUND} when the case is absent
     */
    void commit(ReviewDecisionCommit commit);

    /** The stable failure for a decision that lost the compare-and-set race. */
    static EvaluationException conflict() {
        return new EvaluationException(
                EvaluationErrorCodes.REVIEW_CAS_CONFLICT,
                "The review decision lost the compare-and-set race against a newer ledger");
    }
}