package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.InMemoryLifecycleAuditSink;
import com.company.campaign.lifecycle.LifecycleAction;
import com.company.campaign.lifecycle.LifecycleAuditEvent;
import com.company.campaign.lifecycle.LifecycleAuditSink;
import com.company.campaign.lifecycle.LifecycleDisposition;
import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.PublishCommand;
import com.company.campaign.lifecycle.PublishCommit;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.PublishResult;
import com.company.campaign.lifecycle.PublishService;
import com.company.campaign.lifecycle.testsupport.DelegatingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.LifecycleTestFixtures;
import com.company.campaign.lifecycle.testsupport.PublishTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * T21-R1 focused regressions: the four independent-review findings about atomicity and occurrence correctness.
 *
 * <p>Each test asserts on the audit log and on state, not on definition counts alone:
 *
 * <ul>
 *   <li>the applied full-publication audit is part of the accepted base publish transaction, so it can never
 *       outlive a failed base commit and can never precede an unwritten publication;</li>
 *   <li>every committed occurrence decision, {@code DUE} and {@code SKIPPED} alike, is emitted history, so an
 *       exact replay plans, reports, audits and writes nothing;</li>
 *   <li>the active schedule revision is re-proved by the full commit, so a projection that moved after the
 *       service's read is refused with no publication;</li>
 *   <li>the publication and schedule read are one combined store, so a split wiring is refused rather than
 *       silently able to read a revision from one backend and commit it to another.</li>
 * </ul>
 */
class T21R1AtomicityRegressionTest {

    /** The default scenario clock, inside the accepted base preparation's lifetime. */
    private static final Instant FIXTURE_INSTANT = LifecycleTestFixtures.APPLIED_AT;

    /** An evaluation instant that leaves the last two local dates of the window un-due yet. */
    private static final Instant FIRST_AS_OF = Instant.parse("2026-10-01T23:00:00Z");

    /** A later evaluation instant, still inside the fixture instant, that brings two more dates to bear. */
    private static final Instant RESUMED_AS_OF = FIXTURE_INSTANT;

    private static LifecycleException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, LifecycleException.class);
    }

    private static boolean isAppliedFullAudit(LifecycleAuditEvent event) {
        return event.action() == LifecycleAction.FULL_PUBLISH
                && event.disposition() == LifecycleDisposition.APPLIED
                && FullPublishService.FULL_PUBLISHED_REASON.equals(event.reasonCode());
    }

    private static boolean isAppliedBaseAudit(LifecycleAuditEvent event) {
        return event.action() == LifecycleAction.PUBLISH
                && event.disposition() == LifecycleDisposition.APPLIED
                && PublishService.PUBLISHED_REASON.equals(event.reasonCode());
    }

    private static long countAppliedFullAudits(InMemoryLifecycleAuditSink sink) {
        return sink.events().stream()
                .filter(T21R1AtomicityRegressionTest::isAppliedFullAudit)
                .count();
    }

    private static long countAppliedBaseAudits(InMemoryLifecycleAuditSink sink) {
        return sink.events().stream()
                .filter(T21R1AtomicityRegressionTest::isAppliedBaseAudit)
                .count();
    }

    private static long countPlanAudits(InMemoryLifecycleAuditSink sink) {
        return sink.events().stream()
                .filter(event -> event.action() == LifecycleAction.SCHEDULE_OPERATE)
                .filter(event -> event.disposition() == LifecycleDisposition.APPLIED)
                .filter(event -> ScheduleOperationService.PLANNED_REASON.equals(event.reasonCode()))
                .count();
    }

    // ------------------------------------------------------------------ finding 1: the applied audit is transactional

    @Test
    void aFailingAcceptedCommitLeavesNeitherTheBaseNorTheAppliedFullAudit() {
        FullPublishScenario scenario = FullPublishScenario.withBaseStore(
                FullPublishTestFixtures.immediatePlan(), AlwaysFailingCommitStore::new);
        InMemoryLifecycleAuditSink sink = (InMemoryLifecycleAuditSink) scenario.base.sink;
        PrepareFullPublishResult preparation = scenario.prepare();
        long auditsBefore = sink.events().size();

        assertThatThrownBy(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(sink.events().subList((int) auditsBefore, sink.events().size()))
                .as("an accepted commit that never ran records no applied audit of any kind")
                .noneMatch(T21R1AtomicityRegressionTest::isAppliedFullAudit)
                .noneMatch(T21R1AtomicityRegressionTest::isAppliedBaseAudit);
        assertThat(scenario.store.fullDefinitionCount()).isZero();
        assertThat(scenario.base.store.findLatestPublished(scenario.campaignId())).isEmpty();
    }

    @Test
    void aFailureWhileRecordingTheAdditionalFullAuditLeavesNoPublicationConsumptionOrHold() {
        InMemoryLifecycleAuditSink recorded = new InMemoryLifecycleAuditSink();
        LifecycleAuditSink sink = new LifecycleAuditSink() {
            @Override
            public void record(LifecycleAuditEvent event) {
                recorded.record(event);
            }

            @Override
            public void recordAll(List<LifecycleAuditEvent> events) {
                assertThat(events).hasSize(2);
                assertThat(events.get(0)).matches(T21R1AtomicityRegressionTest::isAppliedBaseAudit);
                assertThat(events.get(1)).matches(T21R1AtomicityRegressionTest::isAppliedFullAudit);
                throw new IllegalStateException("the atomic audit batch is unavailable");
            }
        };
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), sink);
        PrepareFullPublishResult preparation = scenario.prepare();

        assertThatThrownBy(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.store.fullDefinitionCount()).isZero();
        assertThat(scenario.store.fullReceiptCount()).isZero();
        assertThat(scenario.store.outboxEvents()).isEmpty();
        assertThat(scenario.store.findScheduleState(scenario.campaignId())).isEmpty();
        assertThat(scenario.base.store.findLatestPublished(scenario.campaignId())).isEmpty();
        assertThat(scenario.base.store.publishReceiptCount()).isZero();
        assertThat(scenario.base.store.isPreparationConsumed(scenario.basePreparation.preparationId()))
                .isFalse();
        assertThat(scenario.base.retention.activeHoldCount()).isZero();
        assertThat(recorded.events())
                .as("a failed audit batch must leave neither applied publication event")
                .noneMatch(T21R1AtomicityRegressionTest::isAppliedFullAudit)
                .noneMatch(T21R1AtomicityRegressionTest::isAppliedBaseAudit);
    }

    @Test
    void aLegacySingleEventSinkRejectsAMultiEventBatchBeforeWritingAnything() {
        InMemoryLifecycleAuditSink source = new InMemoryLifecycleAuditSink();
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), source);
        scenario.publish();
        List<LifecycleAuditEvent> applied = source.events().stream()
                .filter(event -> isAppliedBaseAudit(event) || isAppliedFullAudit(event))
                .toList();
        List<LifecycleAuditEvent> recorded = new ArrayList<>();
        LifecycleAuditSink legacy = recorded::add;

        assertThatThrownBy(() -> legacy.recordAll(applied))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit sink does not support atomic batches");
        assertThat(recorded).isEmpty();
    }

    @Test
    void aSuccessfulFullPublishRecordsExactlyOneBaseAndOneFullAppliedAudit() {
        InMemoryLifecycleAuditSink sink = new InMemoryLifecycleAuditSink();
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), sink);
        scenario.publish();

        assertThat(countAppliedBaseAudits(sink)).isEqualTo(1L);
        assertThat(countAppliedFullAudits(sink)).isEqualTo(1L);
    }

    @Test
    void anExactIdempotentReplayRecordsNeitherAuditAgain() {
        InMemoryLifecycleAuditSink sink = new InMemoryLifecycleAuditSink();
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), sink);
        PrepareFullPublishResult preparation = scenario.prepare();
        scenario.confirm(preparation, "idem-1", preparation.requiredRiskCodes());
        int auditsAfterFirst = sink.events().size();

        scenario.confirm(preparation, "idem-1", preparation.requiredRiskCodes());

        assertThat(sink.events()).hasSize(auditsAfterFirst);
        assertThat(countAppliedBaseAudits(sink)).isEqualTo(1L);
        assertThat(countAppliedFullAudits(sink)).isEqualTo(1L);
    }

    @Test
    void theAppliedFullAuditTravelsInsideTheAcceptedBaseCommit() {
        RecordingCommitStore[] holder = new RecordingCommitStore[1];
        FullPublishScenario scenario = FullPublishScenario.withBaseStore(
                FullPublishTestFixtures.immediatePlan(), delegate -> {
                    RecordingCommitStore store = new RecordingCommitStore(delegate);
                    holder[0] = store;
                    return store;
                });
        scenario.publish();

        assertThat(holder[0].committed).isNotNull();
        assertThat(holder[0].committed.auditEvent().action()).isEqualTo(LifecycleAction.PUBLISH);
        assertThat(holder[0].committed.additionalAuditEvents())
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.action()).isEqualTo(LifecycleAction.FULL_PUBLISH);
                    assertThat(event.disposition()).isEqualTo(LifecycleDisposition.APPLIED);
                    assertThat(event.reasonCode()).isEqualTo(FullPublishService.FULL_PUBLISHED_REASON);
                });
    }

    @Test
    void anAcceptedT17PublishCommitCarriesNoAdditionalAuditEvent() {
        RecordingCommitStore[] holder = new RecordingCommitStore[1];
        FullPublishScenario scenario = FullPublishScenario.withBaseStore(
                FullPublishTestFixtures.immediatePlan(), delegate -> {
                    RecordingCommitStore store = new RecordingCommitStore(delegate);
                    holder[0] = store;
                    return store;
                });

        PreparePublishResult preparation = scenario.base
                .prepareServiceOver(holder[0])
                .prepare(scenario.base.prepareCommand(), scenario.context);
        PublishResult published = scenario.base
                .publishServiceOver(holder[0])
                .confirm(new PublishCommand(
                        preparation.preparationId(), preparation.preparationHash(),
                        "accepted-key-1", List.of()), scenario.context);

        assertThat(published.publishedId()).isNotBlank();
        assertThat(holder[0].committed).isNotNull();
        assertThat(holder[0].committed.additionalAuditEvents()).isEmpty();
        assertThat(holder[0].committed.retentionHolds()).isEmpty();
    }

    // ------------------------------------------------------------------ finding 2: SKIPPED is emitted history

    @Test
    void committedSkippedOccurrencesAreNeverPlannedAuditedOrWrittenAgain() {
        InMemoryLifecycleAuditSink sink = new InMemoryLifecycleAuditSink();
        FullPublishScenario scenario = FullPublishScenario.withAuditSink(
                FullPublishTestFixtures.dailyPlan("2026-09-29", "2026-10-05", "08:00"), sink);
        scenario.publish();

        ScheduleDueResult first = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, FIRST_AS_OF), scenario.context);
        assertThat(first.dueExecutionKeys()).hasSize(1);
        assertThat(first.skippedExecutionKeys()).hasSize(2);

        List<ScheduledOccurrenceRecord> committed =
                scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(committed).hasSize(3);
        assertThat(committed.stream().filter(record -> record.decision().due()).toList())
                .singleElement()
                .satisfies(record -> assertThat(record.localDate()).isEqualTo("2026-10-01"));
        List<String> committedKeys = committed.stream()
                .map(ScheduledOccurrenceRecord::executionKey)
                .toList();
        long planAudits = countPlanAudits(sink);

        ScheduleDueResult second = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, FIRST_AS_OF), scenario.context);

        assertThat(second.dueExecutionKeys()).isEmpty();
        assertThat(second.skippedExecutionKeys()).isEmpty();
        assertThat(second.alreadyCommittedExecutionKeys())
                .containsExactlyInAnyOrderElementsOf(committedKeys);
        assertThat(scenario.store.findCommittedOccurrences(scenario.campaignId())).hasSize(3);
        assertThat(countPlanAudits(sink))
                .as("an exact no-op plan records no audit at all")
                .isEqualTo(planAudits);

        ScheduleDueResult resumed = scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, RESUMED_AS_OF), scenario.context);

        assertThat(resumed.dueExecutionKeys()).hasSize(1);
        assertThat(resumed.skippedExecutionKeys()).hasSize(1);
        List<ScheduledOccurrenceRecord> afterResume =
                scenario.store.findCommittedOccurrences(scenario.campaignId());
        assertThat(afterResume).hasSize(5);
        assertThat(afterResume.stream()
                .map(ScheduledOccurrenceRecord::executionKey)
                .filter(key -> !committedKeys.contains(key))
                .toList())
                .as("resuming later plans only the dates that were not previously committed")
                .containsExactlyInAnyOrderElementsOf(
                        java.util.stream.Stream.concat(
                                        resumed.dueExecutionKeys().stream(),
                                        resumed.skippedExecutionKeys().stream())
                                .toList());
    }

    @Test
    void aNoOpPlanStillRefusesAStaleExpectedScheduleRevision() {
        InMemoryLifecycleAuditSink sink = new InMemoryLifecycleAuditSink();
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), sink);
        scenario.publish();
        scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, FIXTURE_INSTANT), scenario.context);
        long planAudits = countPlanAudits(sink);

        assertThat(failureOf(() -> scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 7L, FIXTURE_INSTANT),
                scenario.context)).code())
                .isEqualTo(LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT);
        assertThat(countPlanAudits(sink)).isEqualTo(planAudits);
    }

    // ------------------------------------------------------------------ finding 3: schedule revision re-proof

    @Test
    void aScheduleRevisionThatMovedBeforeTheCommitIsRefusedWithNoPublication() {
        InterleavingStore[] holder = new InterleavingStore[1];
        FullPublishScenario scenario = FullPublishScenario.withStore(
                FullPublishTestFixtures.immediatePlan(), null, publishScenario -> {
                    InterleavingStore store =
                            new InterleavingStore(publishScenario.store, publishScenario.sink);
                    holder[0] = store;
                    return store;
                });
        scenario.publish();

        PreparePublishResult base = scenario.base.prepareService()
                .prepare(scenario.base.prepareCommand(), scenario.context);
        PrepareFullPublishResult second = scenario.prepareService().prepare(
                new PrepareFullPublishCommand(base.preparationId(), base.preparationHash()),
                scenario.context);

        // A concurrent operator pauses the active schedule after the service read its revision but before the
        // full publication commits its own projection.
        holder[0].beforeCommit = () -> scenario.operationService().pause(
                new ScheduleStateCommand(scenario.campaignId(), 1L), scenario.context);

        LifecycleException failure = failureOf(() -> scenario.confirm(
                second, "idem-2", second.requiredRiskCodes()));

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isEqualTo(LifecycleErrorCodes.LIFECYCLE_SCHEDULE_STATE_CONFLICT);
        assertThat(failure.invariant()).isEqualTo("scheduleStateRevision");
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
        assertThat(scenario.store.fullReceiptCount()).isEqualTo(1);
        assertThat(scenario.store.outboxEvents()).hasSize(1);
        assertThat(scenario.base.store.findLatestPublished(scenario.campaignId()).orElseThrow()
                .publishedRevision()).isEqualTo(1L);
        assertThat(scenario.base.store.publishReceiptCount()).isEqualTo(1);
        assertThat(scenario.base.store.isPreparationConsumed(base.preparationId())).isFalse();
        // The concurrent pause itself is not lost: the refused publication simply did not overwrite it.
        assertThat(scenario.activeSchedule().status()).isEqualTo(ScheduleStatus.PAUSED);
        assertThat(scenario.activeSchedule().stateRevision()).isEqualTo(2L);
    }

    // ------------------------------------------------------------------ finding 4: one combined store

    @Test
    void splitPublicationAndScheduleStoresAreRefusedRatherThanSilentlyAccepted() {
        FullPublishScenario scenario = FullPublishScenario.immediate();

        ScheduleStore scheduleOnly = new ScheduleOnlyStore(scenario.store);
        assertThatThrownBy(() -> scenario.publishService(scenario.store, scheduleOnly))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combined");

        // Even a second instance of the combined adapter is a different transactional backend, so it is split.
        ScheduleStore secondCombined =
                new InMemoryFullPublicationStore(scenario.baseStore, scenario.base.sink);
        assertThatThrownBy(() -> scenario.publishService(scenario.store, secondCombined))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("combined");

        assertThatThrownBy(() -> scenario.publishService(scenario.store, null))
                .isInstanceOf(NullPointerException.class);

        // The one combined store is the only wiring accepted, and it works.
        FullPublishService service = scenario.publishService(scenario.store, scenario.store);
        PrepareFullPublishResult preparation = scenario.prepare();
        assertThat(service.confirm(
                scenario.command(preparation, "idem-1", preparation.requiredRiskCodes()),
                scenario.context).fullDefinitionId()).isNotBlank();
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ test collaborators

    /** A store whose accepted T17 commit always fails, so the accepted commit is where the rollback happens. */
    private static final class AlwaysFailingCommitStore extends DelegatingLifecycleStore {

        private AlwaysFailingCommitStore(LifecycleStore delegate) {
            super(delegate);
        }

        @Override
        public PublishReceipt commitPublish(PublishCommit commit) {
            throw new IllegalStateException("the accepted publish commit failed");
        }
    }

    /** A store that records the commit it is asked to perform before delegating it. */
    private static final class RecordingCommitStore extends DelegatingLifecycleStore {

        private PublishCommit committed;

        private RecordingCommitStore(LifecycleStore delegate) {
            super(delegate);
        }

        @Override
        public PublishReceipt commitPublish(PublishCommit commit) {
            this.committed = commit;
            return super.commitPublish(commit);
        }
    }

    /** A combined store that lets a test interleave one write between the service's read and its commit. */
    private static final class InterleavingStore extends InMemoryFullPublicationStore {

        private Runnable beforeCommit = () -> { };

        private InterleavingStore(LifecycleStore base, LifecycleAuditSink auditSink) {
            super(base, auditSink);
        }

        @Override
        public FullPublishReceipt commitFullPublish(FullPublishCommit commit) {
            Runnable interleave = beforeCommit;
            beforeCommit = () -> { };
            interleave.run();
            return super.commitFullPublish(commit);
        }
    }

    /** A schedule store that is not a combined publication store, standing in for a split backend. */
    private static final class ScheduleOnlyStore implements ScheduleStore {

        private final ScheduleStore delegate;

        private ScheduleOnlyStore(ScheduleStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<CampaignScheduleState> findScheduleState(String campaignId) {
            return delegate.findScheduleState(campaignId);
        }

        @Override
        public List<ScheduledOccurrenceRecord> findCommittedOccurrences(String campaignId) {
            return delegate.findCommittedOccurrences(campaignId);
        }

        @Override
        public Optional<CampaignScheduleState> commitScheduleOperation(
                String campaignId,
                long expectedStateRevision,
                CampaignScheduleState replacement,
                List<ScheduledOccurrenceRecord> records,
                LifecycleAuditEvent auditEvent) {
            return delegate.commitScheduleOperation(
                    campaignId, expectedStateRevision, replacement, records, auditEvent);
        }
    }

    }
