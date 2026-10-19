package com.company.campaign.segment.resolution;

import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.EvaluationErrorCodes;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.review.ReviewedCorpusClassification;
import com.company.campaign.evaluation.segment.review.SegmentCorpusReviewBoundary;
import com.company.campaign.evaluation.segment.review.testsupport.ReviewWorkflowFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 15: the committed corpus remains 225 cases with 675 {@code PENDING} reviews, any corpus-derived
 * T10 evidence is explicitly {@code DEVELOPMENT_ONLY}, and no Gate {@code PASSED} evidence is emitted.
 *
 * <p>The corpus classification is produced by the T06 review boundary, not by T10: T10 consumes only the
 * classification result and can never turn pending human reviews into a Gate pass.
 */
class IntentDevelopmentOnlyCorpusTest {

    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = ReviewWorkflowFixtures.loadCorpusV1();
    }

    @Test
    void committedCorpusRemainsTwoHundredTwentyFiveCasesWithSixHundredSeventyFivePendingReviews() {
        assertThat(bundle.cases()).hasSize(225);
        assertThat(bundle.expectedResults()).hasSize(225);
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

    @Test
    void corpusDerivedT10EvidenceIsDevelopmentOnlyAndNeverGatePassed() {
        ReviewedCorpusClassification classification = new SegmentCorpusReviewBoundary().classify(bundle);

        assertThat(classification.classification()).isEqualTo(CorpusUsageClassification.DEVELOPMENT_ONLY);
        assertThat(classification.classification()).isNotEqualTo(CorpusUsageClassification.GATE_ELIGIBLE);
        assertThat(classification.isGateEligible()).isFalse();
        assertThat(classification.isUsableForDevelopment()).isTrue();
        assertThat(classification.issueCodes()).contains(EvaluationErrorCodes.REVIEW_PENDING);
        assertFailsWithCode(classification::requireGateEligible, EvaluationErrorCodes.REVIEW_NOT_GATE_ELIGIBLE);
    }
}