package com.company.campaign.evaluation.segment.validation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides whether a corpus carries complete, current and approved review evidence.
 *
 * <p>It has two passes on purpose. {@link #validateStructure} answers "is a real ledger present and does it
 * bind the revision that is actually in the corpus?" and is used by candidate completeness: a candidate
 * corpus is expected to carry pending ledgers bound to its exact hashes. {@link #validateReadiness} adds
 * "has every required role actually approved?" and is used only by Gate readiness. Keeping them apart is
 * what lets T06 report a machine-complete candidate while refusing to call it Gate-ready.
 *
 * <p>Nothing here fabricates a decision or reads a clock: it only compares what the ledger binds against
 * what the manifest pins, and reports the roles that are still pending or were rejected.
 */
public final class ReviewEvidenceValidator {

    /** Missing, mismatched or stale review evidence: the ledger cannot support any decision claim. */
    public List<Issue> validateStructure(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();
        List<CaseInput> cases = bundle.cases();
        List<CaseFileReference> references = bundle.manifest().cases();
        List<CaseReviewEvidence> reviews = bundle.reviews();

        for (int index = 0; index < cases.size(); index++) {
            CaseInput caseInput = cases.get(index);
            String path = "/cases/" + index + "/" + caseInput.caseId() + "/review";
            CaseFileReference reference = references.get(index);
            CaseReviewEvidence review = index < reviews.size() ? reviews.get(index) : null;

            if (!reference.hasReviewEvidence()) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING, path,
                        "Case '" + caseInput.caseId() + "' declares no review-evidence file"));
                continue;
            }
            if (review == null) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING, path,
                        "Case '" + caseInput.caseId() + "' has a review path but no loaded review ledger"));
                continue;
            }
            // The manifest pins the review file's byte hash and the loader verifies it, while the ledger's
            // own reviewHash excludes itself. Both are checked at load time, so nothing further is compared
            // here. What remains is whether the ledger still binds the revision the manifest names.
            if (!review.caseId().equals(caseInput.caseId())) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.REVIEW_CASE_MISMATCH, path,
                        "Review evidence names case '" + review.caseId() + "' but is attached to '"
                                + caseInput.caseId() + "'"));
            }
            if (!review.caseHash().equals(reference.caseHash())
                    || !review.expectedHash().equals(reference.expectedHash())) {
                issues.add(SegmentEvaluationValidator.issue(
                        EvaluationErrorCodes.REVIEW_BINDING_STALE, path,
                        "Review evidence for case '" + caseInput.caseId()
                                + "' binds a case or expected hash other than the reviewed revision"));
            }
            for (ReviewRole role : ReviewRole.ordered()) {
                CaseReviewEntry entry = review.entryFor(role);
                if (entry == null) {
                    issues.add(SegmentEvaluationValidator.issue(
                            EvaluationErrorCodes.REVIEW_ROLE_MISSING, path + "/" + role.name(),
                            "Review evidence for case '" + caseInput.caseId() + "' has no " + role
                                    + " entry"));
                    continue;
                }
                if (entry.isDecided() && (entry.reviewerRef() == null || entry.evidenceRef() == null
                        || entry.decisionTime() == null)) {
                    issues.add(SegmentEvaluationValidator.issue(
                            EvaluationErrorCodes.REVIEW_REFERENCE_INVALID, path + "/" + role.name(),
                            "Decided review entry for case '" + caseInput.caseId() + "' role " + role
                                    + " is missing a reviewer, evidence reference or decision time"));
                }
            }
        }
        return issues;
    }

    /** Pending or rejected roles: the evidence exists and is current, but the decision is not an approval. */
    public List<Issue> validateReadiness(CorpusBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        List<Issue> issues = new ArrayList<>();
        List<CaseInput> cases = bundle.cases();
        List<CaseReviewEvidence> reviews = bundle.reviews();

        for (int index = 0; index < cases.size(); index++) {
            CaseInput caseInput = cases.get(index);
            CaseReviewEvidence review = index < reviews.size() ? reviews.get(index) : null;
            if (review == null) {
                // Missing evidence is a structural finding and already blocks readiness.
                continue;
            }
            for (ReviewRole role : ReviewRole.ordered()) {
                CaseReviewEntry entry = review.entryFor(role);
                if (entry == null || entry.status() == ReviewStatus.APPROVED) {
                    continue;
                }
                String path = "/cases/" + index + "/" + caseInput.caseId() + "/review/" + role.name();
                if (entry.status() == ReviewStatus.REJECTED) {
                    issues.add(SegmentEvaluationValidator.issue(
                            EvaluationErrorCodes.REVIEW_REJECTED, path,
                            "Case '" + caseInput.caseId() + "' was rejected by the " + role + " owner"));
                } else {
                    issues.add(SegmentEvaluationValidator.issue(
                            EvaluationErrorCodes.REVIEW_PENDING, path,
                            "Case '" + caseInput.caseId() + "' still awaits " + role + " owner approval"));
                }
            }
        }
        return issues;
    }
}