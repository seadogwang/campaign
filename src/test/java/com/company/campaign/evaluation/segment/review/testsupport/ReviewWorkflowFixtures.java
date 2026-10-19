package com.company.campaign.evaluation.segment.review.testsupport;

import com.company.campaign.evaluation.segment.contract.CaseFileReference;
import com.company.campaign.evaluation.segment.contract.CaseReviewEntry;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.loader.SegmentEvaluationLoader;
import com.company.campaign.evaluation.segment.review.ReviewCaseKey;
import com.company.campaign.evaluation.segment.review.ReviewCaseSnapshot;
import com.company.campaign.evaluation.segment.review.ReviewDecisionCommand;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only helpers over the committed candidate corpus for review-boundary tests.
 *
 * <p>Nothing here writes to the source tree: the committed {@code corpus-v1} assets are loaded once and
 * every modification is made to an in-memory copy.
 */
public final class ReviewWorkflowFixtures {

    public static final String MANIFEST = "evaluation/segment/corpus-v1/manifest.json";

    /** A fixed decision instant, so no test reads a clock. */
    public static final Instant DECISION_TIME = Instant.parse("2026-10-02T09:00:00Z");

    private ReviewWorkflowFixtures() {
    }

    public static CorpusBundle loadCorpusV1() {
        return new SegmentEvaluationLoader(Path.of("")).loadCorpus(MANIFEST);
    }

    public static ReviewCaseKey keyFor(CorpusBundle bundle, int index) {
        CaseFileReference reference = bundle.manifest().cases().get(index);
        return new ReviewCaseKey(
                bundle.manifest().corpusVersion(), reference.caseId(),
                reference.caseHash(), reference.expectedHash());
    }

    public static ReviewCaseSnapshot snapshotFor(CorpusBundle bundle, int index) {
        return new ReviewCaseSnapshot(
                keyFor(bundle, index), bundle.cases().get(index),
                bundle.expectedResults().get(index), bundle.reviews().get(index));
    }

    /** The same bundle with a replaced review list, so approvals can be exercised entirely in memory. */
    public static CorpusBundle withReviews(CorpusBundle bundle, List<CaseReviewEvidence> reviews) {
        return new CorpusBundle(
                bundle.manifest(), bundle.metadataCatalog(), bundle.fixtureCatalog(),
                bundle.cases(), bundle.expectedResults(), reviews, bundle.metadataPayloads(),
                bundle.executionContexts());
    }

    /** Every case's ledger rebuilt with all three roles approved for the same exact revision. */
    public static List<CaseReviewEvidence> approvedReviews(CorpusBundle bundle) {
        List<CaseReviewEvidence> reviews = new ArrayList<>(bundle.reviews().size());
        for (CaseReviewEvidence pending : bundle.reviews()) {
            List<CaseReviewEntry> entries = new ArrayList<>(ReviewRole.ordered().size());
            for (ReviewRole role : ReviewRole.ordered()) {
                entries.add(CaseReviewEntry.decided(
                        role, ReviewStatus.APPROVED,
                        "reviewer-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-01",
                        "review-evidence-" + role.name().toLowerCase(java.util.Locale.ROOT) + "-001",
                        DECISION_TIME,
                        "Approved by the " + role + " owner for this exact revision."));
            }
            reviews.add(CaseReviewEvidence.of(
                    pending.schemaVersion(), pending.caseId(), pending.caseHash(),
                    pending.expectedHash(), entries));
        }
        return reviews;
    }

    public static ReviewDecisionCommand command(
            ReviewCaseKey key,
            ContentHash expectedReviewHash,
            ReviewRole role,
            ReviewStatus status,
            String evidenceRef,
            String notes) {
        return new ReviewDecisionCommand(
                key, expectedReviewHash, role, status, evidenceRef, DECISION_TIME, notes);
    }
}