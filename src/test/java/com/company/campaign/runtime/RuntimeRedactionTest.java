package com.company.campaign.runtime;

import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import com.company.campaign.runtime.testsupport.ScriptedAudiencePort;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused T22 redaction tests: no member value, tenant value or Campaign value ever appears in a runtime
 * record's diagnostics, in an audit record or in a bounded failure's details.
 */
class RuntimeRedactionTest {

    private static final String TENANT = "tenant-alpha";
    private static final String PROGRAM = "program-alpha";
    private static final String CAMPAIGN = "campaign-alpha";
    private static final String DEFINITION = "full-definition-alpha";
    private static final String EXECUTION_ID = "exec-alpha-1";
    private static final String PARTICIPANT_ID = "participant-alpha-1";
    private static final String MEMBER = "member-9f3a-secret";
    private static final Instant AT = Instant.parse("2026-10-03T00:00:00Z");
    private static final ContentHash HASH = RuntimeSupport.seedHash("redaction-evidence");

    @Test
    void everyRuntimeRecordPrintsNoMemberTenantOrCampaignValue() {
        AudienceSnapshotMember snapshot =
                AudienceSnapshotMember.of(TENANT, EXECUTION_ID, MEMBER, HASH, AT);
        CampaignMemberEnrollment enrollment = CampaignMemberEnrollment.of(
                TENANT, CAMPAIGN, MEMBER, EXECUTION_ID, DEFINITION, 1L, AT);
        JourneyParticipant participant = JourneyParticipant.created(
                TENANT, EXECUTION_ID, CAMPAIGN, DEFINITION, HASH, HASH, MEMBER, "node-start", AT);
        RuntimeTask task = RuntimeTask.due(
                TENANT, EXECUTION_ID, PARTICIPANT_ID, "node-start", RuntimeTaskKind.START, AT);
        CampaignExecution execution = CampaignExecution.created(
                EXECUTION_ID, TENANT, PROGRAM, CAMPAIGN, DEFINITION, HASH, 1L, HASH, HASH, AT, AT, AT);
        InboxRecord inbox = InboxRecord.of(
                InboxSourceKind.SCHEDULE_OCCURRENCE, "exec-key-1", HASH, TENANT, CAMPAIGN, DEFINITION, 1L,
                "exec-key-1", InboxOutcome.EXECUTION_CREATED, AT);
        RuntimeAuditRecord audit = RuntimeAuditRecord.of(
                "audit-1", TENANT, RuntimeAuditRecord.REASON_EXECUTION_CREATED, "EXECUTION", EXECUTION_ID,
                "exec-key-1", null, 0L, AT);
        NodeExecution node = NodeExecution.pending(
                TENANT, EXECUTION_ID, PARTICIPANT_ID, "node-start",
                com.company.campaign.journey.graph.JourneyNodeKind.START, HASH, AT);
        NodeAttempt attempt = NodeAttempt.of(
                TENANT, EXECUTION_ID, PARTICIPANT_ID, "task-1", "node-execution-1", 1, "SUCCEEDED", AT,
                AT);
        ExecutionCounters counters = ExecutionCounters.materialized(TENANT, EXECUTION_ID, 3, 3, 0L);

        for (Object record : List.of(
                snapshot, enrollment, participant, task, execution, inbox, audit, node, attempt, counters)) {
            assertThat(record.toString())
                    .as("%s diagnostics", record.getClass().getSimpleName())
                    .doesNotContain(MEMBER)
                    .doesNotContain(TENANT)
                    .doesNotContain(CAMPAIGN)
                    .doesNotContain(DEFINITION);
        }
    }

    @Test
    void materializationAuditsCarryNoMemberValue() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00"));
        scenario.publish();
        String key = scenario.planDue(RuntimeScenario.PUBLISHED_AT).dueExecutionKeys().get(0);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), key);
        Instant nominal = scenario.occurrence(key).nominalInstant();
        String member = "member-9f3a-secret";
        scenario.withScriptedAudience(ScriptedAudiencePort.complete(
                scenario.tenantId(), List.of(member), nominal, nominal.minusSeconds(60)));

        scenario.materialization().materialize(scenario.tenantId(), key);

        assertThat(scenario.audits()).isNotEmpty();
        assertThat(scenario.audits()).allSatisfy(audit -> {
            assertThat(audit.toString()).doesNotContain(member);
            assertThat(audit.subjectId()).doesNotContain(member);
            assertThat(audit.executionKey()).doesNotContain(member);
        });
        assertThat(scenario.store.findParticipants(scenario.tenantId(), executionIdOf(scenario, key)))
                .allSatisfy(participant -> assertThat(participant.toString()).doesNotContain(member));
        assertThat(scenario.store.findSnapshotMembers(scenario.tenantId(), executionIdOf(scenario, key)))
                .allSatisfy(snapshot -> assertThat(snapshot.toString()).doesNotContain(member));
    }

    @Test
    void aBoundedFailureCarriesOnlyItsCodeAndInvariant() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00"));
        scenario.publish();

        RuntimeBoundaryException failure = null;
        try {
            scenario.materialization().materialize(scenario.tenantId(), "absent-execution-key");
        } catch (RuntimeBoundaryException rejected) {
            failure = rejected;
        }

        assertThat(failure).isNotNull();
        assertThat(failure.details()).containsOnlyKeys("invariant");
        assertThat(failure.getMessage()).doesNotContain(scenario.tenantId());
        assertThat(failure.toString()).doesNotContain(scenario.campaignId());
    }

    private static String executionIdOf(RuntimeScenario scenario, String executionKey) {
        return scenario.store.findExecution(scenario.tenantId(), executionKey).orElseThrow().executionId();
    }
}
