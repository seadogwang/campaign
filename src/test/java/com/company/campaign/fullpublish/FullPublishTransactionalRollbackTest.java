package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleErrorCodes;
import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.lifecycle.LifecycleStore;
import com.company.campaign.lifecycle.PublishCommit;
import com.company.campaign.lifecycle.PreparePublishResult;
import com.company.campaign.lifecycle.PublishReceipt;
import com.company.campaign.lifecycle.testsupport.DelegatingLifecycleStore;
import com.company.campaign.lifecycle.testsupport.SwitchableAuditSink;
import com.company.campaign.segment.reference.AudienceRetentionHold;
import com.company.campaign.segment.reference.AudienceVersionRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Focused T21 atomicity tests: one transaction writes every full-publication effect or none of it.
 *
 * <p>Each test fails exactly one collaborator of the real transaction — the T21 audit sink, the accepted T17
 * commit (which is where the retention holds are acquired), the outbox append — and then proves that no full
 * definition, no full receipt, no moved published pointer, no active schedule projection, no outbox event and
 * no consumed preparation survived.
 */
class FullPublishTransactionalRollbackTest {

    private static LifecycleException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, LifecycleException.class);
    }

    private static Statement statement(FullPublishScenario scenario) {
        return new Statement(
                scenario.store.fullDefinitionCount(),
                scenario.store.fullReceiptCount(),
                scenario.store.outboxEvents().size(),
                scenario.base.store.findLatestPublished(scenario.campaignId()).isPresent(),
                scenario.base.store.isPreparationConsumed(scenario.basePreparation.preparationId()),
                scenario.store.findScheduleState(scenario.campaignId()).isPresent());
    }

    private record Statement(
            int definitions, int receipts, int outbox, boolean published,
            boolean consumed, boolean scheduled) {

        static final Statement NOTHING_PUBLISHED = new Statement(0, 0, 0, false, false, false);
    }

    // ------------------------------------------------------------------ rollback

    @Test
    void aFailingFullPublicationAuditSinkLeavesNothingBehind() {
        SwitchableAuditSink sink = new SwitchableAuditSink();
        FullPublishScenario scenario =
                FullPublishScenario.withAuditSink(FullPublishTestFixtures.immediatePlan(), sink);
        PrepareFullPublishResult preparation = scenario.prepare();

        sink.fail();
        assertThatThrownBy(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statement(scenario)).isEqualTo(Statement.NOTHING_PUBLISHED);
    }

    @Test
    void aFailingAcceptedCommitRollsBackEveryFullPublicationEffect() {
        FullPublishScenario scenario = FullPublishScenario.withBaseStore(
                FullPublishTestFixtures.immediatePlan(), FailingCommitStore::new);
        PrepareFullPublishResult preparation = scenario.prepare();

        // The accepted T17 commit is where the retention holds are acquired and the published pointer moves; a
        // failure there must leave the whole T21 publication unwritten.
        assertThatThrownBy(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statement(scenario)).isEqualTo(Statement.NOTHING_PUBLISHED);
    }

    @Test
    void aFailingOutboxAppendLeavesNothingBehind() {
        FullPublishScenario scenario = FullPublishScenario.withStore(
                FullPublishTestFixtures.immediatePlan(), null, FailingOutboxStore::new);
        PrepareFullPublishResult preparation = scenario.prepare();

        assertThatThrownBy(() -> scenario.confirm(
                preparation, "idem-1", preparation.requiredRiskCodes()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(statement(scenario)).isEqualTo(Statement.NOTHING_PUBLISHED);
    }

    // ------------------------------------------------------------------ compare-and-set

    @Test
    void aLostPublishedRevisionRaceIsRefusedInsideTheCommit() {
        StaleRevisionStore[] holder = new StaleRevisionStore[1];
        FullPublishScenario scenario = FullPublishScenario.withBaseStore(
                FullPublishTestFixtures.immediatePlan(), delegate -> {
                    StaleRevisionStore store = new StaleRevisionStore(delegate);
                    holder[0] = store;
                    return store;
                });

        scenario.publish();
        assertThat(scenario.base.store.findLatestPublished(scenario.campaignId()).orElseThrow()
                .publishedRevision()).isEqualTo(1L);

        PreparePublishResult base = scenario.base.prepareService()
                .prepare(scenario.base.prepareCommand(), scenario.context);
        PrepareFullPublishCommand command = new PrepareFullPublishCommand(
                base.preparationId(), base.preparationHash());

        // A competing publication moved the Campaign on between the service's read and the commit.
        holder[0].stale = true;

        LifecycleException failure = failureOf(() -> {
            PrepareFullPublishResult preparation = scenario.prepareService()
                    .prepare(command, scenario.context);
            scenario.confirm(preparation, "idem-race", preparation.requiredRiskCodes());
        });

        assertThat(failure).isNotNull();
        assertThat(failure.code()).isIn(
                LifecycleErrorCodes.LIFECYCLE_PUBLISH_DEFINITION_CONFLICT,
                LifecycleErrorCodes.LIFECYCLE_FULL_PREPARATION_STALE);
        assertThat(scenario.store.fullDefinitionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ retention binding

    @Test
    void aRetentionHoldThatDoesNotWitnessItsOwnPublicationIsUnrepresentable() {
        RecordingFullStore[] holder = new RecordingFullStore[1];
        FullPublishScenario scenario = FullPublishScenario.withStore(
                FullPublishTestFixtures.immediatePlan(), null, publishScenario -> {
                    RecordingFullStore store =
                            new RecordingFullStore(publishScenario.store, publishScenario.sink);
                    holder[0] = store;
                    return store;
                });
        scenario.publish();

        FullPublishCommit committed = holder[0].committed;
        assertThat(committed).isNotNull();
        assertThat(committed.retentionHolds()).isEmpty();

        AudienceRetentionHold foreign = new AudienceRetentionHold(
                "retention-hold:foreign", new AudienceVersionRef(
                        "audience-1", "version-1", FullPublishTestFixtures.hash("version-hash")),
                "another-campaign-1", "another-published-1", Instant.parse("2026-10-03T00:00:00Z"));

        List<AudienceRetentionHold> holds = new ArrayList<>();
        holds.add(foreign);

        assertThatThrownBy(() -> new FullPublishCommit(
                committed.expectedPreparationId(), committed.expectedPreparationHash(),
                committed.idempotencyKey(), committed.requestHash(), committed.expectedPublishedRevision(),
                committed.baseDefinition(), committed.baseReceipt(), committed.baseAuditEvent(),
                committed.fullDefinition(), committed.fullReceipt(), committed.scheduleState(),
                committed.outboxEvent(), committed.fullAuditEvent(), holds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must witness its own publication");
    }

    // ------------------------------------------------------------------ test collaborators

    /** A store whose accepted T17 commit always fails, simulating a failing retention acquisition. */
    private static final class FailingCommitStore extends DelegatingLifecycleStore {

        private FailingCommitStore(LifecycleStore delegate) {
            super(delegate);
        }

        @Override
        public PublishReceipt commitPublish(PublishCommit commit) {
            throw new IllegalStateException("retention acquisition failed");
        }
    }

    /** A store whose outbox append always fails, while nothing has been written yet. */
    private static final class FailingOutboxStore extends InMemoryFullPublicationStore {

        private FailingOutboxStore(
                com.company.campaign.lifecycle.testsupport.PublishScenario publishScenario) {
            super(publishScenario.store, publishScenario.sink);
        }

        @Override
        protected void beforeOutboxAppend() {
            throw new IllegalStateException("outbox append failed");
        }
    }

    /** A store that reports a stale next revision once armed, simulating a lost compare-and-set race. */
    private static final class StaleRevisionStore extends DelegatingLifecycleStore {

        private boolean stale;

        private StaleRevisionStore(LifecycleStore delegate) {
            super(delegate);
        }

        @Override
        public long nextPublishedRevision(String campaignId) {
            long next = super.nextPublishedRevision(campaignId);
            return stale ? Math.max(1L, next - 1L) : next;
        }
    }

    /** A store that records the full publication commit it is asked to perform. */
    private static final class RecordingFullStore extends InMemoryFullPublicationStore {

        private FullPublishCommit committed;

        private RecordingFullStore(
                LifecycleStore base, com.company.campaign.lifecycle.LifecycleAuditSink auditSink) {
            super(base, auditSink);
        }

        @Override
        public FullPublishReceipt commitFullPublish(FullPublishCommit commit) {
            this.committed = commit;
            return super.commitFullPublish(commit);
        }
    }
}