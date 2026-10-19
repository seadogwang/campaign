package com.company.campaign.runtime;

import com.company.campaign.builder.compiler.CampaignPlan;
import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import com.company.campaign.runtime.testsupport.ScriptedAudiencePort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused T22 atomicity tests: a delivery and a materialization each commit as one unit, and an injected store
 * or audit failure rolls every effect back.
 */
class RuntimeAtomicityTest {

    private static final Instant AS_OF = RuntimeScenario.PUBLISHED_AT;

    private static CampaignPlan plan() {
        return RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00");
    }

    // ------------------------------------------------------------------ delivery rollback

    @Test
    void anInjectedStoreFailureRollsTheWholeDeliveryBack() {
        SwitchableAuditSink sink = new SwitchableAuditSink();
        RuntimeScenario scenario = RuntimeScenario.withStoreAndSink(plan(), ScriptedStore::new, sink);
        scenario.publish();
        ScriptedStore store = (ScriptedStore) scenario.store;
        store.failCommits(true);

        assertThatThrownBy(() -> scenario.ingress().deliverRegistration(
                scenario.registrationEvent().eventId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.store.inboxCount()).isZero();
        assertThat(scenario.store.executionCount()).isZero();
        assertThat(scenario.store.auditCount()).isZero();
        assertThat(scenario.audits()).isEmpty();
        assertThat(sink.records()).isEmpty();

        store.failCommits(false);
        RuntimeDeliveryResult recovered = scenario.ingress().deliverRegistration(
                scenario.registrationEvent().eventId());
        assertThat(recovered.disposition()).isEqualTo(RuntimeDeliveryDisposition.REGISTERED);
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
    }

    @Test
    void anInjectedAuditFailureWritesNoReceiptAndNoExecution() {
        RuntimeScenario scenario = RuntimeScenario.withFailingAudit(plan());
        scenario.publish();

        assertThatThrownBy(() -> scenario.ingress().deliverOccurrence(
                scenario.campaignId(), scenario.planDue(AS_OF).dueExecutionKeys().get(0)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.store.inboxCount()).isZero();
        assertThat(scenario.store.executionCount()).isZero();
        assertThat(scenario.store.auditCount()).isZero();
    }

    // ------------------------------------------------------------------ materialization rollback

    @Test
    void anInjectedStoreFailureRollsTheWholeMaterializationBack() {
        RuntimeScenario scenario = RuntimeScenario.withStore(plan(), ScriptedStore::new);
        scenario.publish();
        String key = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1", "member-2"), nominal, nominal.minusSeconds(60)));
        ScriptedStore store = (ScriptedStore) scenario.store;
        int auditsBefore = store.auditCount();

        store.failCommits(true);
        assertThatThrownBy(() -> scenario.materialization().materialize(scenario.tenantId(), key))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.store.snapshotMemberCount()).isZero();
        assertThat(scenario.store.enrollmentCount()).isZero();
        assertThat(scenario.store.participantCount()).isZero();
        assertThat(scenario.store.taskCount()).isZero();
        assertThat(scenario.store.auditCount()).isEqualTo(auditsBefore);
        assertThat(scenario.store.findCounters(scenario.tenantId(), executionId(scenario, key))).isEmpty();
        CampaignExecution unchanged =
                scenario.store.findExecution(scenario.tenantId(), key).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(ExecutionStatus.CREATED);
        assertThat(unchanged.revision()).isZero();

        store.failCommits(false);
        MaterializationResult recovered =
                scenario.materialization().materialize(scenario.tenantId(), key);
        assertThat(recovered.failed()).isFalse();
        assertThat(recovered.snapshotMemberCount()).isEqualTo(2);
        assertThat(scenario.store.participantCount()).isEqualTo(2);
    }

    @Test
    void anInjectedAuditFailureDuringMaterializationRollsEveryEffectBack() {
        SwitchableAuditSink sink = new SwitchableAuditSink();
        RuntimeScenario scenario =
                RuntimeScenario.withStoreAndSink(plan(), InMemoryRuntimeStore::new, sink);
        scenario.publish();
        String key = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of("member-1"), nominal, nominal.minusSeconds(60)));
        int auditsBefore = sink.records().size();
        assertThat(auditsBefore).isEqualTo(1);

        sink.fail(true);
        assertThatThrownBy(() -> scenario.materialization().materialize(scenario.tenantId(), key))
                .isInstanceOf(IllegalStateException.class);

        assertThat(scenario.store.snapshotMemberCount()).isZero();
        assertThat(scenario.store.enrollmentCount()).isZero();
        assertThat(scenario.store.participantCount()).isZero();
        assertThat(scenario.store.taskCount()).isZero();
        assertThat(sink.records()).hasSize(auditsBefore);
        assertThat(scenario.store.findExecution(scenario.tenantId(), key).orElseThrow().status())
                .isEqualTo(ExecutionStatus.CREATED);
    }

    @Test
    void aLegacySingleRecordAuditSinkRejectsAMultiRecordBatchBeforeWritingAnything() {
        List<RuntimeAuditRecord> records = new ArrayList<>();
        RuntimeAuditSink legacy = records::add;
        RuntimeAuditRecord first = RuntimeAuditRecord.of(
                "audit-batch-1", "tenant-1", RuntimeAuditRecord.REASON_NODE_SUCCEEDED, "TASK", "task-1",
                null, null, null, AS_OF);
        RuntimeAuditRecord second = RuntimeAuditRecord.of(
                "audit-batch-2", "tenant-1", RuntimeAuditRecord.REASON_PAUSE_GUARDED, "TASK", "task-1",
                null, null, null, AS_OF);

        assertThatThrownBy(() -> legacy.recordAll(List.of(first, second)))
                .isInstanceOf(RuntimeBoundaryException.class)
                .hasFieldOrPropertyWithValue(
                        "code", RuntimeErrorCodes.RUNTIME_AUDIT_BATCH_UNSUPPORTED);
        assertThat(records).isEmpty();
    }

    private static String executionId(RuntimeScenario scenario, String executionKey) {
        return scenario.store.findExecution(scenario.tenantId(), executionKey).orElseThrow().executionId();
    }

    /** A store whose commit point can be made to fail on demand. */
    static final class ScriptedStore extends InMemoryRuntimeStore {

        private boolean fail;

        ScriptedStore(RuntimeAuditSink sink) {
            super(sink);
        }

        void failCommits(boolean value) {
            this.fail = value;
        }

        @Override
        protected void beforeCommit() {
            if (fail) {
                throw new IllegalStateException("scripted runtime store failure");
            }
        }
    }

    /** An audit collaborator that can be made to fail on demand while retaining what it already accepted. */
    static final class SwitchableAuditSink implements RuntimeAuditSink {

        private final List<RuntimeAuditRecord> records = new ArrayList<>();
        private boolean fail;

        void fail(boolean value) {
            this.fail = value;
        }

        @Override
        public void record(RuntimeAuditRecord record) {
            if (fail) {
                throw new IllegalStateException("scripted runtime audit sink failure");
            }
            records.add(record);
        }

        @Override
        public void recordAll(List<RuntimeAuditRecord> batch) {
            if (fail) {
                throw new IllegalStateException("scripted runtime audit sink failure");
            }
            if (batch == null || batch.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("invalid audit batch");
            }
            records.addAll(batch);
        }

        List<RuntimeAuditRecord> records() {
            return List.copyOf(records);
        }
    }
}
