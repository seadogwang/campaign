package com.company.campaign.builder.refine;

import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.evaluation.segment.contract.CaseInput;
import com.company.campaign.evaluation.segment.contract.CaseReviewEvidence;
import com.company.campaign.evaluation.segment.contract.CorpusBundle;
import com.company.campaign.evaluation.segment.contract.ExpectedResult;
import com.company.campaign.evaluation.segment.contract.RefinementOperation;
import com.company.campaign.evaluation.segment.contract.RefinementTargetPlaceholder;
import com.company.campaign.evaluation.segment.contract.ReviewRole;
import com.company.campaign.evaluation.segment.contract.ReviewStatus;
import com.company.campaign.evaluation.segment.contract.SegmentCategory;
import com.company.campaign.evaluation.segment.review.CorpusUsageClassification;
import com.company.campaign.evaluation.segment.review.ReviewedCorpusClassification;
import com.company.campaign.evaluation.segment.review.SegmentCorpusReviewBoundary;
import com.company.campaign.evaluation.segment.review.testsupport.ReviewWorkflowFixtures;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.window.LifetimeWindow;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Section 4 of the task: the refinement contract is compatible with the T03 {@code RefinementOperation}
 * vocabulary and with the Category 13 stable target/scope fixtures, without editing a single expected corpus
 * answer.
 */
class SegmentRefinementCorpusCompatibilityTest {

    private static final SegmentRefinementPatchCodec CODEC = new SegmentRefinementPatchCodec();
    private static CorpusBundle bundle;

    @BeforeAll
    static void loadOnce() {
        bundle = ReviewWorkflowFixtures.loadCorpusV1();
    }

    private static List<CaseInput> categoryThirteenCases() {
        List<CaseInput> cases = new ArrayList<>();
        for (CaseInput caseInput : bundle.cases()) {
            if (caseInput.category() == SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE) {
                cases.add(caseInput);
            }
        }
        return cases;
    }

    @Test
    void theRefinementContractSharesTheT03OperationVocabularyExactly() {
        Set<String> corpus = new LinkedHashSet<>();
        for (RefinementOperation operation : RefinementOperation.values()) {
            corpus.add(operation.name());
        }
        Set<String> contract = new LinkedHashSet<>();
        for (RefinementOpKind kind : RefinementOpKind.ordered()) {
            contract.add(kind.name());
        }

        assertThat(contract).isEqualTo(corpus);
        assertThat(contract).containsExactly(
                "ADD_NODE", "REMOVE_NODE", "REPLACE_FIELD", "MOVE_NODE");
    }

    @Test
    void everyCategoryThirteenCaseAddressesItsTargetByStableIdentity() {
        List<CaseInput> cases = categoryThirteenCases();
        assertThat(cases).hasSize(15);

        for (CaseInput caseInput : cases) {
            ExpectedResult expected = bundle.expectedFor(caseInput.caseId());
            assertThat(expected).as(caseInput.caseId()).isNotNull();
            RefinementTargetPlaceholder target = expected.refinementTarget();
            assertThat(target).as(caseInput.caseId()).isNotNull();

            // The corpus operation name maps to one contract kind without aliasing.
            assertThat(RefinementOpKind.valueOf(target.operation().name()).name())
                    .isEqualTo(target.operation().name());

            // Both references are usable as stable node identities in the contract, and neither is an index.
            assertThat(RefinementIdentifiers.require(
                    target.targetRef(), SegmentRefinementErrorCodes.NODE_ID_INVALID, "targetRef"))
                    .isEqualTo(target.targetRef());
            assertThat(RefinementIdentifiers.isPositionalPath(target.targetRef())).isFalse();
            if (target.scopeRef() != null) {
                assertThat(RefinementIdentifiers.require(
                        target.scopeRef(), SegmentRefinementErrorCodes.NODE_ID_INVALID, "scopeRef"))
                        .isEqualTo(target.scopeRef());
                assertThat(RefinementIdentifiers.isPositionalPath(target.scopeRef())).isFalse();
            }
        }
    }

    @Test
    void aPatchCanBeExpressedFromEveryCategoryThirteenTargetAndRoundTrips() {
        for (CaseInput caseInput : categoryThirteenCases()) {
            RefinementTargetPlaceholder target = bundle.expectedFor(caseInput.caseId()).refinementTarget();
            SegmentDsl base = new SegmentDslCodec().parse(
                    new CanonicalJson().writeString(caseInput.currentSegment().content()));
            SegmentRefinementOperation operation = operationFor(target);
            SegmentRefinementPatch patch = SegmentRefinementPatch.publish(
                    base.schemaVersion(), base.editableDslHash(), List.of(operation));

            assertThat(patch.operationIds()).containsExactly("op-1");
            assertThat(patch.operations().get(0).kind().name()).isEqualTo(target.operation().name());
            assertThat(CODEC.parse(CODEC.write(patch))).isEqualTo(patch);
        }
    }

    @Test
    void everyCategoryThirteenBaseSegmentIsAValidEditableT05Document() {
        for (CaseInput caseInput : categoryThirteenCases()) {
            SegmentDsl base = new SegmentDslCodec().parse(
                    new CanonicalJson().writeString(caseInput.currentSegment().content()));

            assertThat(base.subject()).isEqualTo(
                    com.company.campaign.metadata.domain.SubjectKind.MEMBER);
            assertThat(base.editableDslHash()).isNotNull();
            assertThat(base.predicate()).isNotNull();
            assertThat(caseInput.currentSegment().segmentId()).startsWith("segment-base-");
        }
    }

    @Test
    void theCorpusRefusesAnIndexShapedTargetEvenWhenTheCorpusNamesOne() {
        // The contract is stricter than the reserved vocabulary: a numeric or pointer-shaped reference is
        // refused, so a future corpus case cannot quietly reintroduce positional patching.
        assertThatThrownBy(() -> RefinementIdentifiers.require(
                "0", SegmentRefinementErrorCodes.NODE_ID_INVALID, "targetRef"))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED);
        assertThatThrownBy(() -> RefinementIdentifiers.require(
                "/children/1", SegmentRefinementErrorCodes.NODE_ID_INVALID, "targetRef"))
                .isInstanceOf(SegmentRefinementException.class)
                .extracting(failure -> ((SegmentRefinementException) failure).code())
                .isEqualTo(SegmentRefinementErrorCodes.INDEX_PATH_REJECTED);
    }

    @Test
    void theCommittedCorpusAndItsReviewsAreUnchanged() {
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
        assertThat(categoryThirteenCases()).hasSize(15);
        assertThat(EnumSet.allOf(SegmentCategory.class)).contains(
                SegmentCategory.REFINEMENT_ADD_CHANGE_DELETE_MOVE);
    }

    @Test
    void corpusDerivedRefinementEvidenceRemainsDevelopmentOnly() {
        ReviewedCorpusClassification classification = new SegmentCorpusReviewBoundary().classify(bundle);

        assertThat(classification.classification()).isEqualTo(CorpusUsageClassification.DEVELOPMENT_ONLY);
        assertThat(classification.classification()).isNotEqualTo(CorpusUsageClassification.GATE_ELIGIBLE);
    }

    @Test
    void theRefinementResultIsNeverGateEligibleOrProductionEvidence() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30)));

        assertThat(result.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(result.developmentOnly()).isTrue();
        assertThat(ContentHash.sha256(result.canonicalBytes())).isEqualTo(result.resultHash());
        assertThat(result.terminalState()).isEqualTo(SegmentRefinementState.READY_FOR_PREVIEW);
    }

    // ------------------------------------------------------------------ helpers

    private static SegmentRefinementOperation operationFor(RefinementTargetPlaceholder target) {
        String targetRef = target.targetRef();
        String scopeRef = target.scopeRef();
        return switch (target.operation()) {
            case ADD_NODE -> new SegmentRefinementOperation.AddNode(
                    "op-1", scopeRef, null,
                    new com.company.campaign.segment.dsl.predicate.AttributePredicate(
                            targetRef, "member.tier", com.company.campaign.metadata.domain.ComparisonOperator.EQUAL,
                            com.company.campaign.segment.dsl.predicate.ConditionValue.of(
                                    new com.company.campaign.segment.dsl.value.EnumValue("GOLD"))));
            case REMOVE_NODE -> new SegmentRefinementOperation.RemoveNode("op-1", targetRef);
            case REPLACE_FIELD -> new SegmentRefinementOperation.ReplaceField(
                    "op-1", targetRef, RefinementField.WINDOW,
                    new RefinementReplacement.WindowReplacement(new LifetimeWindow()));
            case MOVE_NODE -> new SegmentRefinementOperation.MoveNode("op-1", targetRef, scopeRef, null);
        };
    }
}