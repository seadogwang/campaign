package com.company.campaign.fullpublish;

import com.company.campaign.lifecycle.LifecycleException;
import com.company.campaign.shared.canonical.ContentHash;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Focused T21 section-2/5 contract tests: every immutable artifact re-proves its own hash and its bindings. */
class FullPublishContractIntegrityTest {

    private static FullPublishScenario published() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();
        return scenario;
    }

    private static FullPublishedDefinition definition(FullPublishScenario scenario) {
        return scenario.store.findFullDefinition(
                scenario.activeSchedule().fullDefinitionId()).orElseThrow();
    }

    // ------------------------------------------------------------------ runtime availability pin

    @Test
    void aRuntimeAvailabilityPinRefusesAnotherRuntimeVersionOrATamperedHash() {
        assertThatThrownBy(() -> RuntimeAvailabilityPin.of(
                "campaign-runtime/9.9", RuntimeAvailabilityState.AVAILABLE,
                FullPublishTestFixtures.hash("evidence")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE);

        assertThatThrownBy(() -> new RuntimeAvailabilityPin(
                FullPublishVersions.RUNTIME_AVAILABILITY_FORMAT_VERSION,
                FullPublishVersions.RUNTIME_SCHEMA_VERSION, RuntimeAvailabilityState.AVAILABLE,
                FullPublishTestFixtures.hash("evidence"), FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_RUNTIME_UNAVAILABLE);
    }

    // ------------------------------------------------------------------ full definition

    @Test
    void aFullDefinitionRefusesAJourneyOrScheduleThatDoesNotDescendFromItsOwnManifest() {
        FullPublishedDefinition real = definition(published());

        assertThatThrownBy(() -> new FullPublishedDefinition(
                real.formatVersion(), real.fullDefinitionId(), real.publicationRevision(), real.tenantId(),
                real.programId(), real.userId(), real.dataScopeId(), real.campaignId(),
                real.campaignRevision(), real.baseIdentity(), FullPublishTestFixtures.hash("other-manifest"),
                real.journey(), real.schedule(), real.runtimeAvailability(), real.publishedAt(),
                real.contentHash()))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_DEFINITION_INVALID);

        assertThatThrownBy(() -> new FullPublishedDefinition(
                real.formatVersion(), real.fullDefinitionId(), real.publicationRevision(), real.tenantId(),
                real.programId(), real.userId(), real.dataScopeId(), real.campaignId(),
                real.campaignRevision(), real.baseIdentity(), real.manifestHash(), real.journey(),
                CompiledSchedule.of(
                        com.company.campaign.builder.capability.schedule.ScheduleBasicForm.IMMEDIATE,
                        FullPublishTestFixtures.ZONE, null, null, null, null,
                        FullPublishTestFixtures.component(
                                FullPublishTestFixtures.immediatePlan(),
                                com.company.campaign.builder.capability.schedule.ScheduleBasicVersions
                                        .CAPABILITY_CODE).logicalId(),
                        FullPublishTestFixtures.hash("payload"), real.manifestHash(),
                        FullPublishTestFixtures.hash("another-journey")),
                real.runtimeAvailability(), real.publishedAt(), real.contentHash()))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_DEFINITION_INVALID);
    }

    @Test
    void aFullDefinitionRefusesAnUnavailableRuntimePinOrATamperedContentHash() {
        FullPublishedDefinition real = definition(published());

        RuntimeAvailabilityPin unavailable = RuntimeAvailabilityPin.of(
                FullPublishVersions.RUNTIME_SCHEMA_VERSION, RuntimeAvailabilityState.UNAVAILABLE,
                FullPublishTestFixtures.hash("evidence"));

        assertThatThrownBy(() -> new FullPublishedDefinition(
                real.formatVersion(), real.fullDefinitionId(), real.publicationRevision(), real.tenantId(),
                real.programId(), real.userId(), real.dataScopeId(), real.campaignId(),
                real.campaignRevision(), real.baseIdentity(), real.manifestHash(), real.journey(),
                real.schedule(), unavailable, real.publishedAt(), real.contentHash()))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_DEFINITION_INVALID);

        assertThatThrownBy(() -> new FullPublishedDefinition(
                real.formatVersion(), real.fullDefinitionId(), real.publicationRevision(), real.tenantId(),
                real.programId(), real.userId(), real.dataScopeId(), real.campaignId(),
                real.campaignRevision(), real.baseIdentity(), real.manifestHash(), real.journey(),
                real.schedule(), real.runtimeAvailability(), real.publishedAt(),
                FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_DEFINITION_INVALID);
    }

    // ------------------------------------------------------------------ receipt, state, outbox, occurrence

    @Test
    void aFullReceiptRefusesATamperedHash() {
        FullPublishScenario scenario = published();
        FullPublishedDefinition definition = definition(scenario);
        FullPublishReceipt real = scenario.store.findFullReceipt(
                scenario.context.tenantId(), scenario.context.programId(), scenario.context.userId(),
                scenario.lastPreparation().preparationId(), "idem-key-1").orElseThrow();

        assertThatThrownBy(() -> new FullPublishReceipt(
                real.formatVersion(), real.receiptId(), real.tenantId(), real.programId(), real.userId(),
                real.dataScopeId(), real.preparationId(), real.preparationHash(), real.idempotencyKey(),
                real.requestHash(), real.campaignId(), real.fullDefinitionId(), real.publicationRevision(),
                real.fullDefinitionHash(), real.basePublishedId(), real.baseDefinitionChecksum(),
                real.journeyHash(), real.scheduleHash(), real.confirmedRiskCodes(), real.publishedAt(),
                FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_RECEIPT_INVALID);

        assertThatThrownBy(() -> new FullPublishReceipt(
                real.formatVersion(), real.receiptId(), real.tenantId(), real.programId(), real.userId(),
                real.dataScopeId(), real.preparationId(), real.preparationHash(), real.idempotencyKey(),
                real.requestHash(), real.campaignId(), real.fullDefinitionId(), real.publicationRevision(),
                definition.contentHash(), real.basePublishedId(), real.baseDefinitionChecksum(),
                real.journeyHash(), real.scheduleHash(), real.confirmedRiskCodes(), real.publishedAt(),
                FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_RECEIPT_INVALID);
    }

    @Test
    void aScheduleProjectionRefusesATamperedHashOrAForeignRevision() {
        FullPublishScenario scenario = published();
        CampaignScheduleState real = scenario.activeSchedule();

        assertThatThrownBy(() -> new CampaignScheduleState(
                real.formatVersion(), real.campaignId(), real.tenantId(), real.programId(), real.userId(),
                real.dataScopeId(), real.stateRevision(), real.status(), real.fullDefinitionId(),
                real.fullDefinitionHash(), real.baseDefinitionChecksum(), real.journeyHash(), real.schedule(),
                real.publishedAt(), real.updatedAt(), FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.SCHEDULE_STATE_INVALID);

        assertThatThrownBy(() -> new CampaignScheduleState(
                real.formatVersion(), real.campaignId(), real.tenantId(), real.programId(), real.userId(),
                real.dataScopeId(), 0L, real.status(), real.fullDefinitionId(), real.fullDefinitionHash(),
                real.baseDefinitionChecksum(), real.journeyHash(), real.schedule(), real.publishedAt(),
                real.updatedAt(), real.stateHash()))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.SCHEDULE_STATE_INVALID);
    }

    @Test
    void aSchedulingRegistrationEventRefusesAForeignTopicOrATamperedHash() {
        FullPublishScenario scenario = published();
        ScheduleRegistrationOutboxEvent real = scenario.store.outboxEvents().get(0);

        assertThatThrownBy(() -> new ScheduleRegistrationOutboxEvent(
                real.formatVersion(), real.eventId(), "another.topic", real.scope(), real.campaignId(),
                real.fullDefinitionId(), real.publicationRevision(), real.fullDefinitionHash(),
                real.baseDefinitionChecksum(), real.journeyHash(), real.scheduleHash(),
                real.runtimeSchemaVersion(), real.runtimeAvailabilityPinHash(), real.occurredAt(),
                real.eventHash()))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_OUTBOX_INVALID);

        assertThatThrownBy(() -> new ScheduleRegistrationOutboxEvent(
                real.formatVersion(), real.eventId(), real.topic(), real.scope(), real.campaignId(),
                real.fullDefinitionId(), real.publicationRevision(), real.fullDefinitionHash(),
                real.baseDefinitionChecksum(), real.journeyHash(), real.scheduleHash(),
                real.runtimeSchemaVersion(), real.runtimeAvailabilityPinHash(), real.occurredAt(),
                FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.FULL_OUTBOX_INVALID);
    }

    @Test
    void aCommittedOccurrenceRefusesATamperedHash() {
        FullPublishScenario scenario = FullPublishScenario.immediate();
        scenario.publish();
        scenario.operationService().planDue(
                new ScheduleDueCommand(scenario.campaignId(), 1L, Instant.parse("2026-10-03T00:05:00Z")),
                scenario.context);
        ScheduledOccurrenceRecord real =
                scenario.store.findCommittedOccurrences(scenario.campaignId()).get(0);

        assertThatThrownBy(() -> new ScheduledOccurrenceRecord(
                real.formatVersion(), real.executionKey(), real.campaignId(), real.fullDefinitionId(),
                real.fullDefinitionHash(), real.scheduleHash(), real.localDate(), real.zoneId(),
                real.nominalInstant(), real.dueInstant(), real.decision(), real.reasonCode(),
                real.committedAt(), FullPublishTestFixtures.hash("tampered")))
                .isInstanceOf(LifecycleException.class)
                .hasFieldOrPropertyWithValue("code", FullPublishErrorCodes.OCCURRENCE_RECORD_INVALID);

        assertThat(real.decision()).isEqualTo(OccurrenceDecision.DUE);
    }

    // ------------------------------------------------------------------ unsupported versions

    @Test
    void anUnsupportedScheduleVersionIsRefusedBeforeAnythingIsFrozen() {
        assertThatThrownBy(() -> new ScheduleCompileRequest(
                "schedule-0001", "schedule.basic", "1.0",
                com.company.campaign.builder.compiler.CapabilityPayload.of(java.util.Map.of(
                        "form", "IMMEDIATE", "zoneId", FullPublishTestFixtures.ZONE)),
                FullPublishTestFixtures.hash("manifest"), FullPublishTestFixtures.hash("journey"),
                "campaign-schedule/9.9",
                ScheduleVersions.SCHEDULE_COMPILER_VERSION,
                ScheduleVersions.RUNTIME_SCHEMA_VERSION))
                .isInstanceOf(ScheduleException.class);
    }

    @Test
    void aFullPreparationNeverCarriesTrustedFactsOnItsCommand() {
        assertThatThrownBy(() -> new com.company.campaign.lifecycle.PublishPreparationRef(
                "not a bounded identifier!", ContentHash.sha256("x".getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}