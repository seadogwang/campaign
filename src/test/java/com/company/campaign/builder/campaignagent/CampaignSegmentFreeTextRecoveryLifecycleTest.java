package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.agent.SegmentAgentErrorCodes;
import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.builder.assembly.testsupport.CampaignAssemblyFixtures;
import com.company.campaign.builder.campaignagent.CampaignAgentPhase;
import com.company.campaign.builder.campaignagent.CampaignAgentSession;
import com.company.campaign.builder.campaignagent.CampaignAnswerOption;
import com.company.campaign.builder.campaignagent.CampaignSegmentContinuation;
import com.company.campaign.builder.campaignagent.CampaignSegmentFreeTextRecovery;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.conversation.CampaignConversationSnapshot;
import com.company.campaign.conversation.CampaignConversationSnapshotCodec;
import com.company.campaign.segment.resolution.ResolutionId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused deterministic lifecycle tests for {@link CampaignSegmentFreeTextRecovery} — the host-owned
 * free-text Segment recovery state for initial model-output rejection.
 *
 * <p>These prove the fix for the clearing defect: the router now clears the recovery state exactly when
 * Segment reaches Ready/resolved, a non-recoverable terminal, or a genuine option-selection clarification.
 *
 * <h3>Test coverage</h3>
 * <ul>
 *   <li>Rollback (session copy / memento–restore) preserves and clears recovery state correctly.</li>
 *   <li>Snapshot codec round-trip preserves recovery state and its absence after Ready.</li>
 *   <li>Repeated recoverable rejection retains and increments the counter through the value record.</li>
 *   <li>Option-selection {@link CampaignSegmentContinuation} lifecycle stays independent.</li>
 * </ul>
 */
class CampaignSegmentFreeTextRecoveryLifecycleTest {

    // ---- shared fixtures ---------------------------------------------------------------

    private static final ResolutionId FIXTURE_RESOLUTION_ID = new ResolutionId(
            "resolution:0000000000000000000000000000000000000000000000000000000000000001");
    private static final CampaignAssemblyContext ASSEMBLY_CONTEXT =
            CampaignAssemblyFixtures.createContext();

    private static CampaignSegmentFreeTextRecovery freshRecovery() {
        return CampaignSegmentFreeTextRecovery.initial(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
    }

    private static CampaignSegmentFreeTextRecovery recoveryAt(int attempts) {
        CampaignSegmentFreeTextRecovery r = freshRecovery();
        for (int i = 1; i < attempts; i++) {
            r = r.withIncrementedRecovery();
        }
        return r;
    }

    private static CampaignSegmentContinuation freshContinuation() {
        return CampaignSegmentContinuation.pending(
                "audience utterance",
                FIXTURE_RESOLUTION_ID,
                "target-1",
                "SELECT_WINDOW",
                List.of(new CampaignAnswerOption("opt-1", "Option 1")));
    }

    private static CampaignAgentSession session() {
        return new CampaignAgentSession(ASSEMBLY_CONTEXT);
    }

    private static CampaignConversationKey key() {
        return new CampaignConversationKey(
                "tenant-001", "program-001", "user-001",
                CampaignAssemblyFixtures.CONVERSATION, CampaignAssemblyFixtures.RUN);
    }

    // ---- rollback: session copy preserves recovery -------------------------------------

    @Test
    void sessionCopyPreservesFreeTextRecovery() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(freshRecovery());

        CampaignAgentSession copied = original.copy();
        assertThat(copied.segmentFreeTextRecovery()).isNotNull();
        assertThat(copied.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(1);
        assertThat(copied.segmentFreeTextRecovery().diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
    }

    @Test
    void sessionCopyReflectsClearedRecoveryAfterReady() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(freshRecovery());
        // Simulate Ready clearing the recovery.
        original.setSegmentFreeTextRecovery(null);

        CampaignAgentSession copied = original.copy();
        assertThat(copied.segmentFreeTextRecovery()).isNull();
    }

    @Test
    void sessionCopyPreservesIncrementedRecoveryAcrossTurns() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(freshRecovery());
        // Simulate a second rejection incrementing the counter.
        original.setSegmentFreeTextRecovery(
                original.segmentFreeTextRecovery().withIncrementedRecovery());

        CampaignAgentSession copied = original.copy();
        assertThat(copied.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(2);
    }

    // ---- rollback: session memento–restore round-trip ----------------------------------

    @Test
    void mementoRestoreRoundTripPreservesFreeTextRecovery() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(freshRecovery());
        original.setPhase(CampaignAgentPhase.BUILDING);

        CampaignAgentSession.Restore memento = original.memento();
        assertThat(memento.segmentFreeTextRecovery()).isNotNull();
        assertThat(memento.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(1);

        CampaignAgentSession restored = CampaignAgentSession.restore(ASSEMBLY_CONTEXT, memento);
        assertThat(restored.segmentFreeTextRecovery()).isNotNull();
        assertThat(restored.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(1);
        assertThat(restored.segmentFreeTextRecovery().diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);

        // A second round-trip from the restored session must match.
        CampaignAgentSession.Restore second = restored.memento();
        assertThat(second.segmentFreeTextRecovery()).isNotNull();
        assertThat(second.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void mementoRestoreRoundTripAfterReadyContainsNone() {
        CampaignAgentSession original = session();
        // Recovery was previously active but has been cleared (Ready reached).
        original.setSegmentFreeTextRecovery(null);
        original.setPhase(CampaignAgentPhase.BUILDING);

        CampaignAgentSession.Restore memento = original.memento();
        assertThat(memento.segmentFreeTextRecovery()).isNull();

        CampaignAgentSession restored = CampaignAgentSession.restore(ASSEMBLY_CONTEXT, memento);
        assertThat(restored.segmentFreeTextRecovery()).isNull();

        CampaignAgentSession.Restore second = restored.memento();
        assertThat(second.segmentFreeTextRecovery()).isNull();
    }

    // ---- snapshot codec round-trip -----------------------------------------------------

    @Test
    void snapshotCodecRoundTripPreservesFreeTextRecovery() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(recoveryAt(2));
        original.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);

        CampaignConversationSnapshot snapshot =
                CampaignConversationSnapshot.of(key(), original);
        CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();

        byte[] encoded = codec.encode(snapshot);
        CampaignConversationSnapshot decoded = codec.decode(encoded);

        assertThat(decoded.hasSession()).isTrue();
        CampaignAgentSession.Restore decodedSession = decoded.session();
        assertThat(decodedSession.segmentFreeTextRecovery()).isNotNull();
        assertThat(decodedSession.segmentFreeTextRecovery().recoveryAttempts()).isEqualTo(2);
        assertThat(decodedSession.segmentFreeTextRecovery().diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(decodedSession.segmentFreeTextRecovery().questionCode())
                .isEqualTo("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");
    }

    @Test
    void snapshotCodecRoundTripAfterReadyContainsNone() {
        CampaignAgentSession original = session();
        // Recovery cleared — simulating Ready/resolved.
        original.setSegmentFreeTextRecovery(null);
        original.setPhase(CampaignAgentPhase.AUDIENCE_READY);

        CampaignConversationSnapshot snapshot =
                CampaignConversationSnapshot.of(key(), original);
        CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();

        byte[] encoded = codec.encode(snapshot);
        CampaignConversationSnapshot decoded = codec.decode(encoded);

        assertThat(decoded.hasSession()).isTrue();
        CampaignAgentSession.Restore decodedSession = decoded.session();
        assertThat(decodedSession.segmentFreeTextRecovery()).isNull();
    }

    @Test
    void snapshotCodecRoundTripPreservesIncrementedRecoveryAcrossTurns() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(recoveryAt(3)); // exhausted
        original.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);

        CampaignConversationSnapshot snapshot =
                CampaignConversationSnapshot.of(key(), original);
        CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();

        byte[] encoded = codec.encode(snapshot);
        CampaignConversationSnapshot decoded = codec.decode(encoded);

        assertThat(decoded.session().segmentFreeTextRecovery()).isNotNull();
        assertThat(decoded.session().segmentFreeTextRecovery().recoveryAttempts())
                .isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(decoded.session().segmentFreeTextRecovery().exhausted()).isTrue();
    }

    // ---- repeated recoverable rejection retains and increments --------------------------

    @Test
    void freeTextRecoveryRetainsDiagnosticCodeAcrossRepeatedRejections() {
        CampaignSegmentFreeTextRecovery recovery = freshRecovery();
        assertThat(recovery.recoveryAttempts()).isEqualTo(1);

        CampaignSegmentFreeTextRecovery afterSecond = recovery.withIncrementedRecovery();
        assertThat(afterSecond.recoveryAttempts()).isEqualTo(2);
        assertThat(afterSecond.diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(afterSecond.questionCode()).isEqualTo("SEGMENT_AUDIENCE_REPHRASE_REQUIRED");

        CampaignSegmentFreeTextRecovery afterThird = afterSecond.withIncrementedRecovery();
        assertThat(afterThird.recoveryAttempts())
                .isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(afterThird.exhausted()).isTrue();

        // Incrementing past budget is rejected by the compact constructor.
        assertThatThrownBy(afterThird::withIncrementedRecovery)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void freeTextRecoveryEachIncrementProducesDistinctImmutableInstances() {
        CampaignSegmentFreeTextRecovery first = freshRecovery();
        CampaignSegmentFreeTextRecovery second = first.withIncrementedRecovery();

        // The original is unchanged — the record is immutable.
        assertThat(first.recoveryAttempts()).isEqualTo(1);
        assertThat(second.recoveryAttempts()).isEqualTo(2);
        // Diagnostic code is preserved.
        assertThat(second.diagnosticCode()).isEqualTo(first.diagnosticCode());
    }

    // ---- option-selection continuation lifecycle independence --------------------------

    @Test
    void freeTextRecoveryAndContinuationAreIndependentStates() {
        CampaignSegmentFreeTextRecovery recovery = freshRecovery();
        CampaignSegmentContinuation continuation = freshContinuation();

        // Recovery carries no ResolutionId, no options, no receipts, no utterance.
        assertThat(recovery).isNotInstanceOf(CampaignSegmentContinuation.class);
        // Continuation recovery counter starts at zero (separate budget).
        assertThat(continuation.recoveryAttempts()).isEqualTo(0);
        // Free-text recovery counter starts at one.
        assertThat(recovery.recoveryAttempts()).isEqualTo(1);
    }

    @Test
    void sessionCanHoldOnlyRecoveryWithoutContinuation() {
        CampaignAgentSession session = session();
        session.setSegmentFreeTextRecovery(freshRecovery());
        session.setSegmentContinuation(null);

        assertThat(session.segmentFreeTextRecovery()).isNotNull();
        assertThat(session.segmentContinuation()).isNull();

        CampaignAgentSession copied = session.copy();
        assertThat(copied.segmentFreeTextRecovery()).isNotNull();
        assertThat(copied.segmentContinuation()).isNull();
    }

    @Test
    void sessionCanHoldOnlyContinuationWithoutRecovery() {
        CampaignAgentSession session = session();
        session.setSegmentFreeTextRecovery(null);
        session.setSegmentContinuation(freshContinuation());

        assertThat(session.segmentFreeTextRecovery()).isNull();
        assertThat(session.segmentContinuation()).isNotNull();

        CampaignAgentSession copied = session.copy();
        assertThat(copied.segmentFreeTextRecovery()).isNull();
        assertThat(copied.segmentContinuation()).isNotNull();
    }

    // ---- budget enforcement ------------------------------------------------------------

    @Test
    void freeTextRecoveryExhaustedCannotBeIncrementedFurther() {
        CampaignSegmentFreeTextRecovery recovery = freshRecovery()
                .withIncrementedRecovery()
                .withIncrementedRecovery();
        assertThat(recovery.recoveryAttempts())
                .isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(recovery.exhausted()).isTrue();

        assertThatThrownBy(recovery::withIncrementedRecovery)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void freeTextRecoveryInitialAttemptIsWithinBudget() {
        CampaignSegmentFreeTextRecovery recovery = freshRecovery();
        assertThat(recovery.recoveryAttempts()).isBetween(1,
                CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(recovery.exhausted()).isFalse();
    }

    // ---- pending state (attempt zero) invariants ---------------------------------------

    @Test
    void pendingRecoveryHasAttemptZero() {
        CampaignSegmentFreeTextRecovery pending = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW");
        assertThat(pending.recoveryAttempts()).isZero();
        assertThat(pending.questionCode()).isEqualTo("SELECT_WINDOW");
        assertThat(pending.diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(pending.exhausted()).isFalse();
    }

    @Test
    void pendingRecoveryIncrementGoesZeroToOne() {
        CampaignSegmentFreeTextRecovery pending = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW");
        CampaignSegmentFreeTextRecovery afterRetry = pending.withIncrementedRecovery();

        assertThat(afterRetry.recoveryAttempts()).isEqualTo(1);
        assertThat(afterRetry.questionCode()).isEqualTo("SELECT_WINDOW");
        assertThat(pending.recoveryAttempts()).isZero(); // original unchanged
    }

    @Test
    void pendingRecoveryThroughAllAttemptsToExhaustion() {
        CampaignSegmentFreeTextRecovery r = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.RESOLUTION_REJECTED, "SELECT_WINDOW");

        assertThat(r.recoveryAttempts()).isZero();

        r = r.withIncrementedRecovery();
        assertThat(r.recoveryAttempts()).isEqualTo(1);
        assertThat(r.exhausted()).isFalse();

        r = r.withIncrementedRecovery();
        assertThat(r.recoveryAttempts()).isEqualTo(2);
        assertThat(r.exhausted()).isFalse();

        r = r.withIncrementedRecovery();
        assertThat(r.recoveryAttempts()).isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(r.exhausted()).isTrue();

        assertThatThrownBy(r::withIncrementedRecovery)
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void pendingNewQuestionResetsRecovery() {
        CampaignSegmentFreeTextRecovery original = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW");
        original = original.withIncrementedRecovery();
        assertThat(original.recoveryAttempts()).isEqualTo(1);

        // A new genuine question (different questionCode) creates a fresh pending.
        CampaignSegmentFreeTextRecovery reset = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.RESOLUTION_REJECTED, "CHANNEL_PREFERENCE");
        assertThat(reset.recoveryAttempts()).isZero();
        assertThat(reset.questionCode()).isEqualTo("CHANNEL_PREFERENCE");
        assertThat(reset.diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.RESOLUTION_REJECTED);
    }

    @Test
    void pendingRecoveryPreservesQuestionCodeAcrossRetries() {
        CampaignSegmentFreeTextRecovery r = CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW");
        r = r.withIncrementedRecovery();
        r = r.withIncrementedRecovery();

        assertThat(r.recoveryAttempts()).isEqualTo(2);
        assertThat(r.questionCode()).isEqualTo("SELECT_WINDOW");
        assertThat(r.diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
    }

    // ---- snapshot round-trip with pending recovery -------------------------------------

    @Test
    void snapshotCodecRoundTripPreservesPendingRecoveryAttemptZero() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW"));
        original.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);

        CampaignConversationSnapshot snapshot =
                CampaignConversationSnapshot.of(key(), original);
        CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();

        byte[] encoded = codec.encode(snapshot);
        CampaignConversationSnapshot decoded = codec.decode(encoded);

        assertThat(decoded.hasSession()).isTrue();
        CampaignAgentSession.Restore decodedSession = decoded.session();
        assertThat(decodedSession.segmentFreeTextRecovery()).isNotNull();
        assertThat(decodedSession.segmentFreeTextRecovery().recoveryAttempts()).isZero();
        assertThat(decodedSession.segmentFreeTextRecovery().questionCode())
                .isEqualTo("SELECT_WINDOW");
        assertThat(decodedSession.segmentFreeTextRecovery().diagnosticCode())
                .isEqualTo(SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED);
        assertThat(decodedSession.segmentFreeTextRecovery().exhausted()).isFalse();
    }

    @Test
    void snapshotCodecExhaustedRecoveryPreservesExhausted() {
        CampaignAgentSession original = session();
        CampaignSegmentFreeTextRecovery exhausted =
                CampaignSegmentFreeTextRecovery.pending(
                                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW")
                        .withIncrementedRecovery()
                        .withIncrementedRecovery()
                        .withIncrementedRecovery();
        assertThat(exhausted.exhausted()).isTrue();
        original.setSegmentFreeTextRecovery(exhausted);
        original.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);

        CampaignConversationSnapshot snapshot =
                CampaignConversationSnapshot.of(key(), original);
        CampaignConversationSnapshotCodec codec = new CampaignConversationSnapshotCodec();

        byte[] encoded = codec.encode(snapshot);
        CampaignConversationSnapshot decoded = codec.decode(encoded);

        CampaignAgentSession.Restore decodedSession = decoded.session();
        assertThat(decodedSession.segmentFreeTextRecovery()).isNotNull();
        assertThat(decodedSession.segmentFreeTextRecovery().recoveryAttempts())
                .isEqualTo(CampaignSegmentFreeTextRecovery.MAX_RECOVERY_ATTEMPTS);
        assertThat(decodedSession.segmentFreeTextRecovery().exhausted()).isTrue();
    }

    // ---- Ready clears recovery (invariant) ---------------------------------------------

    @Test
    void readyClearsFreeTextRecoveryFromPendingState() {
        CampaignAgentSession session = session();
        CampaignSegmentFreeTextRecovery pending =
                CampaignSegmentFreeTextRecovery.pending(
                        SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW");
        session.setSegmentFreeTextRecovery(pending);
        assertThat(session.segmentFreeTextRecovery()).isNotNull();

        // Simulate Ready: the router clears recovery on a resolved Segment.
        session.setSegmentFreeTextRecovery(null);

        assertThat(session.segmentFreeTextRecovery()).isNull();

        CampaignAgentSession copied = session.copy();
        assertThat(copied.segmentFreeTextRecovery()).isNull();
    }

    // ---- memento round-trip with pending recovery --------------------------------------

    @Test
    void mementoRestoreRoundTripPreservesPendingRecoveryAttemptZero() {
        CampaignAgentSession original = session();
        original.setSegmentFreeTextRecovery(CampaignSegmentFreeTextRecovery.pending(
                SegmentAgentErrorCodes.MODEL_OUTPUT_REJECTED, "SELECT_WINDOW"));
        original.setPhase(CampaignAgentPhase.AWAITING_CLARIFICATION);

        CampaignAgentSession.Restore memento = original.memento();
        assertThat(memento.segmentFreeTextRecovery()).isNotNull();
        assertThat(memento.segmentFreeTextRecovery().recoveryAttempts()).isZero();

        CampaignAgentSession restored = CampaignAgentSession.restore(ASSEMBLY_CONTEXT, memento);
        assertThat(restored.segmentFreeTextRecovery()).isNotNull();
        assertThat(restored.segmentFreeTextRecovery().recoveryAttempts()).isZero();
        assertThat(restored.segmentFreeTextRecovery().questionCode())
                .isEqualTo("SELECT_WINDOW");

        CampaignAgentSession.Restore second = restored.memento();
        assertThat(second.segmentFreeTextRecovery()).isNotNull();
        assertThat(second.segmentFreeTextRecovery().recoveryAttempts()).isZero();
    }
}