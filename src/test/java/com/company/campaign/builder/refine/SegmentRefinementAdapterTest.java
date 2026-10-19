package com.company.campaign.builder.refine;

import com.company.campaign.builder.agent.testsupport.AgentTestFixtures;
import com.company.campaign.builder.refine.testsupport.RefinementTestFixtures;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.builder.tool.SegmentToolCallRecord;
import com.company.campaign.builder.tool.SegmentToolIds;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.ComparisonOperator;
import com.company.campaign.metadata.domain.SubjectKind;
import com.company.campaign.preview.PreviewExecutionContext;
import com.company.campaign.preview.PreviewPresentation;
import com.company.campaign.preview.PreviewResponse;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDsl;
import com.company.campaign.segment.dsl.contract.SegmentDslSchemaVersions;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.predicate.GroupPredicate;
import com.company.campaign.segment.dsl.predicate.Predicate;
import com.company.campaign.segment.dsl.predicate.PredicateType;
import com.company.campaign.segment.dsl.value.MoneyValue;
import com.company.campaign.segment.resolution.ResolutionResult;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3 of the task: the orchestration adapter discovers Metadata first, applies the patch as a host-owned
 * non-Tool stage, then runs genuine T07 validation, T08 planning and T09C Preview through their admitted Tools,
 * and terminates as exactly {@code READY_FOR_PREVIEW}, {@code DENIED} or {@code INVALID} with bounded, complete
 * and deterministic evidence.
 */
class SegmentRefinementAdapterTest {

    private static ResolutionResult tierResolution() {
        return SliceTestFixtures.resolve(
                SliceTestFixtures.intent("Gold会员", SliceTestFixtures.enumTierJson()),
                RefinementTestFixtures.snapshot(), SliceTestFixtures.policy());
    }

    private static SegmentRefinementPatch thirtyDayPatch(SegmentDsl base) {
        return RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30));
    }

    private static RefinementTestFixtures.CountingPreviewPort countingPreviewPort() {
        return new RefinementTestFixtures.CountingPreviewPort(AgentTestFixtures.previewPort(
                RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                SegmentValidationFixtures.allowAll()));
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void reachesReadyForPreviewThroughTheExactOrderedStateAndToolTrace() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.terminalState()).isEqualTo(SegmentRefinementState.READY_FOR_PREVIEW);
        assertThat(result.isReadyForPreview()).isTrue();
        assertThat(result.developmentOnly()).isTrue();
        assertThat(result.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(result.failureCode()).isNull();

        assertThat(result.stateTrace()).extracting(SegmentRefinementTransition::toState).containsExactly(
                SegmentRefinementState.REFINING,
                SegmentRefinementState.DISCOVERING,
                SegmentRefinementState.APPLYING_PATCH,
                SegmentRefinementState.VALIDATING,
                SegmentRefinementState.PLANNING,
                SegmentRefinementState.PREVIEWING,
                SegmentRefinementState.READY_FOR_PREVIEW);
        assertThat(result.stateTrace()).extracting(SegmentRefinementTransition::sequence)
                .containsExactly(0, 1, 2, 3, 4, 5, 6);

        // intent.resolve is deliberately never executed: the invoker permits the host-owned forward rank jump.
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId).containsExactly(
                SegmentToolIds.METADATA_DISCOVER,
                SegmentToolIds.DSL_VALIDATE,
                SegmentToolIds.PLAN_BUILD,
                SegmentToolIds.PREVIEW_SAFE);
        assertThat(result.toolTrace()).allSatisfy(call -> {
            assertThat(call.disposition()).isEqualTo(Disposition.RESOLVED);
            assertThat(call.code()).isNull();
            assertThat(call.inputFingerprint()).isNotNull();
        });
        assertThat(result.toolTrace().get(0).rank()).isZero();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::rank).containsExactly(0, 2, 3, 4);
    }

    @Test
    void theResultBindsEveryPinTheCallerNeedsToDetectAStalePresentation() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = thirtyDayPatch(base);
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, patch);

        assertThat(result.baseDslHash()).isEqualTo(base.editableDslHash());
        assertThat(result.refinedDslHash()).isNotEqualTo(base.editableDslHash());
        assertThat(result.patchFingerprint()).isEqualTo(patch.patchFingerprint());
        assertThat(result.skillId()).isEqualTo("segment.rule-based");
        assertThat(result.skillHash()).isEqualTo(RefinementTestFixtures.skill().canonicalHash());
        assertThat(result.allowedToolIds()).isEqualTo(RefinementTestFixtures.catalog().allowedToolIds());
        assertThat(result.metadataSnapshotHash()).isEqualTo(RefinementTestFixtures.snapshot().contentHash());
        assertThat(result.asOf()).isEqualTo(RefinementTestFixtures.context().snapshotRef().asOf().toString());
        assertThat(result.timezone()).isEqualTo(RefinementTestFixtures.context().snapshotRef().timezone().getId());
        assertThat(result.dataWatermark())
                .isEqualTo(RefinementTestFixtures.context().snapshotRef().dataWatermark().toString());
        assertThat(List.of(result.canonicalAstHash(), result.planHash(), result.previewHash(), result.resultHash()))
                .doesNotContainNull();

        assertThat(result.previewCard()).isNotNull();
        assertThat(result.previewCard().matchedCount()).isEqualTo(AgentTestFixtures.MEMBERS.size());
        assertThat(result.previewCard().previewHash()).isEqualTo(result.previewHash());
        assertThat(result.previewCard().planHash()).isEqualTo(result.planHash());
        assertThat(result.previewCard().dslHash()).isEqualTo(result.refinedDslHash());
        assertThat(result.previewCard().canonicalAstHash()).isEqualTo(result.canonicalAstHash());
        assertThat(result.previewCard().skillHash()).isEqualTo(result.skillHash());
        assertThat(result.previewCard().metadataSnapshotId())
                .isEqualTo(RefinementTestFixtures.snapshot().snapshotId());
        assertThat(result.previewCard().hasSample()).isFalse();
        assertThat(result.hasGroundedExplanation()).isFalse();
    }

    @Test
    void theReportedRefinedHashIsTheCanonicalHashOfTheRealThirtyDayDocument() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));

        SegmentDsl expected = new SegmentRefinementEditor().apply(base, thirtyDayPatch(base));
        assertThat(result.refinedDslHash()).isEqualTo(expected.editableDslHash());
        assertThat(new com.company.campaign.segment.dsl.codec.SegmentDslCodec().writeString(expected))
                .contains("\"amount\":30");
    }

    @Test
    void theResultCarriesTheRefinedDslAsAContinuationArtifactBoundToEveryHash() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = thirtyDayPatch(base);
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, patch);

        SegmentDsl expected = new SegmentRefinementEditor().apply(base, patch);
        assertThat(result.refinedDsl()).isEqualTo(expected);
        // The typed continuation document is exactly the one the refined hash and the Preview card bind.
        assertThat(result.refinedDsl().editableDslHash()).isEqualTo(result.refinedDslHash());
        assertThat(result.previewCard().dslHash()).isEqualTo(result.refinedDsl().editableDslHash());
        assertThat(result.refinedDsl().predicate()).isNotSameAs(base.predicate());
        assertThat(result.hasGroundedExplanation()).isFalse();
    }

    @Test
    void aSecondRefinementContinuesFromTheFirstResultsRefinedDslAndBothPreviewsSeeTheSuccessiveDocuments() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        RefinementTestFixtures.RecordingDocumentPreviewPort port =
                new RefinementTestFixtures.RecordingDocumentPreviewPort(AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));
        SegmentRefinementEnvironment environment =
                RefinementTestFixtures.environment(SegmentValidationFixtures.allowAll(), port);

        SegmentRefinementPatch first = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceWindow("op-1", "n-spend", 30));
        SegmentRefinementResult firstResult = RefinementTestFixtures.run(environment, base, first);
        assertThat(firstResult.disposition()).isEqualTo(Disposition.RESOLVED);

        // The second request uses the first result's typed refined DSL directly, with a patch bound to its hash.
        SegmentRefinementPatch second = RefinementTestFixtures.patch(firstResult.refinedDsl(),
                RefinementTestFixtures.replaceValue("op-2", "n-spend", new MoneyValue("250.00", "CNY")));
        SegmentRefinementResult secondResult = RefinementTestFixtures.run(
                environment, firstResult.refinedDsl(), second);

        assertThat(secondResult.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(secondResult.baseDslHash()).isEqualTo(firstResult.refinedDslHash());
        assertThat(secondResult.refinedDsl()).isEqualTo(
                new SegmentRefinementEditor().apply(firstResult.refinedDsl(), second));
        assertThat(secondResult.refinedDsl().editableDslHash()).isEqualTo(secondResult.refinedDslHash());

        // Both Preview runs were handed exactly the successive documents, in order, with samples disabled.
        assertThat(port.documents()).containsExactly(firstResult.refinedDsl(), secondResult.refinedDsl());
        assertThat(port.documents().get(1)).isNotEqualTo(port.documents().get(0));
        assertThat(port.presentations()).extracting(PreviewPresentation::includeSample)
                .containsExactly(false, false);

        // A patch still bound to the original document is stale on step two.
        SegmentRefinementResult stale = RefinementTestFixtures.run(
                environment, firstResult.refinedDsl(), first);
        assertThat(stale.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(stale.failureCode()).isEqualTo(SegmentRefinementErrorCodes.BASE_HASH_MISMATCH);
        assertThat(stale.refinedDsl()).isNull();
    }

    @Test
    void previewIsAlwaysInvokedWithSamplesDisabled() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        AgentTestFixtures.RecordingPreviewPort port = new AgentTestFixtures.RecordingPreviewPort(
                AgentTestFixtures.previewPort(
                        RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                        SegmentValidationFixtures.allowAll()));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(SegmentValidationFixtures.allowAll(), port),
                base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(port.presentations()).hasSize(1);
        assertThat(port.presentations()).extracting(PreviewPresentation::includeSample).containsExactly(false);
        assertThat(port.presentations()).extracting(PreviewPresentation::includeConditionStats)
                .containsExactly(true);
        assertThat(result.previewCard().sampleTokens()).isNull();
    }

    @Test
    void anIncoherentSampleDespiteNoRequestFailsClosed() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        PreviewResponse withSample = new PreviewResponse(
                "SUCCESS", "sha256:" + "1".repeat(64), "qp-1", "sha256:" + "2".repeat(64), 3, null,
                List.of("sha256:" + "3".repeat(64)),
                new PreviewExecutionContext(
                        RefinementTestFixtures.snapshot().snapshotId(), "2026-10-01T10:00:00+08:00",
                        "Asia/Shanghai", "2026-10-01T09:55:00+08:00"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(
                        SegmentValidationFixtures.allowAll(),
                        RefinementTestFixtures.previewPortReturning(withSample)),
                base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("previewFailure"));
        assertThat(result.previewCard()).isNull();
        assertThat(result.previewHash()).isNull();
    }

    // ------------------------------------------------------------------ grounded explanation

    @Test
    void theGroundedExplanationIsRebuiltOnlyThroughItsAdmittedToolWhenItsEvidenceHonestlyHolds() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base), tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.hasGroundedExplanation()).isTrue();
        assertThat(result.explanation().claims()).isNotEmpty();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId).containsExactly(
                SegmentToolIds.METADATA_DISCOVER,
                SegmentToolIds.DSL_VALIDATE,
                SegmentToolIds.PLAN_BUILD,
                SegmentToolIds.PREVIEW_SAFE,
                SegmentToolIds.EXPLANATION_GROUNDED);
        assertThat(result.explanation().claims()).allSatisfy(claim -> {
            assertThat(claim.claimId()).matches("claim-[0-9]+");
            assertThat(claim).hasNoNullFieldsOrPropertiesExcept(
                    "clauseIds", "nodeIds", "definitionIds", "operator", "valueSummary", "windowSummary",
                    "previewStatisticPath", "previewStatisticSummary");
        });
        // The rebuilt explanation is grounded on the refined document, not on the base one.
        assertThat(result.explanation().claims()).anySatisfy(claim ->
                assertThat(claim.windowSummary()).isEqualTo("ROLLING:30:DAY"));
    }

    @Test
    void aPureMoveStillGroundsTheExplanationWhenTheAcceptedClauseKeepsItsCompiledNode() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch move = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-spend", "n-root", "n-tier"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, move, tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.hasGroundedExplanation()).isTrue();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .contains(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void aValueReplacementStillGroundsTheExplanationWhenTheAcceptedClauseKeepsItsCompiledNode() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue("op-1", "n-spend", new MoneyValue("250.00", "CNY")));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, patch, tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.hasGroundedExplanation()).isTrue();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .contains(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void aRemovalSkipsTheExplanationEvenWhenTheDefinitionStillExistsElsewhere() {
        SegmentDsl base = RefinementTestFixtures.duplicateDefinitionDocument();
        SegmentRefinementPatch remove = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.RemoveNode("op-1", "n-tier-alt"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, remove, tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        // The removed clause's definition is still referenced by the surviving node, so a definition-presence
        // check alone would have wrongly grounded the explanation on the removed clause.
        assertThat(result.refinedDsl().predicate()).isInstanceOf(GroupPredicate.class);
        assertThat(((GroupPredicate) result.refinedDsl().predicate()).children())
                .extracting(Predicate::nodeId).containsExactly("n-tier", "n-spend");
        assertThat(((GroupPredicate) result.refinedDsl().predicate()).children().get(0))
                .isInstanceOf(AttributePredicate.class);
        assertThat(((AttributePredicate) ((GroupPredicate) result.refinedDsl().predicate())
                .children().get(0)).attributeId()).isEqualTo("member.tier");

        assertThat(result.hasGroundedExplanation()).isFalse();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void aMoveSkipsTheExplanationWhenNoSingleNodeProvesTheAcceptedClauseBinding() {
        SegmentDsl base = RefinementTestFixtures.duplicateDefinitionDocument();
        // Two nodes bind member.tier, so the accepted clause's compiled node cannot be identified exactly.
        SegmentRefinementPatch move = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-tier-alt", "n-root", "n-spend"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, move, tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.hasGroundedExplanation()).isFalse();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void noExplanationIsFabricatedWhenNoPriorResolutionExists() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));

        assertThat(result.hasGroundedExplanation()).isFalse();
        assertThat(result.explanation()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    @Test
    void noExplanationIsFabricatedWhenThePriorResolutionNoLongerGroundsTheRefinedDocument() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.RemoveNode("op-1", "n-tier"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, patch, tierResolution());

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.hasGroundedExplanation()).isFalse();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .doesNotContain(SegmentToolIds.EXPLANATION_GROUNDED);
    }

    // ------------------------------------------------------------------ bounded failures

    @Test
    void aStaleBaseHashTerminatesAsInvalidBeforeValidationPlanningOrPreviewRun() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch stale = SegmentRefinementPatch.publish(
                SegmentDslSchemaVersions.SUPPORTED, RefinementTestFixtures.nestedDocument().editableDslHash(),
                List.of(new SegmentRefinementOperation.RemoveNode("op-1", "n-tier")));
        RefinementTestFixtures.CountingPreviewPort port = countingPreviewPort();
        AgentTestFixtures.CountingAuthorization authorization =
                new AgentTestFixtures.CountingAuthorization(SegmentValidationFixtures.allowAll());

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(authorization, port), base, stale);

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentRefinementErrorCodes.BASE_HASH_MISMATCH);
        assertThat(result.refinedDsl()).isNull();
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(result.previewHash()).isNull();
        assertThat(port.calls()).isZero();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER);
        assertThat(result.stateTrace()).extracting(SegmentRefinementTransition::toState).containsExactly(
                SegmentRefinementState.REFINING,
                SegmentRefinementState.DISCOVERING,
                SegmentRefinementState.APPLYING_PATCH,
                SegmentRefinementState.INVALID);
        assertThat(authorization.calls()).isPositive();
    }

    @Test
    void aStructuralPatchFailureTerminatesWithItsOwnBoundedContractCode() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch missingAnchor = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.MoveNode("op-1", "n-tier", "n-root", "n-missing"));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, missingAnchor);

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentRefinementErrorCodes.ANCHOR_NOT_FOUND);
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER);
    }

    @Test
    void aDeniedMetadataDiscoveryStopsTheRunBeforeAnyLaterStage() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        RefinementTestFixtures.CountingPreviewPort port = countingPreviewPort();

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(SegmentValidationFixtures.denyAll(), port),
                base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.terminalState()).isEqualTo(SegmentRefinementState.DENIED);
        assertThat(result.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("metadataDiscoveryFailure"));
        assertThat(result.refinedDslHash()).isNull();
        assertThat(port.calls()).isZero();
        assertThat(result.toolTrace()).hasSize(1);
        assertThat(result.toolTrace().get(0).disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.toolTrace().get(0).inputFingerprint()).isNotNull();
    }

    @Test
    void aSemanticValidationFailureStopsBeforePlanningAndPreview() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        // GREATER_THAN is not an operator member.tier permits for an ENUM attribute, so the genuine T07
        // validator rejects the structurally valid refined document.
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceOperator("op-1", "n-tier", ComparisonOperator.GREATER_THAN));
        RefinementTestFixtures.CountingPreviewPort port = countingPreviewPort();

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(SegmentValidationFixtures.allowAll(), port),
                base, patch);

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("validationFailure"));
        // The patch was applied, but a refused run publishes neither the refined DSL nor any later artifact.
        assertThat(result.refinedDsl()).isNull();
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.canonicalAstHash()).isNull();
        assertThat(result.planHash()).isNull();
        assertThat(port.calls()).isZero();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE);
        assertThat(result.stateTrace()).extracting(SegmentRefinementTransition::toState).containsExactly(
                SegmentRefinementState.REFINING,
                SegmentRefinementState.DISCOVERING,
                SegmentRefinementState.APPLYING_PATCH,
                SegmentRefinementState.VALIDATING,
                SegmentRefinementState.INVALID);
    }

    @Test
    void aPreviewBackendFailureTerminatesAsInvalidWithoutAPreviewCard() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(
                        SegmentValidationFixtures.allowAll(), RefinementTestFixtures.failingPreviewPort()),
                base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("previewFailure"));
        assertThat(result.previewCard()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId).containsExactly(
                SegmentToolIds.METADATA_DISCOVER,
                SegmentToolIds.DSL_VALIDATE,
                SegmentToolIds.PLAN_BUILD,
                SegmentToolIds.PREVIEW_SAFE);
        assertThat(result.toolTrace().get(3).disposition()).isEqualTo(Disposition.INVALID);
    }

    @Test
    void aDocumentThatDoesNotBindTheResolvedSnapshotIsRefused() {
        MetadataBinding foreign = new MetadataBinding(
                "another-snapshot", "1", MetadataSchemaVersions.SUPPORTED,
                RefinementTestFixtures.snapshot().contentHash());
        SegmentDsl other = SegmentDsl.publish(
                SegmentDslSchemaVersions.SUPPORTED, SubjectKind.MEMBER, foreign,
                new GroupPredicate("n-root", PredicateType.ALL,
                        List.of(RefinementTestFixtures.tierEqualsGold("n-tier"))));
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(other,
                new SegmentRefinementOperation.AddNode(
                        "op-1", "n-root", null, RefinementTestFixtures.tierEqualsGold("n-added")));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), other, patch);

        assertThat(result.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(result.failureCode()).isEqualTo(SegmentRefinementAdapterErrorCodes.DSL_BINDING_MISMATCH);
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER);
    }

    // ------------------------------------------------------------------ determinism and hygiene

    @Test
    void identicalExplicitInputsReplayByteIdentically() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = thirtyDayPatch(base);

        byte[] bytes = RefinementTestFixtures.run(RefinementTestFixtures.environment(), base, patch)
                .canonicalBytes();
        for (int iteration = 0; iteration < 100; iteration++) {
            assertThat(RefinementTestFixtures.run(RefinementTestFixtures.environment(), base, patch)
                    .canonicalBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void aChangedPatchChangesTheCoveredHashAndEveryDerivedStageHash() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult thirty = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));
        SegmentRefinementResult fortyFive = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-spend", 45)));

        assertThat(thirty.resultHash()).isNotEqualTo(fortyFive.resultHash());
        assertThat(thirty.patchFingerprint()).isNotEqualTo(fortyFive.patchFingerprint());
        assertThat(thirty.refinedDslHash()).isNotEqualTo(fortyFive.refinedDslHash());
        assertThat(thirty.planHash()).isNotEqualTo(fortyFive.planHash());
        assertThat(thirty.previewHash()).isNotEqualTo(fortyFive.previewHash());
        assertThat(thirty.canonicalAstHash()).isNotEqualTo(fortyFive.canonicalAstHash());
    }

    @Test
    void revalidationCatchesATypeMismatchAndAWindowTheMetricDoesNotSupport() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        // A MONEY metric compared against a STRING operand is structurally fine but semantically invalid.
        SegmentRefinementPatch wrongType = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue(
                        "op-1", "n-spend", new com.company.campaign.segment.dsl.value.StringValue("lots")));
        SegmentRefinementResult typeResult = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, wrongType);
        assertThat(typeResult.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(typeResult.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("validationFailure"));
        assertThat(typeResult.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE);

        // A SELECTED_EVENT window is not a window kind this atomic metric declares support for.
        SegmentRefinementPatch unsupportedWindow = RefinementTestFixtures.patch(base,
                new SegmentRefinementOperation.ReplaceField(
                        "op-1", "n-spend", RefinementField.WINDOW,
                        new RefinementReplacement.WindowReplacement(
                                new com.company.campaign.segment.dsl.window.SelectedEventWindow(
                                        "order.first_positive", List.of()))));
        SegmentRefinementResult windowResult = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, unsupportedWindow);
        assertThat(windowResult.disposition()).isEqualTo(Disposition.INVALID);
        assertThat(windowResult.failureCode())
                .isEqualTo(RefinementTestFixtures.skill().failureCode("validationFailure"));
        assertThat(windowResult.planHash()).isNull();
        assertThat(windowResult.previewHash()).isNull();
    }

    @Test
    void revalidationReauthorizesEveryDefinitionTheRefinedDocumentReferences() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        // The principal can no longer read member.tier, which the base document references: the refinement
        // was applied, and the genuine T07 re-authorization refuses the refined document.
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(
                        RefinementTestFixtures.denyAttribute("member.tier"),
                        AgentTestFixtures.previewPort(
                                RefinementTestFixtures.snapshot(), RefinementTestFixtures.context(),
                                RefinementTestFixtures.denyAttribute("member.tier"))),
                base, thirtyDayPatch(base));

        assertThat(result.disposition()).isEqualTo(Disposition.DENIED);
        assertThat(result.terminalState()).isEqualTo(SegmentRefinementState.DENIED);
        assertThat(result.refinedDslHash()).isNull();
        assertThat(result.previewHash()).isNull();
        assertThat(result.toolTrace()).extracting(SegmentToolCallRecord::toolId)
                .containsExactly(SegmentToolIds.METADATA_DISCOVER, SegmentToolIds.DSL_VALIDATE);
        assertThat(result.toolTrace().get(1).disposition()).isEqualTo(Disposition.DENIED);
    }

    @Test
    void rePlanningAndPreviewChangeOnlyWithTheRefinedDslAndRetainEveryContextPin() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult thirty = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));
        SegmentRefinementResult fortyFive = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base,
                RefinementTestFixtures.patch(base, RefinementTestFixtures.replaceWindow("op-1", "n-spend", 45)));

        assertThat(thirty.baseDslHash()).isEqualTo(fortyFive.baseDslHash());
        assertThat(thirty.metadataSnapshotHash()).isEqualTo(fortyFive.metadataSnapshotHash());
        assertThat(thirty.skillHash()).isEqualTo(fortyFive.skillHash());
        assertThat(thirty.allowedToolIds()).isEqualTo(fortyFive.allowedToolIds());
        assertThat(thirty.asOf()).isEqualTo(fortyFive.asOf());
        assertThat(thirty.timezone()).isEqualTo(fortyFive.timezone());
        assertThat(thirty.dataWatermark()).isEqualTo(fortyFive.dataWatermark());
        assertThat(thirty.previewCard().asOf()).isEqualTo(fortyFive.previewCard().asOf());
        assertThat(thirty.previewCard().dataWatermark()).isEqualTo(fortyFive.previewCard().dataWatermark());
        assertThat(thirty.refinedDslHash()).isNotEqualTo(fortyFive.refinedDslHash());
        assertThat(thirty.planHash()).isNotEqualTo(fortyFive.planHash());
    }

    @Test
    void aRefinementThatReplacesAThresholdPublishesTheMatchingRefinedDocument() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementPatch patch = RefinementTestFixtures.patch(base,
                RefinementTestFixtures.replaceValue("op-1", "n-spend", new MoneyValue("250.00", "CNY")));

        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, patch);

        SegmentDsl expected = new SegmentRefinementEditor().apply(base, patch);
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.refinedDslHash()).isEqualTo(expected.editableDslHash());
        assertThat(new com.company.campaign.segment.dsl.codec.SegmentDslCodec().writeString(expected))
                .contains("\"amount\":\"250.00\"");
    }

    @Test
    void theRefinementEnvironmentCanReuseTheExactAcceptedT11HostEnvironment() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        com.company.campaign.builder.agent.SegmentAgentEnvironment agentEnvironment =
                AgentTestFixtures.environment(
                        AgentTestFixtures.portReturning(SliceTestFixtures.enumTierJson()));
        SegmentRefinementEnvironment reused = SegmentRefinementEnvironment.from(agentEnvironment);

        assertThat(reused.skill()).isSameAs(agentEnvironment.skill());
        assertThat(reused.catalog()).isSameAs(agentEnvironment.catalog());
        assertThat(reused.authorizationService()).isSameAs(agentEnvironment.authorizationService());
        assertThat(reused.previewPort()).isSameAs(agentEnvironment.previewPort());
        // The refinement flow calls no model, so the Agent environment's model port is deliberately not carried.
        assertThat(reused.auditSink()).isNull();

        SegmentRefinementResult result = RefinementTestFixtures.run(reused, base, thirtyDayPatch(base));
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.skillHash()).isEqualTo(agentEnvironment.skill().canonicalHash());
    }

    @Test
    void theResultCarriesNoSqlIdentifierOrMemberData() {
        SegmentDsl base = RefinementTestFixtures.baseDocument();
        SegmentRefinementResult result = RefinementTestFixtures.run(
                RefinementTestFixtures.environment(), base, thirtyDayPatch(base));

        String projected = new String(result.canonicalBytes(), StandardCharsets.UTF_8);
        assertThat(projected)
                .doesNotContain("SELECT")
                .doesNotContain("member_id")
                .doesNotContain("jdbc")
                .doesNotContain(AgentTestFixtures.MEMBERS.get(0));
        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
    }
}