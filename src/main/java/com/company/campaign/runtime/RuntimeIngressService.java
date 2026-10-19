package com.company.campaign.runtime;

import com.company.campaign.fullpublish.FullPublishedDefinition;
import com.company.campaign.fullpublish.OccurrenceDecision;
import com.company.campaign.fullpublish.ScheduleRegistrationOutboxEvent;
import com.company.campaign.fullpublish.ScheduledOccurrenceRecord;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The Runtime ingress boundary: it consumes exact committed T21 work into durable Runtime state.
 *
 * <p>Two deliveries are supported, and neither trusts its envelope. A schedule-registration delivery loads one
 * exact outbox event, resolves the full definition the event names and re-proves every pin the event carries —
 * its own definition hash, publication revision, Journey hash, Schedule hash, base-definition checksum and
 * runtime schema — before recording a receipt. An occurrence delivery loads one exact committed occurrence,
 * re-proves the same binding, records a {@code SKIPPED} occurrence without any Execution, and turns a
 * {@code DUE} occurrence into exactly one Execution per {@code (tenantId, executionKey)}.
 *
 * <p>Tenant, program and Campaign identity are taken from the loaded frozen definition, never from a delivery
 * envelope, a thread context or a caller argument: a delivery states <em>which</em> work it is, and the frozen
 * definition states <em>whose</em> it is. The frozen evaluation instant is the occurrence's own
 * {@code nominalInstant}, so a delayed delivery cannot widen the audience by evaluating later.
 *
 * <p>Nothing here claims a lease, advances a node, calls a Provider, sends a message or grants a reward.
 * {@link #toString()} prints no owner value.
 */
public final class RuntimeIngressService {

    /** The bounded subject kind a registration receipt's audit record names. */
    public static final String SUBJECT_INBOX = "INBOX";

    /** The bounded subject kind an Execution lifecycle audit record names. */
    public static final String SUBJECT_EXECUTION = "EXECUTION";

    private final RuntimeWorkSourcePort workSource;
    private final RuntimeDefinitionLookupPort definitions;
    private final RuntimeStore store;
    private final Clock clock;

    public RuntimeIngressService(
            RuntimeWorkSourcePort workSource,
            RuntimeDefinitionLookupPort definitions,
            RuntimeStore store,
            Clock clock) {
        this.workSource = Objects.requireNonNull(workSource, "workSource");
        this.definitions = Objects.requireNonNull(definitions, "definitions");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ registration delivery

    /**
     * Consumes one exact scheduling-registration outbox event into the Inbox.
     *
     * <p>An exact redelivery returns the original receipt and writes nothing. The same event identifier bound to
     * different event bytes is a bounded conflict. The event produces no Execution of its own: a registration
     * only records that a published version's schedule is registered with the runtime.
     */
    public RuntimeDeliveryResult deliverRegistration(String eventId) {
        RuntimeSupport.requireIdentifier(eventId, "eventId");
        ScheduleRegistrationOutboxEvent event = workSource.findRegistrationWork(eventId)
                .orElseThrow(() -> missingSource("registrationWork"));

        FullPublishedDefinition definition = loadBoundDefinition(
                event.fullDefinitionId(), event.fullDefinitionHash());
        requireRegistrationBindings(definition, event);

        Instant receivedAt = clock.instant();
        InboxRecord inbox = InboxRecord.of(
                InboxSourceKind.SCHEDULE_REGISTRATION, event.eventId(), event.eventHash(),
                definition.tenantId(), definition.campaignId(), definition.fullDefinitionId(),
                definition.publicationRevision(), null, InboxOutcome.REGISTERED, receivedAt);
        RuntimeAuditRecord audit = audit(
                definition.tenantId(), RuntimeAuditRecord.REASON_REGISTRATION_RECEIVED, SUBJECT_INBOX,
                event.eventId(), null, null, null, receivedAt);
        RuntimeStore.DeliveryCommitResult result =
                store.commitDelivery(new DeliveryCommit(inbox, null, audit));
        return new RuntimeDeliveryResult(result.inbox(), result.execution(),
                result.duplicate()
                        ? RuntimeDeliveryDisposition.DUPLICATE
                        : RuntimeDeliveryDisposition.REGISTERED);
    }

    // ------------------------------------------------------------------ occurrence delivery

    /**
     * Consumes one exact committed occurrence into the Inbox and, for a {@code DUE} occurrence, into exactly one
     * Execution.
     *
     * <p>The Campaign identifier and execution key are lookup keys, never authority: both are re-proved against
     * the loaded occurrence record and the frozen definition. An exact redelivery returns the original receipt
     * and Execution and writes nothing; the same execution key bound to different occurrence bytes is a bounded
     * conflict, never a second Execution.
     */
    public RuntimeDeliveryResult deliverOccurrence(String campaignId, String executionKey) {
        RuntimeSupport.requireIdentifier(campaignId, "campaignId");
        RuntimeSupport.requireIdentifier(executionKey, "executionKey");
        ScheduledOccurrenceRecord occurrence = workSource.findOccurrenceWork(campaignId, executionKey)
                .orElseThrow(() -> missingSource("occurrenceWork"));
        if (!occurrence.campaignId().equals(campaignId)
                || !occurrence.executionKey().equals(executionKey)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "occurrenceLookup",
                    "A committed occurrence does not witness the Campaign and execution key it was read as.");
        }

        FullPublishedDefinition definition = loadBoundDefinition(
                occurrence.fullDefinitionId(), occurrence.fullDefinitionHash());
        requireOccurrenceBindings(definition, occurrence);

        Instant receivedAt = clock.instant();
        if (occurrence.decision() == OccurrenceDecision.SKIPPED) {
            InboxRecord inbox = InboxRecord.of(
                    InboxSourceKind.SCHEDULE_OCCURRENCE, occurrence.executionKey(), occurrence.recordHash(),
                    definition.tenantId(), definition.campaignId(), definition.fullDefinitionId(),
                    definition.publicationRevision(), null, InboxOutcome.SKIPPED, receivedAt);
            RuntimeAuditRecord audit = audit(
                    definition.tenantId(), RuntimeAuditRecord.REASON_OCCURRENCE_SKIPPED, SUBJECT_INBOX,
                    occurrence.executionKey(), null, null, null, receivedAt);
            RuntimeStore.DeliveryCommitResult result =
                    store.commitDelivery(new DeliveryCommit(inbox, null, audit));
            return new RuntimeDeliveryResult(result.inbox(), result.execution(),
                    result.duplicate()
                            ? RuntimeDeliveryDisposition.DUPLICATE
                            : RuntimeDeliveryDisposition.SKIPPED);
        }

        // The frozen evaluation instant is the occurrence's nominal instant, never the delivery instant.
        CampaignExecution execution = CampaignExecution.created(
                occurrence.executionKey(), definition.tenantId(), definition.programId(),
                definition.campaignId(), definition.fullDefinitionId(), definition.contentHash(),
                definition.publicationRevision(), definition.journeyHash(), definition.scheduleHash(),
                occurrence.nominalInstant(), occurrence.dueInstant(), receivedAt);
        InboxRecord inbox = InboxRecord.of(
                InboxSourceKind.SCHEDULE_OCCURRENCE, occurrence.executionKey(), occurrence.recordHash(),
                definition.tenantId(), definition.campaignId(), definition.fullDefinitionId(),
                definition.publicationRevision(), occurrence.executionKey(), InboxOutcome.EXECUTION_CREATED,
                receivedAt);
        RuntimeAuditRecord audit = audit(
                definition.tenantId(), RuntimeAuditRecord.REASON_EXECUTION_CREATED, SUBJECT_EXECUTION,
                execution.executionId(), occurrence.executionKey(), null, execution.revision(), receivedAt);
        RuntimeStore.DeliveryCommitResult result =
                store.commitDelivery(new DeliveryCommit(inbox, execution, audit));
        return new RuntimeDeliveryResult(result.inbox(), result.execution(),
                result.duplicate()
                        ? RuntimeDeliveryDisposition.DUPLICATE
                        : RuntimeDeliveryDisposition.EXECUTION_CREATED);
    }

    // ------------------------------------------------------------------ shared re-proofs

    private FullPublishedDefinition loadBoundDefinition(String fullDefinitionId, ContentHash pinnedHash) {
        FullPublishedDefinition definition = definitions.findFullDefinition(fullDefinitionId)
                .orElseThrow(() -> new RuntimeBoundaryException(
                        RuntimeErrorCodes.RUNTIME_DEFINITION_NOT_FOUND, "fullDefinitionId",
                        "The frozen full definition this work item names is not held by the host."));
        if (!definition.contentHash().equals(pinnedHash)) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_DEFINITION_HASH_MISMATCH, "fullDefinitionHash",
                    "The frozen full definition does not match the hash the work item pinned.");
        }
        return definition;
    }

    /**
     * Re-proves every pin a committed scheduling-registration event carries against the loaded definition.
     *
     * <p>The definition is the authority; the event is only evidence that something was committed. An event that
     * names a real definition but pins a different revision, Journey, Schedule, base checksum,
     * runtime-availability evidence or runtime schema is refused rather than partially believed. The event's own
     * canonical hash is re-proved by its construction, so an event whose bytes do not match its own hash is not
     * representable in the first place.
     */
    private static void requireRegistrationBindings(
            FullPublishedDefinition definition, ScheduleRegistrationOutboxEvent event) {
        if (!definition.campaignId().equals(event.campaignId())
                || definition.publicationRevision() != event.publicationRevision()
                || !definition.journeyHash().equals(event.journeyHash())
                || !definition.scheduleHash().equals(event.scheduleHash())
                || !definition.baseDefinitionChecksum().equals(event.baseDefinitionChecksum())
                || !definition.runtimeAvailabilityHash().equals(event.runtimeAvailabilityPinHash())
                || !RuntimeVersions.RUNTIME_SCHEMA_VERSION.equals(event.runtimeSchemaVersion())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "registrationBinding",
                    "A committed registration event does not witness exactly the frozen definition it names.");
        }
    }

    /**
     * Re-proves every pin a committed occurrence record carries against the loaded definition.
     *
     * <p>A committed occurrence is already bound to the exact frozen version it was planned from; the check
     * here is that the loaded definition really is that version and really declares the Schedule the occurrence
     * was planned from. The record's own canonical hash is re-proved by its construction.
     */
    private static void requireOccurrenceBindings(
            FullPublishedDefinition definition, ScheduledOccurrenceRecord occurrence) {
        if (!definition.campaignId().equals(occurrence.campaignId())
                || !definition.scheduleHash().equals(occurrence.scheduleHash())) {
            throw new RuntimeBoundaryException(
                    RuntimeErrorCodes.RUNTIME_BINDING_MISMATCH, "occurrenceBinding",
                    "A committed occurrence does not witness exactly the frozen definition it names.");
        }
    }

    private static RuntimeAuditRecord audit(
            String tenantId,
            String reasonCode,
            String subjectKind,
            String subjectId,
            String executionKey,
            Long beforeRevision,
            Long afterRevision,
            Instant occurredAt) {
        return RuntimeAuditRecord.of(
                RuntimeSupport.derivedId("audit", List.of(reasonCode, tenantId, subjectId)),
                tenantId, reasonCode, subjectKind, subjectId, executionKey, beforeRevision, afterRevision,
                occurredAt);
    }

    private static RuntimeBoundaryException missingSource(String field) {
        return new RuntimeBoundaryException(
                RuntimeErrorCodes.RUNTIME_SOURCE_NOT_FOUND, field,
                "The exact committed work item a delivery names is not held by the host.");
    }

    /** Redacted diagnostics: the service holds no owner value. */
    @Override
    public String toString() {
        return "RuntimeIngressService[]";
    }
}