package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;

/**
 * Host-injected port that loads one exact immutable review snapshot for a human UI/query adapter.
 *
 * <p>The port answers one question only: <em>what was reviewed for this exact key?</em> It has no read-all,
 * list, latest or search-by-content fallback, so a human adapter cannot accidentally review a neighbouring
 * revision. Absence is reported by returning {@code null} or throwing; both fail closed as
 * {@code REVIEW_SNAPSHOT_NOT_FOUND}. The port performs no write, no mutation and no authorization.
 */
public interface ReviewCaseQueryPort {

    /**
     * Loads the exact snapshot named by the key, or {@code null} when no such revision is available.
     *
     * <p>A returned snapshot must already prove its own key/case/expected/ledger binding; the service
     * re-proves it anyway and fails closed on any mismatch.
     */
    ReviewCaseSnapshot loadSnapshot(ReviewCaseKey caseKey);

    /** Fails closed with a stable code when a snapshot could not be loaded. */
    static EvaluationException notFound() {
        return new EvaluationException(
                EvaluationErrorCodes.REVIEW_SNAPSHOT_NOT_FOUND,
                "No exact review snapshot exists for the requested case key");
    }
}