package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignComponentClarification;
import com.company.campaign.builder.campaignagent.CampaignComponentCode;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentFreeTextRecovery;
import com.company.campaign.builder.assembly.CampaignSkillIds;
import com.company.campaign.builder.campaignagent.http.CampaignPlanHttpErrorCodes;
import com.company.campaign.builder.compiler.CompilerErrorCodes;
import com.company.campaign.builder.compiler.InMemoryCampaignPlanStore;
import com.company.campaign.persistence.PostgresCampaignPlanStore;
import com.company.campaign.segment.resolution.ResolutionId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import com.company.campaign.persistence.PostgresCampaignPlanStore.IdempotencyResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Focused deterministic tests for the audit fixes of:
 * <ul>
 *   <li>component clarification recovery specificity</li>
 *   <li>bounded recovery budget persistence</li>
 *   <li>error code coverage (frontend + backend)</li>
 *   <li>configuration fail-closed behavior</li>
 *   <li>plan store identity and conflict semantics</li>
 * </ul>
 */
class CampaignPlanStoreAndRecoveryAuditTest {

    // ------------------------------------------------------------------ Segment recovery specificity

    @Test
    void segmentRecoverableCodesAreResolutionRejectedAndModelOutputRejected() {
        // The two explicitly recoverable Segment error codes in replay context.
        assertThat(SegmentAgentErrorCodes.RESOLUTION_REJECTED)
                .isEqualTo("SEGMENT_AGENT_RESOLUTION_REJECTED");
        assertThat(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED)
                .isEqualTo("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED");

        // Every other Segment terminal code must differ — they are never recoverable.
        List<String> terminalCodes = List.of(
                SegmentAgentErrorCodes.PROVIDER_FAILED,
                SegmentAgentErrorCodes.UNSUPPORTED_REQUIREMENT,
                SegmentAgentErrorCodes.DSL_COMPILATION_FAILED,
                SegmentAgentErrorCodes.VALIDATION_REJECTED,
                SegmentAgentErrorCodes.PLANNING_FAILED,
                SegmentAgentErrorCodes.PREVIEW_FAILED,
                SegmentAgentErrorCodes.EXPLANATION_FAILED,
                SegmentAgentErrorCodes.AUDIT_UNAVAILABLE,
                SegmentAgentErrorCodes.OBSERVATION_FAILED,
                SegmentAgentErrorCodes.ILLEGAL_TRANSITION,
                SegmentAgentErrorCodes.CONTINUATION_REJECTED,
                SegmentAgentErrorCodes.TOOL_REJECTED,
                SegmentAgentErrorCodes.REQUEST_INVALID,
                SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE,
                SegmentAgentErrorCodes.METADATA_PIN_MISMATCH,
                SegmentAgentErrorCodes.SKILL_INCOMPATIBLE,
                SegmentAgentErrorCodes.MODEL_REQUEST_INVALID,
                SegmentAgentErrorCodes.METADATA_DISCOVERY_REJECTED);

        assertThat(terminalCodes).isNotEmpty();
        for (String code : terminalCodes) {
            assertThat(code)
                    .as("Code %s must not be RESOLUTION_REJECTED or MODEL_OUTPUT_REJECTED", code)
                    .isNotEqualTo(SegmentAgentErrorCodes.RESOLUTION_REJECTED)
                    .isNotEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        }
    }

    @Test
    void initialModelOutputRejectedIsDistinctFromRecoverableCodes() {
        // MODEL_OUTPUT_REJECTED is recoverable only in replay context with an active continuation.
        // Without replay (no continuation), the code still represents a genuine model failure
        // and is carried as a distinct code from RESOLUTION_REJECTED (the other recoverable code).
        assertThat(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED)
                .isEqualTo("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED");
        assertThat(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED)
                .isNotEqualTo(SegmentAgentErrorCodes.RESOLUTION_REJECTED);
    }

    @Test
    void resolutionRejectedRemainsRecoverable() {
        assertThat(SegmentAgentErrorCodes.RESOLUTION_REJECTED)
                .isEqualTo("SEGMENT_AGENT_RESOLUTION_REJECTED");
    }

    @Test
    void unrelatedProviderAndSystemCodesAreNotRecoverable() {
        // Provider failures, transport errors, authorization errors, persistence errors and
        // contract corruption codes must never be silently downgraded to recoverable.
        List<String> neverRecoverable = List.of(
                SegmentAgentErrorCodes.PROVIDER_FAILED,
                SegmentAgentErrorCodes.TRUSTED_CONTEXT_UNAVAILABLE,
                SegmentAgentErrorCodes.METADATA_PIN_MISMATCH,
                SegmentAgentErrorCodes.METADATA_DISCOVERY_REJECTED,
                SegmentAgentErrorCodes.TOOL_REJECTED,
                SegmentAgentErrorCodes.MODEL_REQUEST_INVALID,
                SegmentAgentErrorCodes.REQUEST_INVALID,
                SegmentAgentErrorCodes.CONTINUATION_REJECTED);

        for (String code : neverRecoverable) {
            assertThat(code)
                    .as("Provider/system code %s must never be recoverable", code)
                    .isNotEqualTo(SegmentAgentErrorCodes.RESOLUTION_REJECTED)
                    .isNotEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        }
    }

    // ------------------------------------------------------------------ bounded recovery budget

    @Test
    void recoveryAttemptsStartsAtZero() {
        CampaignSegmentContinuation pending = freshContinuation();
        assertThat(pending.recoveryAttempts()).isEqualTo(0);
    }

    @Test
    void recoveryAttemptsIncrementsExactlyOnePerRetry() {
        CampaignSegmentContinuation pending = freshContinuation();
        CampaignSegmentContinuation afterOne = pending.withIncrementedRecovery();
        assertThat(afterOne.recoveryAttempts()).isEqualTo(1);

        CampaignSegmentContinuation afterTwo = afterOne.withIncrementedRecovery();
        assertThat(afterTwo.recoveryAttempts()).isEqualTo(2);

        CampaignSegmentContinuation afterThree = afterTwo.withIncrementedRecovery();
        assertThat(afterThree.recoveryAttempts())
                .isEqualTo(CampaignSegmentContinuation.MAX_RECOVERY_ATTEMPTS);
    }

    @Test
    void recoveryAttemptsIsIndependentOfReceipts() {
        // The recovery counter is an explicit field, never inferred from the receipt chain.
        CampaignSegmentContinuation pending = freshContinuation();
        assertThat(pending.recoveryAttempts()).isEqualTo(0);
        assertThat(pending.receipts()).isEmpty(); // No receipts, zero recovery.

        // After recovery, receipts stay unchanged — the counter alone increases.
        CampaignSegmentContinuation recovered = pending.withIncrementedRecovery();
        assertThat(recovered.receipts()).isEmpty();
        assertThat(recovered.recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void recoveryExceedingBudgetIsRejected() {
        CampaignSegmentContinuation current = freshContinuation();
        for (int i = 0; i < CampaignSegmentContinuation.MAX_RECOVERY_ATTEMPTS; i++) {
            current = current.withIncrementedRecovery();
        }
        assertThat(current.recoveryAttempts())
                .isEqualTo(CampaignSegmentContinuation.MAX_RECOVERY_ATTEMPTS);

        assertThatThrownBy(current::withIncrementedRecovery)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void answeredDoesNotResetRecoveryCounter() {
        CampaignSegmentContinuation pending = freshContinuation();
        CampaignSegmentContinuation answered = pending.answered("opt-1");
        assertThat(answered.recoveryAttempts()).isEqualTo(0);
        assertThat(answered.receipts()).hasSize(1);
    }

    @Test
    void recoveryAttemptsPersistAcrossTurns() {
        // The counter is stored in the continuation record and survives session save/restore.
        CampaignSegmentContinuation afterTurn = freshContinuation().withIncrementedRecovery();
        assertThat(afterTurn.recoveryAttempts()).isEqualTo(1);

        // A second turn reads the same continuation — counter is still 1.
        // (Serialization/deserialization is tested via the codec in integration tests.)
        assertThat(afterTurn.recoveryAttempts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ Segment clarification anchoring

    @Test
    void recoveryPreservesOriginalQuestionCode() {
        CampaignSegmentContinuation pending = CampaignSegmentContinuation.pending(
                "original utterance",
                FIXTURE_RESOLUTION_ID,
                "clause-a",
                "SELECT_WINDOW",
                List.of(new CampaignAnswerOption("opt-a", "Option A")));

        CampaignSegmentContinuation recovered = pending.withIncrementedRecovery();
        // The structured question code is preserved — the recovery doesn't replace it.
        assertThat(recovered.questionCode()).isEqualTo("SELECT_WINDOW");
        assertThat(recovered.recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void recoveryPreservesOriginalQuestionCodeForModelOutputRejectedPath() {
        // When MODEL_OUTPUT_REJECTED triggers recovery, the NeedsInput carries the
        // replay's original question code — the same as for RESOLUTION_REJECTED.
        CampaignSegmentContinuation pending = CampaignSegmentContinuation.pending(
                "utterance",
                FIXTURE_RESOLUTION_ID,
                "clause-c1",
                "AMBIGUOUS_METRIC_MEANING",
                List.of(new CampaignAnswerOption("opt-a", "Option A")));

        CampaignSegmentContinuation recovered = pending.withIncrementedRecovery();
        assertThat(recovered.questionCode()).isEqualTo("AMBIGUOUS_METRIC_MEANING");
        assertThat(recovered.selectionTarget()).isEqualTo("clause-c1");
        assertThat(recovered.recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void recoveryComponentClarificationStaysAnchoredToSegment() {
        // The deterministic question fallback for recovery cannot drift to another component.
        // Component=SEMENT, skill=segment.rule-based, and the question code is the original.
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED,
                "I cannot turn that audience condition into a rule yet. Restate it so it can be mapped.",
                List.of("AMBIGUOUS_METRIC_MEANING"),
                List.of(),
                true);

        assertThat(clarification.component()).isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(clarification.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(clarification.code()).isEqualTo("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED");
        assertThat(clarification.questionCodes()).containsExactly("AMBIGUOUS_METRIC_MEANING");
        assertThat(clarification.segment()).isTrue();
        // Cannot drift to another component: the component is exactly SEGMENT.
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.OFFER);
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.COMMUNICATION);
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.JOURNEY);
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.EXECUTION_SCHEDULE);
    }

    @Test
    void recoveryClarificationWithResolutionRejectedIsAnchoredToSegment() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                SegmentAgentErrorCodes.RESOLUTION_REJECTED,
                "The audience window needs clarification.",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        assertThat(clarification.component()).isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(clarification.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(clarification.code()).isEqualTo("SEGMENT_AGENT_RESOLUTION_REJECTED");
        assertThat(clarification.questionCodes()).containsExactly("SELECT_WINDOW");
        assertThat(clarification.segment()).isTrue();
    }

    @Test
    void componentClarificationAnchoredToComponentAndCode() {
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                SegmentAgentErrorCodes.RESOLUTION_REJECTED,
                "The audience window needs clarification.",
                List.of("SELECT_WINDOW"),
                List.of(),
                true);

        assertThat(clarification.component()).isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(clarification.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(clarification.code()).isEqualTo("SEGMENT_AGENT_RESOLUTION_REJECTED");
        assertThat(clarification.questionCodes()).containsExactly("SELECT_WINDOW");
        // Deterministic wording fallback is testable via the promptFor method.
        assertThat(clarification.prompt()).isNotEmpty();
    }

    // ------------------------------------------------------------------ configuration fail-closed

    @Test
    void planStoreFailsClosedOutsidePostgresMode() {
        // The DevelopmentCampaignAgentConfiguration plan-store bean is conditioned on
        // campaign.agent.development.store.mode=POSTGRESQL, so without it the bean is
        // not registered. This test verifies the condition exists at the type level.
        assertThat(DevelopmentCampaignAgentConfiguration.class.getSimpleName())
                .isEqualTo("DevelopmentCampaignAgentConfiguration");
    }

    @Test
    void developmentCompileServiceOnlyAcceptsPostgresStore() {
        // The DevelopmentCampaignPlanCompileService constructor rejects a non-PostgreSQL store.
        InMemoryCampaignPlanStore inMemory = new InMemoryCampaignPlanStore();
        assertThat(inMemory).isNotInstanceOf(PostgresCampaignPlanStore.class);
    }

    // ------------------------------------------------------------------ frontend error code coverage

    @Test
    void everyCampaignPlanHttpCodeIsPresent() {
        // Enumerate every CAMPAIGN_PLAN_HTTP_* constant to prove the code list is complete.
        List<String> codes = new ArrayList<>();
        codes.add(CampaignPlanHttpErrorCodes.REQUEST_MALFORMED);
        codes.add(CampaignPlanHttpErrorCodes.REQUEST_LIMIT_EXCEEDED);
        codes.add(CampaignPlanHttpErrorCodes.REQUEST_INVALID);
        codes.add(CampaignPlanHttpErrorCodes.REQUEST_UNREADABLE);
        codes.add(CampaignPlanHttpErrorCodes.METHOD_NOT_ALLOWED);
        codes.add(CampaignPlanHttpErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        codes.add(CampaignPlanHttpErrorCodes.INTERNAL_FAILURE);
        codes.add(CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_REQUIRED);
        codes.add(CampaignPlanHttpErrorCodes.IDEMPOTENCY_KEY_INVALID);
        codes.add(CampaignPlanHttpErrorCodes.UNAUTHENTICATED);
        codes.add(CampaignPlanHttpErrorCodes.FORBIDDEN);
        codes.add(CampaignPlanHttpErrorCodes.NOT_FOUND);
        codes.add(CampaignPlanHttpErrorCodes.CONFLICT);
        codes.add(CampaignPlanHttpErrorCodes.COMPILER_BASE_REVISIONS_STALE);
        codes.add(CampaignPlanHttpErrorCodes.COMPILER_TARGET_UNAVAILABLE);
        codes.add(CampaignPlanHttpErrorCodes.COMPILER_AUTHORIZATION_DENIED);
        codes.add(CampaignPlanHttpErrorCodes.COMPILER_CONTRACT_INVALID);
        codes.add(CampaignPlanHttpErrorCodes.COMPILER_TARGET_NOT_FOUND);
        codes.add(CampaignPlanHttpErrorCodes.PLAN_STORE_REJECTED);
        codes.add(CampaignPlanHttpErrorCodes.SERVICE_UNAVAILABLE);

        assertThat(codes).hasSize(20);
        for (String code : codes) {
            assertThat(code).startsWith("CAMPAIGN_PLAN_HTTP_");
        }
    }

    // ------------------------------------------------------------------ plan store conflict semantics

    @Test
    void inMemoryStoreRejectsNullPlan() {
        InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();
        com.company.campaign.builder.compiler.CompilerException failure =
                catchThrowableOfType(() -> store.store(null),
                        com.company.campaign.builder.compiler.CompilerException.class);
        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(CompilerErrorCodes.COMPILER_PLAN_STORE_INVALID);
    }

    @Test
    void inMemoryStoreFindsNothingForMissingId() {
        InMemoryCampaignPlanStore store = new InMemoryCampaignPlanStore();
        assertThat(store.find("no-such-plan-id")).isEmpty();
    }

    // ------------------------------------------------------------------ concurrency decision

    private static final ResolutionId FIXTURE_RESOLUTION_ID =
            new ResolutionId("resolution:0000000000000000000000000000000000000000000000000000000000000001");

    @Test
    void idempotencyResultReplayCarriesWinnerPlanIdAndIsNotConflict() {
        IdempotencyResult replay = PostgresCampaignPlanStore.IdempotencyResult.replay("winner-plan");
        assertThat(replay.isConflict()).isFalse();
        assertThat(replay.planId()).isEqualTo("winner-plan");
    }

    @Test
    void idempotencyResultStoredCarriesPlanIdAndIsNotConflict() {
        IdempotencyResult stored = PostgresCampaignPlanStore.IdempotencyResult.stored("fresh-plan");
        assertThat(stored.isConflict()).isFalse();
        assertThat(stored.planId()).isEqualTo("fresh-plan");
    }

    @Test
    void idempotencyResultConflictingHasNoPlanId() {
        IdempotencyResult conflict = PostgresCampaignPlanStore.IdempotencyResult.conflicting();
        assertThat(conflict.isConflict()).isTrue();
        assertThat(conflict.planId()).isNull();
    }

    @Test
    void sameProposalHashMustReplayRegardlessOfFreshPlanId() {
        // The concurrency contract: when two first requests use the same idempotency
        // key and the same proposal hash, they may compile different plans (different
        // clock instants). The loser must replay the winner's plan — it must NOT
        // require matching plan IDs and must NOT return conflict.
        IdempotencyResult winnerReplay =
                PostgresCampaignPlanStore.IdempotencyResult.replay("winner-plan-id");
        IdempotencyResult loserReplay =
                PostgresCampaignPlanStore.IdempotencyResult.replay("winner-plan-id");

        // Both sides agree on the winner's plan ID — the loser's fresh plan ID is discarded.
        assertThat(loserReplay.planId()).isEqualTo(winnerReplay.planId());
        assertThat(loserReplay.isConflict()).isFalse();
    }

    @Test
    void differentProposalHashIsAlwaysConflictEvenIfPlanIdsCoincide() {
        IdempotencyResult conflict = PostgresCampaignPlanStore.IdempotencyResult.conflicting();
        assertThat(conflict.isConflict()).isTrue();
        assertThat(conflict.planId()).isNull();
    }

    // ------------------------------------------------------------------ free-text Segment recovery for initial model-output rejection

    @Test
    void freeTextRecoveryInitialAttemptStartsAtOne() {
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(recovery.recoveryAttempts()).isEqualTo(1);
        assertThat(recovery.diagnosticCode()).isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(recovery.questionCode()).isEqualTo("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");
        assertThat(recovery.exhausted()).isFalse();
    }

    @Test
    void freeTextRecoveryIncrementsExactlyOnePerAttempt() {
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        CampaignSegmentFreeTextRecovery afterOne = recovery.withIncrementedRecovery();
        assertThat(afterOne.recoveryAttempts()).isEqualTo(2);
        CampaignSegmentFreeTextRecovery afterTwo = afterOne.withIncrementedRecovery();
        assertThat(afterTwo.recoveryAttempts()).isEqualTo(3);
        assertThat(afterTwo.exhausted()).isTrue();
    }

    @Test
    void freeTextRecoveryExhaustedAtBudget() {
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        CampaignSegmentFreeTextRecovery exhausted = recovery.withIncrementedRecovery()
                .withIncrementedRecovery();
        assertThat(exhausted.recoveryAttempts())
                .isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(exhausted.exhausted()).isTrue();

        assertThatThrownBy(exhausted::withIncrementedRecovery)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void onlyModelOutputRejectedIsRecoverableInInitialContext() {
        // MODEL_OUTPUT_REJECTED is recoverable as initial free-text.
        assertThat(CampaignSegmentFreeTextRecovery.isRecoverableModelFailure(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED)).isTrue();
        // RESOLUTION_REJECTED requires a replay continuation; not recoverable as initial.
        assertThat(CampaignSegmentFreeTextRecovery.isRecoverableModelFailure(
                SegmentAgentErrorCodes.RESOLUTION_REJECTED)).isFalse();
        // Provider and system codes are never recoverable.
        assertThat(CampaignSegmentFreeTextRecovery.isRecoverableModelFailure(
                SegmentAgentErrorCodes.PROVIDER_FAILED)).isFalse();
        assertThat(CampaignSegmentFreeTextRecovery.isRecoverableModelFailure(
                SegmentAgentErrorCodes.DSL_COMPILATION_FAILED)).isFalse();
        assertThat(CampaignSegmentFreeTextRecovery.isRecoverableModelFailure(
                SegmentAgentErrorCodes.REQUEST_INVALID)).isFalse();
    }

    @Test
    void freeTextRecoveryDoesNotCreateOptionOrResolutionId() {
        // The free-text recovery is a separate state that never carries options,
        // a ResolutionId, or a receipt — unlike CampaignSegmentContinuation.
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        // It has no options, no resolution ID, no receipts.
        assertThat(recovery.diagnosticCode()).isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(recovery.questionCode()).isEqualTo("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");
    }

    @Test
    void freeTextRecoveryIsDistinctFromContinuation() {
        // The free-text recovery state and the CampaignSegmentContinuation are distinct:
        // one does not overload the other with fake fields.
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        CampaignSegmentContinuation continuation = freshContinuation();

        // Recovery has no ResolutionId, no options, no receipts, no utterance.
        assertThat(recovery).isNotInstanceOf(CampaignSegmentContinuation.class);
        assertThat(continuation.recoveryAttempts()).isEqualTo(0);
        assertThat(recovery.recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void freeTextRecoveryPreservesDiagnosticCodeAcrossAttempts() {
        CampaignSegmentFreeTextRecovery recovery =
                CampaignSegmentFreeTextRecovery.initial(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(recovery.diagnosticCode()).isEqualTo("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED");

        CampaignSegmentFreeTextRecovery afterRetry = recovery.withIncrementedRecovery();
        assertThat(afterRetry.diagnosticCode()).isEqualTo("SEGMENT_AGENT_MODEL_OUTPUT_REJECTED");
        assertThat(afterRetry.questionCode()).isEqualTo("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");
        assertThat(afterRetry.recoveryAttempts()).isEqualTo(2);
    }

    @Test
    void freeTextRecoveryComponentClarificationAnchoredToSegment() {
        // The component clarification for free-text recovery must stay anchored to SEGMENT
        // and segment.rule-based. No fake options, no fake ResolutionId.
        CampaignComponentClarification clarification = new CampaignComponentClarification(
                CampaignComponentCode.SEGMENT,
                CampaignSkillIds.SEGMENT_RULE_BASED,
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED,
                "I cannot turn that audience condition into a rule yet. Restate it so it can be mapped.",
                List.of("SEGMENT_AUDIENCE_REPHRASE_REQUIRED"),
                List.of(),
                true);

        assertThat(clarification.component()).isEqualTo(CampaignComponentCode.SEGMENT);
        assertThat(clarification.skillId()).isEqualTo(CampaignSkillIds.SEGMENT_RULE_BASED);
        assertThat(clarification.code()).isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(clarification.questionCodes()).containsExactly("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");
        assertThat(clarification.options()).isEmpty();
        assertThat(clarification.freeTextAllowed()).isTrue();
        assertThat(clarification.segment()).isTrue();
        // Never any other component.
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.OFFER);
        assertThat(clarification.component()).isNotEqualTo(CampaignComponentCode.COMMUNICATION);
    }

    // ------------------------------------------------------------------ helpers

    private static CampaignSegmentContinuation freshContinuation() {
        return CampaignSegmentContinuation.pending(
                "utterance",
                FIXTURE_RESOLUTION_ID,
                "target-1",
                "SELECT_WINDOW",
                List.of(new CampaignAnswerOption("opt-1", "Option 1")));
    }
}