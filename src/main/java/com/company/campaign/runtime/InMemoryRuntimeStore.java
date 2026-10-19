package com.company.campaign.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Synchronized in-memory {@link RuntimeStore} for tests and local use.
 *
 * <p>Every method is serialized on one monitor, so an idempotency lookup, a uniqueness check, a compare-and-set
 * and the writes of one commit happen as one indivisible step: two concurrent deliveries of one source
 * identity create exactly one receipt and one Execution, two concurrent materializations that read the same
 * Execution revision cannot both advance it, two concurrent claims of one task cannot both lease it, and two
 * concurrent completions of one claimed task cannot both move its Participant.
 *
 * <p>The commit ordering is chosen so the last fallible operation happens before any state write: the injected
 * {@link #beforeCommit()} seam runs first, then the complete audit batch is appended atomically, and only then
 * are the in-memory maps mutated through infallible operations. A failing audit sink or an injected store
 * failure therefore leaves <em>no</em> receipt, <em>no</em>
 * Execution, <em>no</em> snapshot member, <em>no</em> enrollment, <em>no</em> Participant, <em>no</em>
 * NodeExecution, <em>no</em> attempt, <em>no</em> handoff, <em>no</em> task and <em>no</em> audit record behind
 * it, which is exactly the rollback a future PostgreSQL transaction would give.
 *
 * <p>This is a test/local adapter only: T22 implements no JPA, no Flyway and no production PostgreSQL adapter.
 * Every lookup is keyed by the same identity the uniqueness rule names, and the claim scan orders by
 * {@code (availableAt, taskId)} exactly as a future {@code FOR UPDATE SKIP LOCKED} adapter would.
 */
public class InMemoryRuntimeStore implements RuntimeStore {

    private final RuntimeAuditSink auditSink;

    private final Map<String, InboxRecord> inboxBySource = new LinkedHashMap<>();
    private final Map<String, CampaignExecution> executionsByKey = new LinkedHashMap<>();
    private final Map<String, CampaignMemberEnrollment> enrollmentsByMember = new LinkedHashMap<>();
    private final Map<String, ExecutionCounters> countersByExecution = new LinkedHashMap<>();
    private final Map<String, AudienceSnapshotMember> snapshotMembersByIdentity = new LinkedHashMap<>();
    private final Map<String, JourneyParticipant> participantsById = new LinkedHashMap<>();
    private final Map<String, NodeExecution> nodeExecutionsById = new LinkedHashMap<>();
    private final Map<String, NodeAttempt> attemptsById = new LinkedHashMap<>();
    private final Map<String, ActionHandoff> handoffsById = new LinkedHashMap<>();
    private final Map<String, RuntimeTask> tasksById = new LinkedHashMap<>();
    private final List<RuntimeAuditRecord> audits = new ArrayList<>();

    /** Creates a store over an in-memory audit sink. */
    public InMemoryRuntimeStore() {
        this(new InMemoryRuntimeAuditSink());
    }

    /** Creates a store whose audit records are recorded through the supplied sink. */
    public InMemoryRuntimeStore(RuntimeAuditSink auditSink) {
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
    }

    /** The audit collaborator every commit records through. */
    public RuntimeAuditSink auditSink() {
        return auditSink;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public synchronized Optional<InboxRecord> findInbox(InboxSourceKind sourceKind, String sourceId) {
        Objects.requireNonNull(sourceKind, "sourceKind");
        Objects.requireNonNull(sourceId, "sourceId");
        return Optional.ofNullable(inboxBySource.get(key(sourceKind.name(), sourceId)));
    }

    @Override
    public synchronized Optional<CampaignExecution> findExecution(String tenantId, String executionKey) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionKey, "executionKey");
        return Optional.ofNullable(executionsByKey.get(key(tenantId, executionKey)));
    }

    @Override
    public synchronized Optional<CampaignExecution> findExecutionById(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        for (CampaignExecution execution : executionsByKey.values()) {
            if (execution.tenantId().equals(tenantId) && execution.executionId().equals(executionId)) {
                return Optional.of(execution);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<RuntimeTask> findTask(String tenantId, String taskId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(taskId, "taskId");
        RuntimeTask stored = tasksById.get(taskId);
        // A task identity is only resolvable inside its own tenant; another tenant sees nothing at all.
        return stored != null && stored.tenantId().equals(tenantId) ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public synchronized List<RuntimeTask> findClaimCandidates(
            String tenantId, Instant claimInstant, int limit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(claimInstant, "claimInstant");
        return claimCandidates(task -> task.tenantId().equals(tenantId), claimInstant, limit);
    }

    @Override
    public synchronized List<RuntimeTask> findClaimCandidates(Instant claimInstant, int limit) {
        Objects.requireNonNull(claimInstant, "claimInstant");
        return claimCandidates(task -> true, claimInstant, limit);
    }

    @Override
    public synchronized List<RuntimeTask> findActionReadyTasks(
            String tenantId, Instant claimInstant, int limit) {
        requireScanBound(limit);
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(claimInstant, "claimInstant");
        List<RuntimeTask> matches = new ArrayList<>();
        for (RuntimeTask task : tasksById.values()) {
            if (task.tenantId().equals(tenantId)
                    && task.kind() == RuntimeTaskKind.ACTION_READY
                    && (task.status() == RuntimeTaskStatus.DUE || task.status() == RuntimeTaskStatus.DEFERRED)
                    && !task.availableAt().isAfter(claimInstant)) {
                matches.add(task);
            }
        }
        matches.sort(Comparator
                .comparing(RuntimeTask::availableAt)
                .thenComparing(RuntimeTask::taskId));
        return List.copyOf(matches.subList(0, Math.min(matches.size(), limit)));
    }

    @Override
    public synchronized Optional<NodeExecution> findNodeExecution(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(participantId, "participantId");
        Objects.requireNonNull(nodeLogicalId, "nodeLogicalId");
        NodeExecution stored = nodeExecutionsById.get(
                NodeExecution.nodeExecutionId(tenantId, executionId, participantId, nodeLogicalId));
        return stored == null ? Optional.empty() : Optional.of(stored);
    }

    @Override
    public synchronized List<NodeExecution> findNodeExecutions(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<NodeExecution> matches = new ArrayList<>();
        for (NodeExecution node : nodeExecutionsById.values()) {
            if (belongsTo(node.tenantId(), node.executionId(), tenantId, executionId)) {
                matches.add(node);
            }
        }
        matches.sort(Comparator.comparing(NodeExecution::nodeExecutionId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized Optional<NodeAttempt> findAttempt(String tenantId, String attemptId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(attemptId, "attemptId");
        NodeAttempt stored = attemptsById.get(attemptId);
        return stored != null && stored.tenantId().equals(tenantId) ? Optional.of(stored) : Optional.empty();
    }

    @Override
    public synchronized List<NodeAttempt> findAttempts(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<NodeAttempt> matches = new ArrayList<>();
        for (NodeAttempt attempt : attemptsById.values()) {
            if (belongsTo(attempt.tenantId(), attempt.executionId(), tenantId, executionId)) {
                matches.add(attempt);
            }
        }
        matches.sort(Comparator.comparing(NodeAttempt::attemptId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<ActionHandoff> findActionHandoffs(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<ActionHandoff> matches = new ArrayList<>();
        for (ActionHandoff handoff : handoffsById.values()) {
            if (belongsTo(handoff.tenantId(), handoff.executionId(), tenantId, executionId)) {
                matches.add(handoff);
            }
        }
        matches.sort(Comparator.comparing(ActionHandoff::handoffId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized Optional<CampaignMemberEnrollment> findEnrollment(
            String tenantId, String campaignId, String memberId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(memberId, "memberId");
        return Optional.ofNullable(enrollmentsByMember.get(key(tenantId, campaignId, memberId)));
    }

    @Override
    public synchronized Optional<ExecutionCounters> findCounters(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        return Optional.ofNullable(countersByExecution.get(key(tenantId, executionId)));
    }

    @Override
    public synchronized List<AudienceSnapshotMember> findSnapshotMembers(
            String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<AudienceSnapshotMember> matches = new ArrayList<>();
        for (AudienceSnapshotMember member : snapshotMembersByIdentity.values()) {
            if (belongsTo(member.tenantId(), member.executionId(), tenantId, executionId)) {
                matches.add(member);
            }
        }
        matches.sort(Comparator.comparing(AudienceSnapshotMember::memberId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<JourneyParticipant> findParticipants(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<JourneyParticipant> matches = new ArrayList<>();
        for (JourneyParticipant participant : participantsById.values()) {
            if (belongsTo(participant.tenantId(), participant.executionId(), tenantId, executionId)) {
                matches.add(participant);
            }
        }
        matches.sort(Comparator.comparing(JourneyParticipant::participantId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<RuntimeTask> findTasks(String tenantId, String executionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(executionId, "executionId");
        List<RuntimeTask> matches = new ArrayList<>();
        for (RuntimeTask task : tasksById.values()) {
            if (belongsTo(task.tenantId(), task.executionId(), tenantId, executionId)) {
                matches.add(task);
            }
        }
        matches.sort(Comparator
                .comparing(RuntimeTask::availableAt)
                .thenComparing(RuntimeTask::taskId));
        return List.copyOf(matches);
    }

    @Override
    public synchronized List<RuntimeAuditRecord> findAudits(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId");
        List<RuntimeAuditRecord> matches = new ArrayList<>();
        for (RuntimeAuditRecord record : audits) {
            if (record.tenantId().equals(tenantId)) {
                matches.add(record);
            }
        }
        return List.copyOf(matches);
    }

    // ------------------------------------------------------------------ delivery

    @Override
    public synchronized DeliveryCommitResult commitDelivery(DeliveryCommit commit) {
        Objects.requireNonNull(commit, "commit");
        InboxRecord inbox = commit.inbox();
        String inboxKey = key(inbox.sourceKind().name(), inbox.sourceId());

        InboxRecord existing = inboxBySource.get(inboxKey);
        if (existing != null) {
            if (!existing.describes(inbox.sourceKind(), inbox.sourceId(), inbox.sourceHash())) {
                // The same source identity carrying different hash material is a conflict forever.
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_INBOX_CONFLICT, "inboxSourceHash",
                        "An Inbox source identity is already bound to different source material.");
            }
            CampaignExecution stored = commit.execution() == null
                    ? null
                    : executionsByKey.get(key(inbox.tenantId(), inbox.executionKey()));
            if (commit.execution() != null && stored == null) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "inboxExecution",
                        "An accepted Inbox receipt is missing the Execution it created.");
            }
            return new DeliveryCommitResult(existing, stored, true);
        }

        CampaignExecution storedExecution = null;
        if (commit.execution() != null) {
            String executionKey = key(inbox.tenantId(), inbox.executionKey());
            storedExecution = executionsByKey.get(executionKey);
            if (storedExecution != null && !storedExecution.equals(commit.execution())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_EXECUTION_CONFLICT, "executionKey",
                        "An execution key is already bound to different hash material.");
            }
        }
        if (inboxBySource.size() >= RuntimeVersions.MAX_INBOX_RECORDS) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "inbox",
                    "The local runtime store cannot accept another Inbox receipt.");
        }

        // The last fallible collaborator runs before any state is written, so its failure rolls everything back.
        beforeCommit();
        auditSink.recordAll(List.of(commit.audit()));

        inboxBySource.put(inboxKey, inbox);
        if (commit.execution() != null && storedExecution == null) {
            storedExecution = commit.execution();
            executionsByKey.put(key(inbox.tenantId(), inbox.executionKey()), storedExecution);
        }
        audits.add(commit.audit());
        return new DeliveryCommitResult(inbox, storedExecution, false);
    }

    // ------------------------------------------------------------------ materialization

    @Override
    public synchronized MaterializationCommitResult commitMaterialization(MaterializationCommit commit) {
        Objects.requireNonNull(commit, "commit");
        String executionKey = key(commit.tenantId(), commit.executionKey());
        CampaignExecution current = executionsByKey.get(executionKey);
        if (current == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "executionKey",
                    "No Execution is stored under this tenant and execution key.");
        }
        if (current.revision() != commit.expectedExecutionRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "executionRevision",
                    "The Execution moved before this materialization committed.");
        }
        if (!current.executionId().equals(commit.execution().executionId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "executionId",
                    "A stored Execution identity is already bound to different work.");
        }
        if (!current.status().reaches(commit.execution().status())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "executionStatus",
                    "An Execution transition is not one the frozen state machine allows.");
        }
        if (countersByExecution.containsKey(key(commit.tenantId(), commit.execution().executionId()))) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "counters",
                    "An Execution aggregate is written at most once by a materialization.");
        }

        // Every uniqueness rule is re-proved against already-stored state, which only the store can see.
        for (CampaignMemberEnrollment enrollment : commit.enrollments()) {
            String memberKey = key(enrollment.tenantId(), enrollment.campaignId(), enrollment.memberId());
            CampaignMemberEnrollment stored = enrollmentsByMember.get(memberKey);
            if (stored != null) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "enrollment",
                        "A member is already enrolled in this Campaign.");
            }
        }
        Set<String> freshParticipantIds = new LinkedHashSet<>();
        for (JourneyParticipant participant : commit.participants()) {
            if (participantsById.containsKey(participant.participantId())
                    || !freshParticipantIds.add(participant.participantId())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "participant",
                        "A Participant identity is already bound to different work.");
            }
        }
        Set<String> freshTaskIds = new LinkedHashSet<>();
        for (RuntimeTask task : commit.tasks()) {
            if (tasksById.containsKey(task.taskId()) || !freshTaskIds.add(task.taskId())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "task",
                        "A task identity is already bound to different work.");
            }
        }
        boolean duplicateMaterialization = false;
        for (AudienceSnapshotMember member : commit.snapshotMembers()) {
            if (snapshotMembersByIdentity.containsKey(
                    key(member.tenantId(), member.executionId(), member.memberId()))) {
                duplicateMaterialization = true;
                break;
            }
        }
        if (duplicateMaterialization) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "snapshotMember",
                    "An Execution audience is materialized at most once.");
        }

        // The last fallible collaborators run before any state is written.
        beforeCommit();
        auditSink.recordAll(commit.audits());

        executionsByKey.put(executionKey, commit.execution());
        for (AudienceSnapshotMember member : commit.snapshotMembers()) {
            snapshotMembersByIdentity.put(
                    key(member.tenantId(), member.executionId(), member.memberId()), member);
        }
        for (CampaignMemberEnrollment enrollment : commit.enrollments()) {
            enrollmentsByMember.put(
                    key(enrollment.tenantId(), enrollment.campaignId(), enrollment.memberId()), enrollment);
        }
        for (JourneyParticipant participant : commit.participants()) {
            participantsById.put(participant.participantId(), participant);
        }
        for (RuntimeTask task : commit.tasks()) {
            tasksById.put(task.taskId(), task);
        }
        countersByExecution.put(
                key(commit.tenantId(), commit.execution().executionId()), commit.counters());
        audits.addAll(commit.audits());
        return new MaterializationCommitResult(
                commit.execution(), commit.counters(), commit.snapshotMembers().size(),
                commit.enrollments().size(), commit.participants().size(), commit.tasks().size());
    }

    // ------------------------------------------------------------------ claim

    @Override
    public synchronized TaskClaimResult claimTask(TaskClaimCommit commit) {
        Objects.requireNonNull(commit, "commit");
        RuntimeTask stored = tasksById.get(commit.taskId());
        if (stored == null || !stored.tenantId().equals(commit.tenantId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TASK_NOT_FOUND, "taskId",
                    "No RuntimeTask is stored under this tenant and task identity.");
        }
        if (stored.revision() != commit.expectedTaskRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskRevision",
                    "The task moved before this claim committed.");
        }
        if (stored.availableAt().isAfter(commit.claimedAt())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "availableAt",
                    "The task is not due at this claim instant.");
        }
        if (stored.status() == RuntimeTaskStatus.LEASED) {
            if (stored.leaseExpiry().isAfter(commit.claimedAt())) {
                // One task has one active lease: an unexpired one is never stolen.
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_LEASE_HELD, "leaseExpiry",
                        "The task already holds an unexpired lease.");
            }
        } else if (stored.status() != RuntimeTaskStatus.DUE
                && stored.status() != RuntimeTaskStatus.DEFERRED) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskStatus",
                    "This task is not in a state a claim may take a lease from.");
        }
        if (!stored.kind().locallyClaimable()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskKind",
                    "This task is not work a local Runtime worker may claim.");
        }

        boolean reclaimed = stored.status() == RuntimeTaskStatus.LEASED;
        RuntimeTask leased = reclaimed
                ? stored.reclaimed(commit.workerId(), commit.leaseToken(), commit.leaseExpiry(),
                        commit.claimedAt())
                : stored.leased(commit.workerId(), commit.leaseToken(), commit.leaseExpiry(),
                        commit.claimedAt());

        // The last fallible collaborators run before any state is written.
        beforeCommit();
        auditSink.recordAll(List.of(commit.audit()));

        tasksById.put(leased.taskId(), leased);
        audits.add(commit.audit());
        return new TaskClaimResult(leased, reclaimed);
    }

    // ------------------------------------------------------------------ node completion

    @Override
    public synchronized NodeCompletionResult commitNodeCompletion(NodeCompletionCommit commit) {
        Objects.requireNonNull(commit, "commit");
        CampaignExecution storedExecution =
                executionsByKey.get(key(commit.tenantId(), commit.executionKey()));
        if (storedExecution == null || !storedExecution.executionId().equals(commit.executionId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "execution",
                    "No Execution is stored under this tenant, execution key and identity.");
        }
        RuntimeTask storedTask = tasksById.get(commit.taskId());
        if (storedTask == null || !storedTask.tenantId().equals(commit.tenantId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TASK_NOT_FOUND, "taskId",
                    "No RuntimeTask is stored under this tenant and task identity.");
        }

        NodeAttempt existingAttempt = attemptsById.get(commit.attempt().attemptId());
        if (existingAttempt != null) {
            return duplicateCompletion(commit, existingAttempt, storedTask, storedExecution);
        }

        if (storedTask.status() != RuntimeTaskStatus.LEASED
                || storedTask.leaseToken() == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskStatus",
                    "A completion requires a task that currently holds a lease.");
        }
        if (storedTask.revision() != commit.expectedTaskRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskRevision",
                    "The task moved before this completion committed.");
        }
        if (!storedTask.leaseToken().equals(commit.leaseToken())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_LEASE_TOKEN_MISMATCH, "leaseToken",
                    "A completion must name the exact lease token the task was claimed under.");
        }
        JourneyParticipant storedParticipant = participantsById.get(commit.participantId());
        if (storedParticipant == null || !storedParticipant.tenantId().equals(commit.tenantId())
                || !storedParticipant.executionId().equals(commit.executionId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "participant",
                    "No Participant is stored under this tenant, Execution and identity.");
        }
        if (storedParticipant.revision() != commit.expectedParticipantRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_PARTICIPANT_CAS_CONFLICT, "participantRevision",
                    "The Participant moved before this completion committed.");
        }
        if (!storedParticipant.currentNodeLogicalId().equals(commit.expectedParticipantNodeLogicalId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_PARTICIPANT_CAS_CONFLICT, "participantNode",
                    "Stale work names a node the Participant is no longer on.");
        }
        if (storedExecution.revision() != commit.expectedExecutionRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "executionRevision",
                    "The Execution moved before this completion committed.");
        }
        requireExecutionMove(storedExecution, commit.execution());
        requireAggregate(commit);

        for (RuntimeTask next : commit.nextTasks()) {
            if (tasksById.containsKey(next.taskId())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "nextTask",
                        "A successor task identity is already bound to different work.");
            }
        }
        for (ActionHandoff handoff : commit.handoffs()) {
            if (handoffsById.containsKey(handoff.handoffId())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "handoff",
                        "A handoff identity is already bound to different work.");
            }
        }

        // The last fallible collaborators run before any state is written.
        beforeCommit();
        auditSink.recordAll(commit.audits());

        applyCompletion(commit);
        return new NodeCompletionResult(
                commit.task(), commit.participant(), commit.nodeExecution(), commit.execution(),
                commit.counters(), commit.handoffs(), false);
    }

    /**
     * Answers an exact hash-equal replay from the already committed state, and refuses any other reuse.
     *
     * <p>The attempt identity is what makes this decidable: the same identity carrying byte-equal material is
     * the same logical try and reuses the committed result, while the same identity carrying anything else is a
     * bounded conflict that writes nothing.
     */
    private NodeCompletionResult duplicateCompletion(
            NodeCompletionCommit commit,
            NodeAttempt existingAttempt,
            RuntimeTask storedTask,
            CampaignExecution storedExecution) {
        if (!existingAttempt.equals(commit.attempt())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_COMPLETION_CONFLICT, "attempt",
                    "A completed attempt identity is already bound to different material.");
        }
        JourneyParticipant storedParticipant = participantsById.get(commit.participantId());
        NodeExecution storedNode = nodeExecutionsById.get(commit.nodeExecution().nodeExecutionId());
        ExecutionCounters storedCounters =
                countersByExecution.get(key(commit.tenantId(), commit.executionId()));
        if (storedParticipant == null || storedNode == null || storedCounters == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "completion",
                    "A committed duplicate is missing the records it should have written.");
        }
        List<ActionHandoff> storedHandoffs =
                handoffsOf(commit.tenantId(), commit.executionId(), commit.participantId(),
                        storedNode.nodeLogicalId());
        return new NodeCompletionResult(
                storedTask, storedParticipant, storedNode, storedExecution, storedCounters, storedHandoffs,
                true);
    }

    // ------------------------------------------------------------------ external-action node completion

    @Override
    public synchronized ExternalActionCompletionResult commitExternalActionCompletion(
            ExternalActionCompletion commit) {
        Objects.requireNonNull(commit, "commit");
        CampaignExecution storedExecution =
                executionsByKey.get(key(commit.tenantId(), commit.executionKey()));
        if (storedExecution == null || !storedExecution.executionId().equals(commit.executionId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "execution",
                    "No Execution is stored under this tenant, execution key and identity.");
        }
        RuntimeTask storedTask = tasksById.get(commit.taskId());
        if (storedTask == null || !storedTask.tenantId().equals(commit.tenantId())
                || storedTask.kind() != RuntimeTaskKind.ACTION_READY) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TASK_NOT_FOUND, "taskId",
                    "No external-action handoff task is stored under this tenant and task identity.");
        }
        JourneyParticipant storedParticipant = participantsById.get(commit.participantId());
        if (storedParticipant == null || !storedParticipant.tenantId().equals(commit.tenantId())
                || !storedParticipant.executionId().equals(commit.executionId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "participant",
                    "No Participant is stored under this tenant, Execution and identity.");
        }
        NodeExecution storedNode = nodeExecutionsById.get(commit.nodeExecution().nodeExecutionId());
        if (storedTask.status() == RuntimeTaskStatus.COMPLETED) {
            // The one external-action node this handoff owns was already finished: the committed logical
            // result is reused and nothing is written, so a redelivered handoff can never double-apply.
            if (storedNode == null) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "completion",
                        "A completed external-action handoff is missing the node it finished.");
            }
            ExecutionCounters storedCounters =
                    countersByExecution.get(key(commit.tenantId(), commit.executionId()));
            if (storedCounters == null) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "counters",
                        "A completed external-action handoff is missing its aggregate.");
            }
            return new ExternalActionCompletionResult(
                    storedTask, storedParticipant, storedNode, storedExecution, storedCounters,
                    List.of(), true);
        }
        if (storedTask.status() != RuntimeTaskStatus.DUE && storedTask.status() != RuntimeTaskStatus.DEFERRED) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskStatus",
                    "A completion requires an outstanding external-action handoff task.");
        }
        if (storedTask.revision() != commit.expectedTaskRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "taskRevision",
                    "The handoff task moved before this completion committed.");
        }
        if (storedNode == null) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH, "nodeExecution",
                    "No NodeExecution is stored for this external-action handoff task.");
        }
        if (!storedNode.nodeLogicalId().equals(storedTask.nodeLogicalId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_NODE_BINDING_MISMATCH, "nodeExecution",
                    "A stored NodeExecution does not own the node this handoff task names.");
        }
        if (!storedNode.status().reaches(commit.nodeExecution().status())
                || commit.nodeExecution().status() == storedNode.status()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "nodeExecutionStatus",
                    "An external-action node transition is not one the frozen state machine allows.");
        }
        RuntimeTask expectedTask = commit.nodeExecution().status() == NodeExecutionStatus.VERIFYING
                ? storedTask.deferred(commit.task().updatedAt())
                : storedTask.retired(storedTask.completionReasonCode(), commit.task().updatedAt());
        if (!expectedTask.equals(commit.task())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "task",
                    "An external-action completion must apply the exact handoff task transition.");
        }
        if (storedParticipant.revision() != commit.expectedParticipantRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_PARTICIPANT_CAS_CONFLICT, "participantRevision",
                    "The Participant moved before this completion committed.");
        }
        if (!storedParticipant.currentNodeLogicalId().equals(commit.expectedParticipantNodeLogicalId())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_PARTICIPANT_CAS_CONFLICT, "participantNode",
                    "Stale external-action work names a node the Participant is no longer on.");
        }
        if (storedExecution.revision() != commit.expectedExecutionRevision()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "executionRevision",
                    "The Execution moved before this completion committed.");
        }
        requireExecutionMove(storedExecution, commit.execution());
        requireAggregateState(
                commit.tenantId(), commit.executionId(), commit.participantId(), commit.participant(),
                commit.execution(), commit.counters());
        for (RuntimeTask next : commit.nextTasks()) {
            if (tasksById.containsKey(next.taskId())) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_IDENTITY_CONFLICT, "nextTask",
                        "A successor task identity is already bound to different work.");
            }
        }

        // The last fallible collaborators run before any state is written.
        beforeCommit();
        auditSink.recordAll(commit.audits());

        tasksById.put(commit.taskId(), commit.task());
        participantsById.put(commit.participantId(), commit.participant());
        nodeExecutionsById.put(commit.nodeExecution().nodeExecutionId(), commit.nodeExecution());
        for (RuntimeTask next : commit.nextTasks()) {
            tasksById.put(next.taskId(), next);
        }
        executionsByKey.put(key(commit.tenantId(), commit.executionKey()), commit.execution());
        countersByExecution.put(key(commit.tenantId(), commit.executionId()), commit.counters());
        audits.addAll(commit.audits());
        return new ExternalActionCompletionResult(
                commit.task(), commit.participant(), commit.nodeExecution(), commit.execution(),
                commit.counters(), commit.nextTasks(), false);
    }

    @Override
    public synchronized int releaseGuardedTasks(
            String tenantId, String campaignId, Instant releaseInstant, RuntimeAuditRecord audit) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(campaignId, "campaignId");
        Objects.requireNonNull(releaseInstant, "releaseInstant");
        Set<String> executionIds = new LinkedHashSet<>();
        for (CampaignExecution execution : executionsByKey.values()) {
            if (execution.tenantId().equals(tenantId) && execution.campaignId().equals(campaignId)) {
                executionIds.add(execution.executionId());
            }
        }
        List<RuntimeTask> released = new ArrayList<>();
        for (RuntimeTask task : tasksById.values()) {
            if (task.status() == RuntimeTaskStatus.DEFERRED
                    && task.tenantId().equals(tenantId)
                    && executionIds.contains(task.executionId())
                    && !task.availableAt().isAfter(releaseInstant)) {
                released.add(task.released(releaseInstant));
            }
        }
        if (released.isEmpty()) {
            // A resume that changes nothing writes nothing at all, not even an audit record.
            return 0;
        }
        if (audit != null) {
            if (!audit.tenantId().equals(tenantId)) {
                throw RuntimeSupport.invalid(RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "releaseAudit");
            }
            beforeCommit();
            auditSink.recordAll(List.of(audit));
        } else {
            beforeCommit();
        }
        for (RuntimeTask task : released) {
            tasksById.put(task.taskId(), task);
        }
        if (audit != null) {
            audits.add(audit);
        }
        return released.size();
    }

    private List<ActionHandoff> handoffsOf(
            String tenantId, String executionId, String participantId, String nodeLogicalId) {
        List<ActionHandoff> matches = new ArrayList<>();
        for (ActionHandoff handoff : handoffsById.values()) {
            if (handoff.tenantId().equals(tenantId) && handoff.executionId().equals(executionId)
                    && handoff.participantId().equals(participantId)
                    && handoff.nodeLogicalId().equals(nodeLogicalId)) {
                matches.add(handoff);
            }
        }
        matches.sort(Comparator.comparing(ActionHandoff::handoffId));
        return List.copyOf(matches);
    }

    /**
     * Re-proves that one Execution move is legal, or that the Execution is genuinely unchanged.
     *
     * <p>A completion that does not move the Execution must restate the stored record byte-for-byte, so a
     * caller cannot slip an unproved Execution change through as "no transition".
     */
    private static void requireExecutionMove(CampaignExecution stored, CampaignExecution next) {
        if (stored.status() == next.status()) {
            if (!stored.equals(next)) {
                throw new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_STORE_CONFLICT, "execution",
                        "An unchanged Execution must be restated exactly.");
            }
            return;
        }
        if (!stored.status().reaches(next.status())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_TRANSITION_INVALID, "executionStatus",
                    "An Execution transition is not one the frozen state machine allows.");
        }
    }

    /**
     * Re-proves the aggregate against the whole Participant set rather than trusting the caller's counters.
     *
     * <p>The Participant set this completion commits is the stored set with this one Participant replaced, so
     * both the deterministic status and the counters projection are recomputed here and compared.
     */
    private void requireAggregate(NodeCompletionCommit commit) {
        requireAggregateState(
                commit.tenantId(), commit.executionId(), commit.participantId(), commit.participant(),
                commit.execution(), commit.counters());
    }

    /**
     * Re-proves one aggregate against the whole Participant set rather than trusting a caller's counters.
     *
     * <p>Both the deterministic status and the counters projection are recomputed here, so an aggregate that
     * does not summarize the Participant set the same commit is about to store is refused. It is shared by the
     * local node completion and the additive external-action completion, so neither can drift from the other.
     */
    private void requireAggregateState(
            String tenantId,
            String executionId,
            String participantId,
            JourneyParticipant participant,
            CampaignExecution execution,
            ExecutionCounters counters) {
        List<JourneyParticipant> after = new ArrayList<>();
        for (JourneyParticipant stored : participantsById.values()) {
            if (stored.tenantId().equals(tenantId) && stored.executionId().equals(executionId)) {
                after.add(stored.participantId().equals(participantId) ? participant : stored);
            }
        }
        if (RuntimeAggregation.status(after) != execution.status()) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "executionStatus",
                    "An Execution must not move to a status its Participant set does not support.");
        }
        ExecutionCounters expected = RuntimeAggregation.counters(execution, after, counters.revision());
        if (!expected.equals(counters)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_AGGREGATE_INVALID, "counters",
                    "The aggregate counters do not match the Participant set they claim to summarize.");
        }
    }

    private void applyCompletion(NodeCompletionCommit commit) {
        tasksById.put(commit.taskId(), commit.task());
        participantsById.put(commit.participantId(), commit.participant());
        nodeExecutionsById.put(commit.nodeExecution().nodeExecutionId(), commit.nodeExecution());
        attemptsById.put(commit.attempt().attemptId(), commit.attempt());
        for (RuntimeTask next : commit.nextTasks()) {
            tasksById.put(next.taskId(), next);
        }
        for (ActionHandoff handoff : commit.handoffs()) {
            handoffsById.put(handoff.handoffId(), handoff);
        }
        executionsByKey.put(key(commit.tenantId(), commit.executionKey()), commit.execution());
        countersByExecution.put(key(commit.tenantId(), commit.executionId()), commit.counters());
        audits.addAll(commit.audits());
    }

    private List<RuntimeTask> claimCandidates(
            java.util.function.Predicate<RuntimeTask> scope, Instant claimInstant, int limit) {
        requireScanBound(limit);
        List<RuntimeTask> matches = new ArrayList<>();
        for (RuntimeTask task : tasksById.values()) {
            if (scope.test(task) && task.isClaimableAt(claimInstant)) {
                matches.add(task);
            }
        }
        matches.sort(Comparator
                .comparing(RuntimeTask::availableAt)
                .thenComparing(RuntimeTask::taskId));
        return List.copyOf(matches.subList(0, Math.min(matches.size(), limit)));
    }

    private static void requireScanBound(int limit) {
        if (limit < 1 || limit > RuntimeVersions.MAX_CLAIM_CANDIDATES) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BOUND_EXCEEDED, "limit",
                    "A claim scan must be bounded and positive.");
        }
    }

    /**
     * The one extension seam of this local adapter: called immediately before any state is written.
     *
     * <p>It exists so a deterministic local test can prove that a failing store write rolls the whole commit
     * back. A production adapter has no reason to override it.
     */
    protected void beforeCommit() {
        // No-op by default.
    }

    // ------------------------------------------------------------------ bounded observation

    /** The number of stored Inbox receipts. */
    public synchronized int inboxCount() {
        return inboxBySource.size();
    }

    /** The number of stored Executions. */
    public synchronized int executionCount() {
        return executionsByKey.size();
    }

    /** The number of stored Campaign enrollments. */
    public synchronized int enrollmentCount() {
        return enrollmentsByMember.size();
    }

    /** The number of stored audience snapshot members. */
    public synchronized int snapshotMemberCount() {
        return snapshotMembersByIdentity.size();
    }

    /** The number of stored Participants. */
    public synchronized int participantCount() {
        return participantsById.size();
    }

    /** The number of stored RuntimeTasks. */
    public synchronized int taskCount() {
        return tasksById.size();
    }

    /** The number of stored runtime Outbox/Audit records. */
    public synchronized int auditCount() {
        return audits.size();
    }

    private static boolean belongsTo(
            String storedTenant, String storedExecution, String tenantId, String executionId) {
        return storedTenant.equals(tenantId) && storedExecution.equals(executionId);
    }

    private static String key(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            builder.append(part).append('|');
        }
        return builder.toString();
    }

    /** Redacted diagnostics: no tenant, Campaign or member value is printed. */
    @Override
    public String toString() {
        return "InMemoryRuntimeStore[inbox=" + inboxCount()
                + ", executions=" + executionCount()
                + ", enrollments=" + enrollmentCount()
                + ", participants=" + participantCount()
                + ", tasks=" + taskCount()
                + ", audits=" + auditCount() + ']';
    }
}
