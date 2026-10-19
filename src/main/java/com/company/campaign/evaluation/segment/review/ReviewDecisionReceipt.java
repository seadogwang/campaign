package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The redacted result of one accepted review decision.
 *
 * <p>A receipt is deliberately thin. It reports the new ledger hash, the role/status that changed and the
 * hash of the immutable audit event that recorded the change — and nothing else. It carries no Case or
 * Expected payload, no notes, no reviewer identity and no trusted context, so returning it cannot disclose
 * the reviewed content or the human behind the decision. A caller that needs the reviewed content must go
 * back through the query port with the exact key.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewDecisionReceipt(
        String corpusVersion,
        String caseId,
        ReviewRole role,
        ReviewStatus status,
        ContentHash reviewHash,
        ContentHash auditEventHash
) {

    public ReviewDecisionReceipt {
        if (corpusVersion == null || caseId == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review receipt must name the corpus version and case it decided");
        }
        if (role == null || status == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review receipt must name the role and status that were decided");
        }
        if (!status.isDecided()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_STATUS_INVALID,
                    "A review receipt can only report a real APPROVED or REJECTED decision");
        }
        if (reviewHash == null || auditEventHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review receipt must bind the new review hash and its audit-event hash");
        }
    }
}