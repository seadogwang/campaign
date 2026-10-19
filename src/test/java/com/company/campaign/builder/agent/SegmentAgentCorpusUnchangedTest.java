package com.company.campaign.builder.agent;

import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.review.ReviewedCorpusClassification;
import com.company.campaign.evaluation.segment.review.SegmentCorpusReviewBoundary;
import com.company.campaign.evaluation.segment.review.testsupport.ReviewWorkflowFixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 14: the formal T11 work leaves the committed 225 corpus cases and 675 pending human reviews
 * unchanged, and every corpus-derived artifact stays {@code DEVELOPMENT_ONLY}.
 */
class SegmentAgentCorpusUnchangedTest {

    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = ReviewWorkflowFixtures.loadCorpusV1();
    }

    @Test
    void theCommittedCorpusRemainsTwoHundredTwentyFiveCasesWithSixHundredSeventyFivePendingReviews() {
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
    void corpusDerivedEvidenceRemainsDevelopmentOnly() {
        ReviewedCorpusClassification classification = new SegmentCorpusReviewBoundary().classify(bundle);
        assertThat(classification.classification()).isEqualTo(CorpusUsageClassification.DEVELOPMENT_ONLY);
        assertThat(classification.classification()).isNotEqualTo(CorpusUsageClassification.GATE_ELIGIBLE);
    }
}