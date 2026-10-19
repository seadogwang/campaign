package com.company.campaign.runtime;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.OccurrenceDecision;
import com.company.campaign.fullpublish.ScheduleDueResult;
import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrence;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.runtime.testsupport.RuntimeScenario;
import com.company.campaign.runtime.testsupport.RuntimeTestPlans;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Focused T22 ingress tests: registration and occurrence delivery, idempotent redelivery, changed-hash
 * conflicts, skipped occurrences, fixed-{@code asOf}, missing work and tenant isolation.
 */
class RuntimeIngressTest {

    private static final Instant AS_OF = RuntimeScenario.PUBLISHED_AT;

    private static RuntimeScenario published() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.oncePlan("2026-10-03T07:00:00+08:00"));
        scenario.publish();
        return scenario;
    }

    private static RuntimeScenario publishedDaily() {
        RuntimeScenario scenario = RuntimeScenario.of(
                RuntimeTestPlans.dailyPlan("2026-09-28", "2026-10-01", "06:00"));
        scenario.publish();
        return scenario;
    }

    private static RuntimeBoundaryException failureOf(Runnable runnable) {
        return catchThrowableOfType(runnable::run, RuntimeBoundaryException.class);
    }

    /** One ingress boundary whose registration source answers with exactly the given event. */
    private static RuntimeIngressService ingressFor(
            RuntimeScenario scenario, ScheduleRegistrationOutboxEvent event) {
        RuntimeWorkSourcePort work = new RuntimeWorkSourcePort() {

            @Override
            public Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId) {
                return event.eventId().equals(eventId) ? Optional.of(event) : Optional.empty();
            }

            @Override
            public Optional<ScheduledOccurrenceRecord> findOccurrenceWork(
                    String campaignId, String executionKey) {
                return Optional.empty();
            }
        };
        return new RuntimeIngressService(
                work, new T21RuntimeWorkSource(scenario.publication.store), scenario.store, scenario.clock);
    }

    /** One ingress boundary whose occurrence source answers with exactly the given record. */
    private static RuntimeIngressService occurrenceIngressFor(
            RuntimeScenario scenario, ScheduledOccurrenceRecord record) {
        RuntimeWorkSourcePort work = new RuntimeWorkSourcePort() {

            @Override
            public Optional<ScheduleRegistrationOutboxEvent> findRegistrationWork(String eventId) {
                return Optional.empty();
            }

            @Override
            public Optional<ScheduledOccurrenceRecord> findOccurrenceWork(
                    String campaignId, String executionKey) {
                return record.campaignId().equals(campaignId) && record.executionKey().equals(executionKey)
                        ? Optional.of(record)
                        : Optional.empty();
            }
        };
        return new RuntimeIngressService(
                work, new T21RuntimeWorkSource(scenario.publication.store), scenario.store, scenario.clock);
    }

    /** The same committed occurrence with different record bytes and the same stable execution key. */
    private static ScheduledOccurrenceRecord rehashed(
            ScheduledOccurrenceRecord record, FullPublishedDefinition definition, Instant committedAt) {
        ScheduledOccurrence occurrence = ScheduledOccurrence.of(
                record.executionKey(), record.fullDefinitionId(), definition.publicationRevision(),
                record.fullDefinitionHash(), record.scheduleHash(), record.localDate(), record.zoneId(),
                record.nominalInstant(), record.dueInstant(), record.decision(), record.reasonCode());
        return ScheduledOccurrenceRecord.of(
                occurrence, record.campaignId(), record.fullDefinitionId(), committedAt);
    }

    // ------------------------------------------------------------------ registration

    @Test
    void registrationDeliveryRecordsOneReceiptWithoutCreatingAnExecution() {
        RuntimeScenario scenario = published();
        ScheduleRegistrationOutboxEvent event = scenario.registrationEvent();

        RuntimeDeliveryResult result = scenario.ingress().deliverRegistration(event.eventId());

        assertThat(result.disposition()).isEqualTo(RuntimeDeliveryDisposition.REGISTERED);
        assertThat(result.execution()).isNull();
        assertThat(result.inbox().sourceKind()).isEqualTo(InboxSourceKind.SCHEDULE_REGISTRATION);
        assertThat(result.inbox().outcome()).isEqualTo(InboxOutcome.REGISTERED);
        assertThat(result.inbox().sourceHash()).isEqualTo(event.eventHash());
        assertThat(result.inbox().tenantId()).isEqualTo(scenario.tenantId());
        assertThat(result.inbox().campaignId()).isEqualTo(scenario.campaignId());
        assertThat(scenario.store.findInbox(InboxSourceKind.SCHEDULE_REGISTRATION, event.eventId())).isPresent();
        assertThat(scenario.store.executionCount()).isZero();
        assertThat(scenario.audits()).hasSize(1);
        assertThat(scenario.audits().get(0).reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_REGISTRATION_RECEIVED);
    }

    @Test
    void exactRegistrationRedeliveryWritesNothingAndReturnsTheOriginalReceipt() {
        RuntimeScenario scenario = published();
        String eventId = scenario.registrationEvent().eventId();

        RuntimeDeliveryResult first = scenario.ingress().deliverRegistration(eventId);
        RuntimeDeliveryResult second = scenario.ingress().deliverRegistration(eventId);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.disposition()).isEqualTo(RuntimeDeliveryDisposition.DUPLICATE);
        assertThat(second.inbox()).isEqualTo(first.inbox());
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
        assertThat(scenario.store.auditCount()).isEqualTo(1);
    }

    @Test
    void registrationWithChangedHashUnderTheSameIdentityIsAConflict() {
        RuntimeScenario scenario = published();
        ScheduleRegistrationOutboxEvent event = scenario.registrationEvent();
        scenario.ingress().deliverRegistration(event.eventId());

        ScheduleRegistrationOutboxEvent changed = ScheduleRegistrationOutboxEvent.of(
                event.eventId(), event.campaignId(), event.fullDefinitionId(), event.publicationRevision(),
                event.fullDefinitionHash(), event.baseDefinitionChecksum(), event.journeyHash(),
                event.scheduleHash(), event.runtimeAvailabilityPinHash(), event.occurredAt().plusSeconds(60));

        RuntimeBoundaryException failure = failureOf(
                () -> ingressFor(scenario, changed).deliverRegistration(changed.eventId()));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_INBOX_CONFLICT);
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
        assertThat(scenario.store.executionCount()).isZero();
    }

    @Test
    void registrationNamingAnotherDefinitionHashIsRefusedWithoutWriting() {
        RuntimeScenario scenario = published();
        ScheduleRegistrationOutboxEvent event = scenario.registrationEvent();
        ScheduleRegistrationOutboxEvent mismatched = ScheduleRegistrationOutboxEvent.of(
                event.eventId(), event.campaignId(), event.fullDefinitionId(), event.publicationRevision(),
                ContentHash.sha256("another-definition".getBytes(StandardCharsets.UTF_8)),
                event.baseDefinitionChecksum(), event.journeyHash(), event.scheduleHash(),
                event.runtimeAvailabilityPinHash(), event.occurredAt());

        RuntimeBoundaryException failure = failureOf(
                () -> ingressFor(scenario, mismatched).deliverRegistration(mismatched.eventId()));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_DEFINITION_HASH_MISMATCH);
        assertThat(scenario.store.inboxCount()).isZero();
    }

    @Test
    void missingRegistrationWorkIsABoundedFailure() {
        RuntimeScenario scenario = published();

        RuntimeBoundaryException failure =
                failureOf(() -> scenario.ingress().deliverRegistration("unknown-event"));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND);
    }

    // ------------------------------------------------------------------ occurrence

    @Test
    void dueOccurrenceCreatesExactlyOneExecutionFrozenToTheNominalInstant() {
        RuntimeScenario scenario = published();
        ScheduleDueResult plan = scenario.planDue(AS_OF);
        assertThat(plan.dueExecutionKeys()).hasSize(1);
        String executionKey = plan.dueExecutionKeys().get(0);
        ScheduledOccurrenceRecord occurrence = scenario.occurrence(executionKey);

        RuntimeDeliveryResult result = scenario.ingress().deliverOccurrence(
                scenario.campaignId(), executionKey);

        assertThat(result.disposition()).isEqualTo(RuntimeDeliveryDisposition.EXECUTION_CREATED);
        CampaignExecution execution = result.execution();
        assertThat(execution).isNotNull();
        assertThat(execution.executionKey()).isEqualTo(executionKey);
        assertThat(execution.tenantId()).isEqualTo(scenario.tenantId());
        assertThat(execution.asOf()).isEqualTo(occurrence.nominalInstant());
        assertThat(execution.nominalInstant()).isEqualTo(occurrence.nominalInstant());
        assertThat(execution.dueInstant()).isEqualTo(occurrence.dueInstant());
        assertThat(execution.status()).isEqualTo(ExecutionStatus.CREATED);
        assertThat(execution.revision()).isZero();
        assertThat(execution.contentHash()).isNotNull();
        assertThat(scenario.store.executionCount()).isEqualTo(1);
        assertThat(scenario.store.findExecution(scenario.tenantId(), executionKey)).contains(execution);
    }

    @Test
    void exactOccurrenceRedeliveryIsANoOp() {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);

        RuntimeDeliveryResult first =
                scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey);
        RuntimeDeliveryResult second =
                scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.execution()).isEqualTo(first.execution());
        assertThat(scenario.store.executionCount()).isEqualTo(1);
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
    }

    @Test
    void occurrenceWithChangedHashUnderTheSameExecutionKeyIsAConflict() {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        ScheduledOccurrenceRecord occurrence = scenario.occurrence(executionKey);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey);

        ScheduledOccurrenceRecord changed = rehashed(
                occurrence, scenario.definition(), occurrence.committedAt().plusSeconds(60));

        RuntimeBoundaryException failure = failureOf(() -> occurrenceIngressFor(scenario, changed)
                .deliverOccurrence(scenario.campaignId(), executionKey));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_INBOX_CONFLICT);
        assertThat(scenario.store.executionCount()).isEqualTo(1);
    }

    @Test
    void skippedOccurrenceIsRecordedWithoutCreatingAnExecution() {
        RuntimeScenario scenario = publishedDaily();
        ScheduleDueResult plan = scenario.planDue(AS_OF);
        assertThat(plan.skippedExecutionKeys()).isNotEmpty();
        String skippedKey = plan.skippedExecutionKeys().get(0);
        assertThat(scenario.occurrence(skippedKey).decision()).isEqualTo(OccurrenceDecision.SKIPPED);

        RuntimeDeliveryResult result =
                scenario.ingress().deliverOccurrence(scenario.campaignId(), skippedKey);

        assertThat(result.disposition()).isEqualTo(RuntimeDeliveryDisposition.SKIPPED);
        assertThat(result.execution()).isNull();
        assertThat(result.inbox().outcome()).isEqualTo(InboxOutcome.SKIPPED);
        assertThat(result.inbox().executionKey()).isNull();
        assertThat(scenario.store.executionCount()).isZero();
        assertThat(scenario.audits()).hasSize(1);
        assertThat(scenario.audits().get(0).reasonCode())
                .isEqualTo(RuntimeAuditRecord.REASON_OCCURRENCE_SKIPPED);
    }

    @Test
    void occurrenceOutsideTheTenantsCampaignsIsNotFound() {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);

        RuntimeBoundaryException failure = failureOf(
                () -> scenario.ingress().deliverOccurrence("another-campaign", executionKey));

        assertThat(failure.code()).isEqualTo(RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND);
        assertThat(scenario.store.executionCount()).isZero();
    }

    @Test
    void committedOccurrenceIsDeliveredOnTheLaterDeliveryInstantOnly() {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        ScheduledOccurrenceRecord occurrence = scenario.occurrence(executionKey);

        // The delivery clock is two days after the occurrence; the Execution still freezes the nominal instant.
        assertThat(RuntimeScenario.DELIVERED_AT).isAfter(occurrence.nominalInstant());
        CampaignExecution execution = scenario.ingress()
                .deliverOccurrence(scenario.campaignId(), executionKey).execution();

        assertThat(execution.asOf()).isEqualTo(occurrence.nominalInstant());
        assertThat(execution.asOf()).isNotEqualTo(RuntimeScenario.DELIVERED_AT);
        assertThat(execution.createdAt()).isEqualTo(RuntimeScenario.DELIVERED_AT);
    }

    @Test
    void registrationAndOccurrenceIdentitiesDoNotShadowEachOther() {
        RuntimeScenario scenario = published();
        String executionKey = scenario.planDue(AS_OF).dueExecutionKeys().get(0);
        ScheduleRegistrationOutboxEvent event = scenario.registrationEvent();
        scenario.ingress().deliverRegistration(event.eventId());

        // The two kinds share no identity space, so both receipts coexist even under one identifier.
        assertThat(scenario.store.inboxCount()).isEqualTo(1);
        scenario.ingress().deliverOccurrence(scenario.campaignId(), executionKey);
        assertThat(scenario.store.inboxCount()).isEqualTo(2);
        assertThat(scenario.store.executionCount()).isEqualTo(1);
    }

    @Test
    void unheldWorkNeverWritesAReceipt() {
        RuntimeScenario scenario = published();

        assertThatThrownBy(() -> scenario.ingress().deliverRegistration("shared-identity"))
                .isInstanceOf(RuntimeBoundaryException.class);
        assertThat(scenario.store.inboxCount()).isZero();
        assertThat(scenario.store.executionCount()).isZero();
    }
}