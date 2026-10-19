package com.company.campaign.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The single host-only transactional boundary every Runtime read and write crosses.
 *
 * <p>There is deliberately one boundary rather than separate read and write collaborators: a compare-and-set
 * that read a revision from one backend and committed it to another would be unprovable, so the revision a
 * caller observed is the revision this very backend re-proves at commit time. The same reasoning forbids
 * wiring an Inbox read from one store and an Execution write to another.
 *
 * <p>Every multi-write operation is one method — {@link #commitDelivery(DeliveryCommit)} and
 * {@link #commitMaterialization(MaterializationCommit)} — so an adapter commits the whole unit and its audit
 * records or rolls all of it back. The invariants an adapter must re-prove inside the transaction, rather than
 * trusting the caller's prechecks, are:
 *
 * <ul>
 *   <li>Inbox uniqueness on {@code (sourceKind, sourceId)}: an exact duplicate returns the stored receipt and
 *       writes nothing, while the same identity with different hash material is a bounded conflict;</li>
 *   <li>Execution uniqueness on {@code (tenantId, executionKey)}: the same key with different hash material is
 *       a bounded conflict, never a second Execution;</li>
 *   <li>Campaign enrollment uniqueness on {@code (tenantId, campaignId, memberId)} across every published
 *       version: an already-enrolled member is excluded, never re-enrolled;</li>
 *   <li>compare-and-set on the Execution revision and on the frozen state machine;</li>
 *   <li>at most one active lease per task, so an unexpired lease is never replaced and an expired one is
 *       reclaimed as a new attempt rather than a silent continuation;</li>
 *   <li>compare-and-set on the exact task revision and lease token, on the Participant revision and
 *       current-node identity, and on the Execution revision of one node completion;</li>
 *   <li>an aggregate recomputed from the whole Participant set rather than accepted from the caller;</li>
 *   <li>atomic rollback of every record and audit when any collaborator fails.</li>
 * </ul>
 *
 * <p>The port is suitable for a future PostgreSQL implementation — each commit is exactly one transaction and
 * each lookup is index-backed by the same keys the uniqueness rules name — but this build wires no JPA, no
 * Flyway and no production adapter.
 */
public interface RuntimeStore {

    // ------------------------------------------------------------------ reads

    /** The Inbox receipt already recorded for one source identity, or empty. */
    Optional<InboxRecord> findInbox(InboxSourceKind sourceKind, String sourceId);

    /** The Execution of one tenant and stable execution key, or empty. */
    Optional<CampaignExecution> findExecution(String tenantId, String executionKey);

    /** The Execution of one tenant and Execution identity, or empty. */
    Optional<CampaignExecution> findExecutionById(String tenantId, String executionId);

    /** The RuntimeTask of one tenant and task identity, or empty when that tenant does not own it. */
    Optional<RuntimeTask> findTask(String tenantId, String taskId);

    /**
     * Every claimable task of one tenant at one claim instant, in deterministic {@code (availableAt, taskId)}
     * order and bounded by the given limit.
     *
     * <p>A task is claimable when it is locally owned work that is due and is either eligible or holds an
     * already expired lease. An unexpired lease is never a candidate, which is what stops one worker from
     * stealing another's in-flight task.
     */
    List<RuntimeTask> findClaimCandidates(String tenantId, Instant claimInstant, int limit);

    /** Every claimable task across every tenant, in the same deterministic order and bound. */
    List<RuntimeTask> findClaimCandidates(Instant claimInstant, int limit);

    /** The NodeExecution of one Participant's frozen node, or empty. */
    Optional<NodeExecution> findNodeExecution(
            String tenantId, String executionId, String participantId, String nodeLogicalId);

    /** Every NodeExecution of one Execution, in deterministic node order. */
    List<NodeExecution> findNodeExecutions(String tenantId, String executionId);

    /** The NodeAttempt with the given identity inside one tenant, or empty. */
    Optional<NodeAttempt> findAttempt(String tenantId, String attemptId);

    /** Every NodeAttempt of one Execution, in deterministic attempt order. */
    List<NodeAttempt> findAttempts(String tenantId, String executionId);

    /** Every {@code ACTION_READY} handoff of one Execution, in deterministic handoff order. */
    List<ActionHandoff> findActionHandoffs(String tenantId, String executionId);

    /**
     * Every outstanding external-action handoff task of one tenant that is due at the given instant, in
     * deterministic {@code (availableAt, taskId)} order and bounded by the given limit.
     *
     * <p>This is the one additive read the external-action layer needs: an {@code ACTION_READY} task is
     * deliberately not locally claimable, so {@link #findClaimCandidates} never returns it and a host that owns
     * the external-action queue needs its own bounded scan. Nothing else about claim eligibility changes.
     */
    List<RuntimeTask> findActionReadyTasks(String tenantId, Instant claimInstant, int limit);

    /**
     * Commits one external-action node completion as one unit, or writes nothing.
     *
     * <p>The additive companion of {@link #commitNodeCompletion(NodeCompletionCommit)} for the one node kind no
     * local worker may claim: it re-proves inside the transaction that the named task is exactly the outstanding
     * {@code ACTION_READY} handoff of this tenant and Execution at the expected revision, that the Participant
     * and NodeExecution compare-and-set expectations still hold, that the node transition is one the frozen
     * machine allows and that the aggregate is exactly the deterministic projection of the whole Participant
     * set. An exact duplicate of an already committed completion returns the committed logical result and writes
     * nothing.
     */
    ExternalActionCompletionResult commitExternalActionCompletion(ExternalActionCompletion commit);

    /** The Campaign enrollment of one member, or empty when this member never enrolled here. */
    Optional<CampaignMemberEnrollment> findEnrollment(String tenantId, String campaignId, String memberId);

    /** The aggregate counters of one Execution, or empty when it has none. */
    Optional<ExecutionCounters> findCounters(String tenantId, String executionId);

    /** Every audience snapshot member of one Execution, in deterministic member order. */
    List<AudienceSnapshotMember> findSnapshotMembers(String tenantId, String executionId);

    /** Every Participant of one Execution, in deterministic Participant order. */
    List<JourneyParticipant> findParticipants(String tenantId, String executionId);

    /** Every RuntimeTask of one Execution, in deterministic task order. */
    List<RuntimeTask> findTasks(String tenantId, String executionId);

    /** Every runtime Outbox/Audit record of one tenant, in commit order. */
    List<RuntimeAuditRecord> findAudits(String tenantId);

    // ------------------------------------------------------------------ writes

    /**
     * Commits one delivery unit — its Inbox receipt, the Execution it may create and its audit record — as one
     * unit, or writes nothing.
     *
     * <p>An exact duplicate returns the stored receipt unchanged and writes nothing. The same source identity
     * or execution key carrying different hash material is a bounded conflict. When the audit collaborator
     * fails, no receipt and no Execution is written.
     */
    DeliveryCommitResult commitDelivery(DeliveryCommit commit);

    /**
     * Commits one materialization unit — the Execution's next revisioned state, its snapshot members, its new
     * enrollments, its Participants, their tasks, its aggregate counters and its audit records — as one unit,
     * or writes nothing.
     *
     * <p>The compare-and-set on {@code expectedExecutionRevision} is re-proved here rather than trusted from
     * the caller's read, and the frozen state machine is re-proved with it. An enrollment that another commit
     * already created is refused as a conflict rather than re-enrolled. When any collaborator fails, no
     * snapshot member, enrollment, Participant, task, counter or Execution change survives.
     */
    MaterializationCommitResult commitMaterialization(MaterializationCommit commit);

    /**
     * Commits one lease claim — the task's next leased revision and its audit record — as one unit, or writes
     * nothing.
     *
     * <p>The compare-and-set on {@code expectedTaskRevision}, the eligibility of the task and the "an unexpired
     * lease cannot be stolen" rule are all re-proved here rather than trusted from the caller's read, because
     * only the store sees the state a competing worker may have just written. A failed collaborator leaves the
     * task exactly as it was.
     */
    TaskClaimResult claimTask(TaskClaimCommit commit);

    /**
     * Commits one node completion — the Execution's next state, the Participant's next revision and position,
     * the NodeExecution's next state, the attempt, the task's next state, every successor task and handoff, the
     * aggregate counters and the audit records — as one unit, or writes nothing.
     *
     * <p>An exact hash-equal duplicate is detected by the identity of the attempt it would write: an attempt
     * that is already stored under that identity and is byte-equal to the submitted one returns the committed
     * logical result and writes nothing, while the same identity carrying different material is a bounded
     * conflict. Every compare-and-set — the task revision and exact lease token, the Participant revision and
     * current-node identity, the Execution revision — is re-proved inside the transaction, and the aggregate is
     * recomputed from the whole Participant set rather than accepted from the caller.
     */
    NodeCompletionResult commitNodeCompletion(NodeCompletionCommit commit);

    /**
     * Makes the guarded {@code DEFERRED} work of one Campaign eligible again, without shifting any availability
     * instant and without touching work that already succeeded.
     *
     * <p>It is the resume half of the pause guard: a paused Campaign's next tasks are retained exactly where
     * they were, and a resume makes the ones that are already due claimable again. The audit record is written
     * only when something was actually released, so a resume that changes nothing writes nothing.
     *
     * @return the number of tasks released
     */
    int releaseGuardedTasks(
            String tenantId, String campaignId, Instant releaseInstant, RuntimeAuditRecord audit);

    /** What one delivery commit stored: the receipt, the Execution it created when it created one, and whether
     * the commit was an exact duplicate that wrote nothing. */
    record DeliveryCommitResult(
            InboxRecord inbox,
            CampaignExecution execution,
            boolean duplicate
    ) {

        public DeliveryCommitResult {
            if (inbox == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "inbox");
            }
        }
    }

    /** What one materialization commit stored: the Execution's committed state and the counts it wrote. */
    record MaterializationCommitResult(
            CampaignExecution execution,
            ExecutionCounters counters,
            int snapshotMemberCount,
            int enrollmentCount,
            int participantCount,
            int taskCount
    ) {

        public MaterializationCommitResult {
            if (execution == null || counters == null) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_INPUT_INVALID, "materializationResult");
            }
        }
    }
}