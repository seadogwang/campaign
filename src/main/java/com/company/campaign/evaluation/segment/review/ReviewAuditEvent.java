package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.EvaluationException;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * The immutable, redacted audit record of one accepted review decision.
 *
 * <p>Every accepted decision appends exactly one event. The event hash-binds the corpus version, the case
 * and expected hashes, the previous and new ledger hashes, the role, the status, the reviewer and evidence
 * references and the explicit decision time. That is the whole content: it carries no utterance, no expected
 * AST, no member set, no review notes, no credential, no tenant/program/user/DataScope and no raw trusted
 * context, so an audit trail proves <em>that</em> a decision happened and exactly which bytes it covered
 * without republishing the reviewed material.
 *
 * <p>{@link #auditEventHash()} covers the event with that field excluded, so the hash is not
 * self-referential. {@link #of} is the only way to build an event with a correct hash, and
 * {@link #requireValidContentHash()} re-derives it from the parsed value.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviewAuditEvent(
        String corpusVersion,
        String caseId,
        ContentHash caseHash,
        ContentHash expectedHash,
        ContentHash previousReviewHash,
        ContentHash newReviewHash,
        ReviewRole role,
        ReviewStatus status,
        String reviewerRef,
        String evidenceRef,
        Instant decisionTime,
        ContentHash auditEventHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ReviewAuditEvent {
        if (corpusVersion == null || caseId == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review audit event must name the corpus version and case it decided");
        }
        if (caseHash == null || expectedHash == null
                || previousReviewHash == null || newReviewHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review audit event must bind the case, expected and both ledger hashes");
        }
        if (role == null || status == null || !status.isDecided()) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_STATUS_INVALID,
                    "A review audit event must record one decided role and status");
        }
        reviewerRef = ReviewInputs.requireReference(reviewerRef, "audit reviewer reference");
        evidenceRef = ReviewInputs.requireReference(evidenceRef, "audit evidence reference");
        if (decisionTime == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_DECISION_INVALID,
                    "A review audit event must record the explicit decision time");
        }
    }

    /** Builds an event and stamps it with its own content hash. */
    public static ReviewAuditEvent of(
            String corpusVersion,
            String caseId,
            ContentHash caseHash,
            ContentHash expectedHash,
            ContentHash previousReviewHash,
            ContentHash newReviewHash,
            ReviewRole role,
            ReviewStatus status,
            String reviewerRef,
            String evidenceRef,
            Instant decisionTime) {
        ReviewAuditEvent withoutHash = new ReviewAuditEvent(
                corpusVersion, caseId, caseHash, expectedHash, previousReviewHash, newReviewHash,
                role, status, reviewerRef, evidenceRef, decisionTime, null);
        return withoutHash.withAuditEventHash(CANONICAL_JSON.hash(withoutHash));
    }

    /** The same event carrying the given hash. */
    public ReviewAuditEvent withAuditEventHash(ContentHash hash) {
        return new ReviewAuditEvent(
                corpusVersion, caseId, caseHash, expectedHash, previousReviewHash, newReviewHash,
                role, status, reviewerRef, evidenceRef, decisionTime, hash);
    }

    /** The canonical content hash of this event with the hash field excluded. */
    public ContentHash recomputeContentHash() {
        return CANONICAL_JSON.hash(withAuditEventHash(null));
    }

    /** Rejects an event whose declared hash does not match its content. */
    public void requireValidContentHash() {
        if (auditEventHash == null) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_AUDIT_HASH_MISMATCH,
                    "Review audit event for case '" + caseId + "' does not declare a content hash");
        }
        if (!auditEventHash.equals(recomputeContentHash())) {
            throw new EvaluationException(
                    EvaluationErrorCodes.REVIEW_AUDIT_HASH_MISMATCH,
                    "Review audit event for case '" + caseId + "' does not match its declared content hash");
        }
    }
}