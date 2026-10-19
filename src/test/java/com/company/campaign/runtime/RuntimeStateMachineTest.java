package com.company.campaign.runtime;

import com.company.campaign.journey.graph.JourneyNodeKind;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 record and state-machine tests: closed, fail-closed states; frozen {@code asOf}; and hash
 * self-binding that makes a tampered record unrepresentable.
 */
class RuntimeStateMachineTest {

    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final ContentHash HASH = RuntimeSupport.seedHash("state-machine-evidence");

    @Test
    void theExecutionStateMachineIsClosed() {
        assertThat(ExecutionStatus.CREATED.reaches(ExecutionStatus.EVALUATING)).isTrue();
        assertThat(ExecutionStatus.CREATED.reaches(ExecutionStatus.RUNNING)).isTrue();
        assertThat(ExecutionStatus.CREATED.reaches(ExecutionStatus.COMPLETED)).isTrue();
        assertThat(ExecutionStatus.CREATED.reaches(ExecutionStatus.FAILED)).isTrue();
        assertThat(ExecutionStatus.EVALUATING.reaches(ExecutionStatus.RUNNING)).isTrue();
        assertThat(ExecutionStatus.RUNNING.reaches(ExecutionStatus.COMPLETED)).isTrue();
        assertThat(ExecutionStatus.RUNNING.reaches(ExecutionStatus.PARTIAL_FAILED)).isTrue();
        assertThat(ExecutionStatus.RUNNING.reaches(ExecutionStatus.NEEDS_REVIEW)).isTrue();
        assertThat(ExecutionStatus.EVALUATING.reaches(ExecutionStatus.COMPLETED)).isFalse();
        assertThat(ExecutionStatus.COMPLETED.reaches(ExecutionStatus.RUNNING)).isFalse();
        assertThat(ExecutionStatus.FAILED.reaches(ExecutionStatus.COMPLETED)).isFalse();
        assertThat(ExecutionStatus.RUNNING.reaches(ExecutionStatus.RUNNING)).isFalse();
        assertThat(ExecutionStatus.CREATED.isTerminal()).isFalse();
        assertThat(ExecutionStatus.NEEDS_REVIEW.isTerminal()).isTrue();
        assertThat(ExecutionStatus.CREATED.isMaterializable()).isTrue();
        assertThat(ExecutionStatus.RUNNING.isMaterializable()).isFalse();
    }

    @Test
    void theParticipantNodeAndTaskStateMachinesAreClosed() {
        assertThat(ParticipantStatus.CREATED.reaches(ParticipantStatus.RUNNING)).isTrue();
        assertThat(ParticipantStatus.RUNNING.reaches(ParticipantStatus.WAITING)).isTrue();
        assertThat(ParticipantStatus.WAITING.reaches(ParticipantStatus.RUNNING)).isTrue();
        assertThat(ParticipantStatus.COMPLETED.reaches(ParticipantStatus.RUNNING)).isFalse();
        assertThat(ParticipantStatus.CREATED.isUnresolved()).isTrue();
        assertThat(ParticipantStatus.EXITED.isUnresolved()).isFalse();

        assertThat(NodeExecutionStatus.PENDING.reaches(NodeExecutionStatus.RUNNING)).isTrue();
        assertThat(NodeExecutionStatus.RUNNING.reaches(NodeExecutionStatus.WAITING)).isTrue();
        assertThat(NodeExecutionStatus.RETRY_PENDING.reaches(NodeExecutionStatus.RUNNING)).isTrue();
        assertThat(NodeExecutionStatus.SUCCEEDED.reaches(NodeExecutionStatus.RUNNING)).isFalse();
        assertThat(NodeExecutionStatus.WAITING.isTerminal()).isFalse();

        assertThat(RuntimeTaskStatus.DUE.reaches(RuntimeTaskStatus.LEASED)).isTrue();
        assertThat(RuntimeTaskStatus.LEASED.reaches(RuntimeTaskStatus.DUE)).isTrue();
        assertThat(RuntimeTaskStatus.DEFERRED.reaches(RuntimeTaskStatus.DUE)).isTrue();
        assertThat(RuntimeTaskStatus.COMPLETED.reaches(RuntimeTaskStatus.DUE)).isFalse();
        assertThat(RuntimeTaskStatus.DEAD_LETTER.isTerminal()).isTrue();
        assertThat(RuntimeTaskStatus.DUE.isEligible()).isTrue();
        assertThat(RuntimeTaskStatus.LEASED.isEligible()).isFalse();
    }

    @Test
    void anIllegalExecutionTransitionFailsClosed() {
        CampaignExecution execution = CampaignExecution.created(
                "exec-key-1", "tenant-1", "program-1", "campaign-1", "definition-1", HASH, 1L, HASH, HASH,
                AT, AT, AT);
        CampaignExecution running = execution.transitioned(
                ExecutionStatus.RUNNING, 2, 1, null, null, AT.plusSeconds(1));
        assertThat(running.revision()).isEqualTo(1L);
        assertThat(running.asOf()).isEqualTo(execution.asOf());

        assertThatThrownBy(() -> running.transitioned(
                ExecutionStatus.RUNNING, 2, 1, null, null, AT.plusSeconds(2)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID);
        assertThatThrownBy(() -> running.transitioned(
                ExecutionStatus.EVALUATING, 2, 1, null, null, AT.plusSeconds(2)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID);
    }

    @Test
    void anExecutionMustFreezeAsOfToItsOwnNominalInstant() {
        // The canonical constructor is the only path that can even state a diverging asOf, and it refuses one.
        assertThatThrownBy(() -> new CampaignExecution(
                RuntimeVersions.EXECUTION_FORMAT_VERSION, "exec-1", "exec-key-1", "tenant-1", "program-1",
                "campaign-1", "definition-1", HASH, 1L, HASH, HASH, AT, AT.plusSeconds(600),
                AT.plusSeconds(600), ExecutionStatus.CREATED, 0, 0, 0L, null, null, AT, AT,
                RuntimeSupport.seedHash("x")))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("invariant", "asOf");

        // Every factory path freezes it to the nominal instant by construction.
        CampaignExecution created = CampaignExecution.created(
                "exec-key-1", "tenant-1", "program-1", "campaign-1", "definition-1", HASH, 1L, HASH, HASH,
                AT, AT.plusSeconds(600), AT.plusSeconds(900));
        assertThat(created.asOf()).isEqualTo(created.nominalInstant());
        assertThat(created.dueInstant()).isEqualTo(AT.plusSeconds(600));
    }

    @Test
    void aRecordWhoseBytesDoNotMatchItsOwnHashIsUnrepresentable() {
        assertThatThrownBy(() -> new AudienceSnapshotMember(
                RuntimeVersions.AUDIENCE_MEMBER_FORMAT_VERSION, "tenant-1", "exec-1", "member-1", HASH, AT,
                RuntimeSupport.seedHash("not-the-real-hash")))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_INPUT_INVALID);

        assertThatThrownBy(() -> new CampaignMemberEnrollment(
                RuntimeVersions.ENROLLMENT_FORMAT_VERSION, "tenant-1", "campaign-1", "member-1", "exec-1",
                "definition-1", 1L, AT, RuntimeSupport.seedHash("not-the-real-hash")))
                .isInstanceOf(RuntimeBoundaryException.class);

        assertThatThrownBy(() -> new InboxRecord(
                RuntimeVersions.INBOX_FORMAT_VERSION, InboxSourceKind.SCHEDULE_OCCURRENCE, "exec-key-1",
                HASH, "tenant-1", "campaign-1", "definition-1", 1L, "exec-key-1",
                InboxOutcome.EXECUTION_CREATED, AT, RuntimeSupport.seedHash("not-the-real-hash")))
                .isInstanceOf(RuntimeBoundaryException.class);
    }

    @Test
    void anUnsupportedVersionDiscriminatorIsRefused() {
        assertThatThrownBy(() -> new AudienceSnapshotMember(
                "campaign-execution-audience-member/2.0", "tenant-1", "exec-1", "member-1", HASH, AT,
                RuntimeSupport.seedHash("x")))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue("code", RuntimeErrorCodes.RUNTIME_VERSION_UNSUPPORTED);
    }

    @Test
    void aTaskIsCreatedDueAndDeterministicallyIdentified() {
        RuntimeTask first = RuntimeTask.due(
                "tenant-1", "exec-1", "participant-1", "node-start", RuntimeTaskKind.START, AT);
        RuntimeTask second = RuntimeTask.due(
                "tenant-1", "exec-1", "participant-1", "node-start", RuntimeTaskKind.START, AT.plusSeconds(30));

        assertThat(first.taskId()).isEqualTo(second.taskId());
        assertThat(first.status()).isEqualTo(RuntimeTaskStatus.DUE);
        assertThat(first.reasonCode()).isEqualTo("START");
        assertThat(first.attemptNumber()).isZero();
        assertThat(first.revision()).isZero();
        assertThat(first.availableAt()).isEqualTo(AT);
    }

    @Test
    void aParticipantIdentityIsDeterministicAndNotAMemberValue() {
        String first = JourneyParticipant.participantId("tenant-1", "exec-1", "member-9f3a-secret");
        String second = JourneyParticipant.participantId("tenant-1", "exec-1", "member-9f3a-secret");

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("member-9f3a-secret");
        assertThat(first).startsWith("ptp-");
        assertThat(RuntimeSupport.IDENTIFIER.matcher(first).matches()).isTrue();
        assertThat(JourneyParticipant.participantId("tenant-1", "exec-1", "member-other"))
                .isNotEqualTo(first);
    }

    @Test
    void aParticipantWaitIsAlwaysAReasonAndADeadlineTogether() {
        assertThatThrownBy(() -> new JourneyParticipant(
                RuntimeVersions.PARTICIPANT_FORMAT_VERSION, "participant-1", "tenant-1", "exec-1",
                "campaign-1", "definition-1", HASH, HASH, "member-1", "node-1", ParticipantStatus.WAITING,
                "DURATION", null, null, 0L, AT, AT, RuntimeSupport.seedHash("x")))
                .isInstanceOf(RuntimeBoundaryException.class);
    }

    @Test
    void aNodeExecutionPinsItsFrozenNodeKindAndPayloadHash() {
        NodeExecution node = NodeExecution.pending(
                "tenant-1", "exec-1", "participant-1", "node-start", JourneyNodeKind.START, HASH, AT);

        assertThat(node.status()).isEqualTo(NodeExecutionStatus.PENDING);
        assertThat(node.nodeKind()).isEqualTo(JourneyNodeKind.START);
        assertThat(node.nodePayloadHash()).isEqualTo(HASH);
        assertThat(node.revision()).isZero();
        assertThat(node.nodeExecutionId())
                .isEqualTo(NodeExecution.nodeExecutionId(
                        "tenant-1", "exec-1", "participant-1", "node-start"));
    }

    @Test
    void countersCannotClaimMoreTerminalParticipantsThanItEnrolled() {
        assertThatThrownBy(() -> new ExecutionCounters(
                RuntimeVersions.COUNTERS_FORMAT_VERSION, "tenant-1", "exec-1", 5, 1, 1, 0, 1, 0, 0, 0, 0L,
                RuntimeSupport.seedHash("x")))
                .isInstanceOf(RuntimeBoundaryException.class);
    }
}