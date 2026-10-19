package com.company.campaign.evaluation.segment.review;

import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.evaluation.segment.review.testsupport.ReviewWorkflowFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deterministic production boundary: what an exact loaded corpus may be used for.
 *
 * <p>The committed corpus is candidate-complete but still awaits human review, so it must classify as
 * {@code DEVELOPMENT_ONLY} — usable by later development code, never Gate-eligible and never silently
 * treated as invalid. A fully approved in-memory copy of the same exact revision must become
 * {@code GATE_ELIGIBLE}, and a structurally broken revision must be {@code INVALID}.
 */
class CorpusReviewClassificationTest {

    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = ReviewWorkflowFixtures.loadCorpusV1();
    }

    private final SegmentCorpusReviewBoundary boundary = new SegmentCorpusReviewBoundary();

    // 1. The committed pending corpus is development-only: not invalid, not Gate-eligible.

    @Test
    void committedPendingCorpusClassifiesAsDevelopmentOnly() {
        ReviewedCorpusClassification result = boundary.classify(bundle);

        assertThat(result.classification()).isEqualTo(CorpusUsageClassification.DEVELOPMENT_ONLY);
        assertThat(result.isGateEligible()).isFalse();
        assertThat(result.isUsableForDevelopment()).isTrue();

        // Pending reviews are reported, never silently ignored, and nothing structural is wrong.
        assertThat(result.issueCodes()).contains(EvaluationErrorCodes.REVIEW_PENDING);
        assertThat(result.issueCodes())
                .doesNotContain(EvaluationErrorCodes.REVIEW_EVIDENCE_MISSING)
                .doesNotContain(EvaluationErrorCodes.REVIEW_BINDING_STALE)
                .doesNotContain(EvaluationErrorCodes.REVIEW_ROLE_MISSING)
                .doesNotContain(EvaluationErrorCodes.REVIEW_REJECTED);

        // The verdict pins the exact manifest and review set it was computed over.
        assertThat(result.manifestHash()).isEqualTo(bundle.manifest().manifestHash());
        assertThat(result.corpusHash()).isNotEqualTo(result.manifestHash());
        assertThat(result.reviewSetHash()).isNotNull();

        // A development-only verdict cannot act as an official Gate input.
        ContractAssertions.assertFailsWithCode(
                result::requireGateEligible, EvaluationErrorCodes.REVIEW_NOT_GATE_ELIGIBLE);
    }

    // 2. A fully approved in-memory copy of the same exact revision is Gate-eligible.

    @Test
    void fullyApprovedInMemoryCorpusIsGateEligible() {
        CorpusBundle approved = ReviewWorkflowFixtures.withReviews(
                bundle, ReviewWorkflowFixtures.approvedReviews(bundle));

        ReviewedCorpusClassification result = boundary.classify(approved);

        assertThat(result.classification()).isEqualTo(CorpusUsageClassification.GATE_ELIGIBLE);
        assertThat(result.issueCodes()).isEmpty();
        assertThat(result.isGateEligible()).isTrue();
        result.requireGateEligible();

        // Approving changes the review-set pin, so the two verdicts can never be relabeled onto each other.
        ReviewedCorpusClassification pending = boundary.classify(bundle);
        assertThat(result.reviewSetHash()).isNotEqualTo(pending.reviewSetHash());
        assertThat(result.corpusHash()).isNotEqualTo(pending.corpusHash());
        assertThat(result.manifestHash()).isEqualTo(pending.manifestHash());
    }

    // 3. A structurally invalid candidate is invalid, never development-only.

    @Test
    void structurallyInvalidCandidateIsInvalid() {
        List<CaseReviewEvidence> tampered = new ArrayList<>(bundle.reviews());
        CaseReviewEvidence first = tampered.get(0);
        tampered.set(0, CaseReviewEvidence.of(
                first.schemaVersion(), first.caseId(), first.caseHash(),
                ContentHash.sha256("different".getBytes(StandardCharsets.UTF_8)), first.reviews()));

        ReviewedCorpusClassification result = boundary.classify(
                ReviewWorkflowFixtures.withReviews(bundle, tampered));

        assertThat(result.classification()).isEqualTo(CorpusUsageClassification.INVALID);
        assertThat(result.isUsableForDevelopment()).isFalse();
        assertThat(result.issueCodes()).contains(EvaluationErrorCodes.REVIEW_BINDING_STALE);
        ContractAssertions.assertFailsWithCode(
                result::requireUsableForDevelopment, EvaluationErrorCodes.REVIEW_DECISION_INVALID);
    }

    @Test
    void classificationIsDeterministic() {
        ReviewedCorpusClassification first = boundary.classify(bundle);
        ReviewedCorpusClassification second = boundary.classify(bundle);

        assertThat(second.classification()).isEqualTo(first.classification());
        assertThat(second.issueCodes()).isEqualTo(first.issueCodes());
        assertThat(second.corpusHash()).isEqualTo(first.corpusHash());
        assertThat(second.reviewSetHash()).isEqualTo(first.reviewSetHash());
    }

    @Test
    void allSixHundredSeventyFiveCommittedReviewsRemainPending() {
        assertThat(bundle.reviews()).hasSize(225);

        int pending = 0;
        int decided = 0;
        for (CaseReviewEvidence review : bundle.reviews()) {
            for (ReviewRole role : ReviewRole.ordered()) {
                if (review.entryFor(role).status() == ReviewStatus.PENDING) {
                    pending++;
                } else {
                    decided++;
                }
            }
        }
        assertThat(pending).isEqualTo(675);
        assertThat(decided).isZero();
    }
}