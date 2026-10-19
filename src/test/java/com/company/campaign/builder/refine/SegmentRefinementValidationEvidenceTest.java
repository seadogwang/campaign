package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.agent.SegmentStageObserver;
import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.contracts.Issue;
import com.company.campaign.metadata.service.MetadataQueryService;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.validation.SegmentValidationErrorCodes;
import com.company.campaign.segment.validation.SegmentValidationPolicy;
import com.company.campaign.segment.validation.SegmentValidationResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 2 of T13-R1: a genuine typed {@code SegmentValidationResult} is handed to the injected
 * observation boundary even when validation refuses the refined document, before T12 terminates — and the
 * typed receipt of the accepted patch is observed beside it.
 */
class SegmentRefinementValidationEvidenceTest {

    private static final class RecordingObserver implements SegmentStageObserver {

        private SegmentValidationResult validation;
        private SegmentRefinementReceipt receipt;

        @Override
        public void validationAccepted(SegmentValidationResult accepted) {
            this.validation = accepted;
        }

        @Override
        public void refinementReceiptAccepted(SegmentRefinementReceipt accepted) {
            this.receipt = accepted;
        }
    }

    private static SegmentValidationPolicy oneNodePolicy() {
        SegmentValidationPolicy standard = SliceTestFixtures.validationPolicy();
        return new SegmentValidationPolicy(
                1, standard.maxPredicateDepth(), standard.maxAnyWidth(), standard.maxAllWidth(),
                standard.maxRelationNestingDepth());
    }

    private static SegmentRefinementEnvironment environment(SegmentStageObserver observer) {
        return SegmentRefinementEnvironment.of(
                        RefinementTestFixtures.skill(),
                        RecordingTrustedContextProvider.of(RefinementTestFixtures.context()),
                        runContext -> RefinementTestFixtures.snapshot(),
                        new MetadataQueryService(
                                SegmentValidationFixtures.allowAll(),
                                AgentTestFixtures.repository(RefinementTestFixtures.snapshot())),
                        SegmentValidationFixtures.allowAll(),
                        oneNodePolicy(),
                        AgentTestFixtures.previewPort(
                                RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                                SegmentValidationFixtures.allowAll()),
                        null,
                        null)
                .withStageObserver(observer);
    }

    @Test
    void anInvalidValidationIsObservedBeforeRefinementTerminates() {
        RecordingObserver observer = new RecordingObserver();
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(
                base, new SegmentRefinementOperation.AddNode(
                        "op-1", base.predicate().nodeId(), null,
                        RefinementTestFixtures.tierEqualsGold("n-added")));

        SegmentRefinementResult result =
                RefinementTestFixtures.run(environment(observer), base, patch);

        // The public envelope stays bounded: a refused validation publishes no refined DSL, plan or Preview.
        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentAgentErrorCodes.VALIDATION_REJECTED);
        assertThat(result.refinedDsl()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();

        // The accepted patch receipt and the genuine typed invalid validation both reached the boundary.
        assertThat(observer.receipt).isNotNull();
        assertThat(observer.receipt.operations()).hasSize(1);
        assertThat(observer.receipt.operations().get(0).kind()).isEqualTo(RefinementOpKind.ADD_NODE);
        assertThat(observer.validation).isNotNull();
        assertThat(observer.validation.valid()).isFalse();
        assertThat(observer.validation.issues()).extracting(Issue::code)
                .contains(SegmentValidationErrorCodes.COMPLEXITY_NODES_EXCEEDED);
    }
}