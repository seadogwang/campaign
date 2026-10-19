package com.company.campaign.runtime.testsupport;

import com.company.campaign.builder.compiler.PublishedIdentity;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.builder.compiler.JourneyManifestFixtures;
import com.company.campaign.fullpublish.CompiledSchedule;
import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.RuntimeAvailabilityPin;
import com.company.campaign.fullpublish.RuntimeAvailabilityState;
import com.company.campaign.journey.compiler.JourneyCompilation;
import com.company.campaign.journey.compiler.JourneyCompileRequest;
import com.company.campaign.journey.compiler.JourneyCompiler;
import com.company.campaign.journey.compiler.JourneyManifest;
import com.company.campaign.journey.compiler.JourneyTestFixture;
import com.company.campaign.journey.compiler.JourneyVersions;
import com.company.campaign.lifecycle.PublishedCampaignDefinition;
import com.company.campaign.runtime.AudienceSnapshotMember;
import com.company.campaign.runtime.CampaignExecution;
import com.company.campaign.runtime.CampaignMemberEnrollment;
import com.company.campaign.runtime.DeliveryCommit;
import com.company.campaign.runtime.ExecutionCounters;
import com.company.campaign.runtime.ExecutionStatus;
import com.company.campaign.runtime.InMemoryRuntimeAuditSink;
import com.company.campaign.runtime.InMemoryRuntimeStore;
import com.company.campaign.runtime.InboxOutcome;
import com.company.campaign.runtime.InboxRecord;
import com.company.campaign.runtime.InboxSourceKind;
import com.company.campaign.runtime.JourneyParticipant;
import com.company.campaign.runtime.MaterializationCommit;
import com.company.campaign.runtime.NodeAttempt;
import com.company.campaign.runtime.NodeCompletionCommit;
import com.company.campaign.runtime.NodeExecution;
import com.company.campaign.runtime.RuntimeAggregation;
import com.company.campaign.runtime.RuntimeAuditRecord;
import com.company.campaign.runtime.RuntimeDefinitionLookupPort;
import com.company.campaign.runtime.RuntimeRetryPolicy;
import com.company.campaign.runtime.RuntimeSupport;
import com.company.campaign.runtime.RuntimeTask;
import com.company.campaign.runtime.RuntimeTaskKind;
import com.company.campaign.runtime.RuntimeWorker;
import com.company.campaign.shared.canonical.ContentHash;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One deterministic phase-B Runtime scenario over a hash-pinned frozen definition and a bootstrapped Execution.
 *
 * <p>The frozen definition is built through the real T20 compiler and the accepted T21 record factories: the
 * Journey is compiled by {@link JourneyCompiler} from a resolved Manifest and the accepted T19 pack index, the
 * Schedule is a real {@link CompiledSchedule} bound to that Manifest and Journey, and the base published
 * definition and the full publication are the accepted immutable records. Every pin the Runtime worker
 * re-proves therefore witnesses exactly the frozen material a host would hold.
 *
 * <p>The Execution, snapshot, enrollment, Participant and START task are committed through the accepted
 * transactional store port, so the worker starts from exactly the state a phase-A materialization leaves. The
 * clock is mutable, so lease expiry, bounded backoff and frozen Wait deadlines are exercised without sleeping.
 */
public final class RuntimeJourneyScenario {

    public static final String TENANT = "tenant-t22b";
    public static final String PROGRAM = "program-t22b";
    public static final String USER = "user-t22b";
    public static final String SCOPE = "scope-t22b";
    public static final String CAMPAIGN = "campaign-t22b";
    public static final String DEFINITION_ID = "full-t22b-1";
    public static final String PUBLISHED_ID = "published-t22b-1";
    public static final String EXECUTION_KEY = "occurrence-t22b-1";
    public static final String NOMINAL = "2026-10-03T00:00:00Z";
    public static final String ENROLLED_AT = "2026-10-03T07:00:00Z";

    /** The lease duration every worker this scenario creates takes. */
    public static final Duration LEASE = Duration.ofSeconds(30);

    /** The bounded fact timeout every worker this scenario creates declares. */
    public static final long FACT_TIMEOUT_MILLIS = 500L;

    /** The claim scan bound every worker this scenario creates uses. */
    public static final int CLAIM_SCAN = 256;

    public final MutableClock clock;
    public final InMemoryRuntimeStore store;
    public final FullPublishedDefinition definition;
    public final PublishedCampaignDefinition base;
    public final JourneyManifest journey;
    public final RuntimeDefinitionLookupPort definitions;
    public final String executionId;
    public final List<String> memberIds;
    public ScriptedCampaignStatePort campaignStates;
    public ScriptedFactPort facts;
    private RuntimeRetryPolicy retryPolicy = RuntimeRetryPolicy.defaults();

    private RuntimeJourneyScenario(
            RuntimeJourneys.Shape shape, List<String> members, Instant start) {
        this.memberIds = List.copyOf(members);
        this.clock = new MutableClock(start);
        this.store = new InMemoryRuntimeStore(new InMemoryRuntimeAuditSink());

        ResolvedCampaignManifest manifest = JourneyManifestFixtures.manifest(shape.components());
        JourneyCompilation compilation = new JourneyCompiler().compile(new JourneyCompileRequest(
                manifest, shape.topology(), JourneyTestFixture.index(),
                JourneyVersions.JOURNEY_COMPILER_VERSION, JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION));
        this.journey = compilation.requireManifest();
        CompiledSchedule schedule = CompiledSchedule.of(
                ScheduleBasicForm.IMMEDIATE, "Asia/Shanghai", null, null, null, null,
                RuntimeJourneys.SCHEDULE,
                hash("schedule-payload"), manifest.manifestHash(), journey.contentHash());
        this.base = PublishedCampaignDefinition.of(
                PUBLISHED_ID, 1L, TENANT, PROGRAM, USER, SCOPE, CAMPAIGN, 1L, "draft-t22b-1", 1L, null,
                "plan-t22b-1", hash("plan"), hash("draft"), "catalog-t22b", hash("catalog"), "policy-t22b",
                hash("policy"), List.of(), List.of(), List.of(), "segment.rule-based", "ACTIVE",
                hash("gate-inputs"), hash("approved-decision"), manifest, start);
        this.definition = FullPublishedDefinition.of(
                DEFINITION_ID, 1L, TENANT, PROGRAM, USER, SCOPE, CAMPAIGN, 1L, base.identity(),
                manifest.manifestHash(), journey, schedule,
                RuntimeAvailabilityPin.of(
                        journey.runtimeSchemaVersion(), RuntimeAvailabilityState.AVAILABLE,
                        hash("runtime-evidence")),
                start);
        this.definitions = new FixedDefinitions(definition, base);
        this.campaignStates = new ScriptedCampaignStatePort(
                TENANT, PROGRAM, CAMPAIGN, definition.contentHash(), start);
        this.facts = new ScriptedFactPort();

        CampaignExecution execution = CampaignExecution.created(
                EXECUTION_KEY, TENANT, PROGRAM, CAMPAIGN, DEFINITION_ID, definition.contentHash(), 1L,
                journey.contentHash(), schedule.scheduleHash(), Instant.parse(NOMINAL),
                Instant.parse(NOMINAL), start);
        InboxRecord inbox = InboxRecord.of(
                InboxSourceKind.SCHEDULE_OCCURRENCE, EXECUTION_KEY, definition.contentHash(), TENANT, CAMPAIGN,
                DEFINITION_ID, 1L, EXECUTION_KEY, InboxOutcome.EXECUTION_CREATED, start);
        store.commitDelivery(new DeliveryCommit(inbox, execution, audit("delivery", execution, start)));
        this.executionId = execution.executionId();
        materialize(execution, Instant.parse(ENROLLED_AT));
    }

    /** A scenario over one frozen Journey shape and an exact enrolled member set. */
    public static RuntimeJourneyScenario of(RuntimeJourneys.Shape shape, List<String> members) {
        return of(shape, members, Instant.parse(ENROLLED_AT));
    }

    /** A scenario over one frozen Journey shape, its members and one exact start instant. */
    public static RuntimeJourneyScenario of(
            RuntimeJourneys.Shape shape, List<String> members, Instant start) {
        Objects.requireNonNull(shape, "shape");
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("a phase-B scenario enrolls at least one member");
        }
        return new RuntimeJourneyScenario(shape, members, start);
    }

    /** A scenario over one frozen Journey shape and one enrolled member. */
    public static RuntimeJourneyScenario of(RuntimeJourneys.Shape shape, String member) {
        return of(shape, List.of(member));
    }

    private void materialize(CampaignExecution execution, Instant at) {
        List<AudienceSnapshotMember> snapshot = new ArrayList<>();
        List<CampaignMemberEnrollment> enrollments = new ArrayList<>();
        List<JourneyParticipant> participants = new ArrayList<>();
        List<RuntimeTask> tasks = new ArrayList<>();
        for (String member : memberIds) {
            snapshot.add(AudienceSnapshotMember.of(
                    TENANT, executionId(execution), member, definition.contentHash(), at));
            enrollments.add(CampaignMemberEnrollment.of(
                    TENANT, CAMPAIGN, member, executionId(execution), DEFINITION_ID, 1L, at));
            JourneyParticipant participant = JourneyParticipant.created(
                    TENANT, executionId(execution), CAMPAIGN, DEFINITION_ID, definition.contentHash(),
                    journey.contentHash(), member, journey.startNodeLogicalId(), at);
            participants.add(participant);
            tasks.add(RuntimeTask.due(
                    TENANT, executionId(execution), participant.participantId(), journey.startNodeLogicalId(),
                    RuntimeTaskKind.START, at));
        }
        CampaignExecution running = execution.transitioned(
                ExecutionStatus.RUNNING, memberIds.size(), memberIds.size(), null, null, at);
        ExecutionCounters counters = ExecutionCounters.materialized(
                TENANT, executionId(execution), memberIds.size(), memberIds.size(), 0L);
        store.commitMaterialization(new MaterializationCommit(
                TENANT, EXECUTION_KEY, execution.revision(), running, snapshot, enrollments, participants,
                tasks, counters, List.of(audit("materialization", running, at))));
    }

    // ------------------------------------------------------------------ host state

    /** The leased Runtime worker over this scenario's store, pins, activation projection and fact port. */
    public RuntimeWorker worker() {
        return new RuntimeWorker(
                definitions, campaignStates, facts, store, clock, retryPolicy, LEASE, FACT_TIMEOUT_MILLIS,
                CLAIM_SCAN);
    }

    /** Replaces the bounded retry policy every worker this scenario creates applies. */
    public RuntimeJourneyScenario withRetryPolicy(RuntimeRetryPolicy replaced) {
        this.retryPolicy = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** Replaces the host Campaign-state projection every worker this scenario creates rechecks. */
    public RuntimeJourneyScenario withCampaignState(ScriptedCampaignStatePort replaced) {
        this.campaignStates = Objects.requireNonNull(replaced, "replaced");
        return this;
    }

    /** Every stored task of this scenario's Execution, in deterministic order. */
    public List<RuntimeTask> tasks() {
        return store.findTasks(TENANT, executionId);
    }

    /** The one stored START task of one member's Participant. */
    public RuntimeTask startTask(String member) {
        String participantId = JourneyParticipant.participantId(TENANT, executionId, member);
        for (RuntimeTask task : tasks()) {
            if (task.participantId().equals(participantId)
                    && task.nodeLogicalId().equals(journey.startNodeLogicalId())
                    && task.kind() == RuntimeTaskKind.START) {
                return task;
            }
        }
        throw new IllegalStateException("no START task is stored for this member");
    }

    /** The stored Participant of one member. */
    public JourneyParticipant participant(String member) {
        String participantId = JourneyParticipant.participantId(TENANT, executionId, member);
        for (JourneyParticipant participant : store.findParticipants(TENANT, executionId)) {
            if (participant.participantId().equals(participantId)) {
                return participant;
            }
        }
        throw new IllegalStateException("no Participant is stored for this member");
    }

    /** The stored Execution. */
    public CampaignExecution execution() {
        return store.findExecution(TENANT, EXECUTION_KEY).orElseThrow();
    }

    /** The stored aggregate counters of this scenario's Execution. */
    public ExecutionCounters counters() {
        return store.findCounters(TENANT, executionId).orElseThrow();
    }

    /**
     * One hand-built completion unit for one leased task, with an explicit lease token and Participant
     * revision.
     *
     * <p>It exists so a test can present exactly the value a boundedly forged or stale caller would present —
     * a wrong token, a stale Participant revision or an aggregate the Participant set does not support — and
     * prove that the store refuses it inside its transaction.
     */
    public NodeCompletionCommit completion(
            RuntimeTask leased,
            CampaignExecution nextExecution,
            JourneyParticipant participant,
            String leaseToken,
            long expectedParticipantRevision,
            Instant at) {
        String nodeLogicalId = leased.nodeLogicalId();
        com.company.campaign.journey.graph.JourneyNode node = journey.node(nodeLogicalId);
        String nodeExecutionId = NodeExecution.nodeExecutionId(
                TENANT, executionId, participant.participantId(), nodeLogicalId);
        List<JourneyParticipant> after = new ArrayList<>();
        for (JourneyParticipant stored : store.findParticipants(TENANT, executionId)) {
            after.add(stored.participantId().equals(participant.participantId()) ? participant : stored);
        }
        return new NodeCompletionCommit(
                TENANT, EXECUTION_KEY, executionId, execution().revision(), nextExecution,
                participant.participantId(), expectedParticipantRevision, nodeLogicalId, participant,
                leased.taskId(), leased.revision(), leaseToken, leased.completed(at),
                NodeExecution.pending(
                        TENANT, executionId, participant.participantId(), nodeLogicalId, node.kind(),
                        node.payloadHash(), at),
                NodeAttempt.of(
                        TENANT, executionId, participant.participantId(), leased.taskId(), nodeExecutionId,
                        leased.attemptNumber(), "START_COMPLETED", leased.updatedAt(), at),
                List.of(), List.of(),
                RuntimeAggregation.counters(nextExecution, after, counters().revision()),
                List.of(RuntimeAuditRecord.of(
                        "audit-forged-1", TENANT, RuntimeAuditRecord.REASON_NODE_SUCCEEDED, "TASK",
                        leased.taskId(), null, null, null, at)));
    }

    /** Every runtime Outbox/Audit record this scenario's store retained. */
    public List<RuntimeAuditRecord> audits() {
        return store.findAudits(TENANT);
    }

    private static String executionId(CampaignExecution execution) {
        return execution.executionId();
    }

    private static RuntimeAuditRecord audit(String suffix, CampaignExecution execution, Instant at) {
        return RuntimeAuditRecord.of(
                RuntimeSupport.derivedId("audit", List.of(suffix, execution.executionId(), at)),
                execution.tenantId(), RuntimeAuditRecord.REASON_EXECUTION_CREATED, "EXECUTION",
                execution.executionId(), execution.executionKey(), null, execution.revision(), at);
    }

    private static ContentHash hash(String seed) {
        return ContentHash.sha256(seed.getBytes(StandardCharsets.UTF_8));
    }

    /** The one definition lookup every pin re-proof in this scenario resolves against. */
    private record FixedDefinitions(
            FullPublishedDefinition definition,
            PublishedCampaignDefinition base) implements RuntimeDefinitionLookupPort {

        @Override
        public Optional<FullPublishedDefinition> findFullDefinition(String fullDefinitionId) {
            return definition.fullDefinitionId().equals(fullDefinitionId)
                    ? Optional.of(definition)
                    : Optional.empty();
        }

        @Override
        public Optional<PublishedCampaignDefinition> findBaseDefinition(String publishedId) {
            return base.publishedId().equals(publishedId) ? Optional.of(base) : Optional.empty();
        }

        @Override
        public String toString() {
            return "FixedDefinitions[]";
        }
    }

    /** Redacted diagnostics: the scenario holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeJourneyScenario[members=" + memberIds.size()
                + ", start=" + journey.startNodeLogicalId()
                + ", nodes=" + journey.nodes().size() + ']';
    }
}
