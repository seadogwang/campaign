package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.Objects;

/**
 * One durable, hash-bound scheduling-registration outbox event: the work item a full publication emits.
 *
 * <p>It is deliberately bounded machine data only — the exact Campaign, full-definition and base-definition
 * identities, the exact Journey, Schedule and runtime-availability hashes, the frozen publication revision and
 * the instant — and nothing else. There is no member identity, no PII, no template body, no reward secret, no
 * SQL, no provider data and no trusted security context: a later T22 delivery reads identities and hashes and
 * resolves everything else itself.
 *
 * <p>T21 emits it inside the same transaction that freezes the definition and never delivers it. There is no
 * lease, no claim, no retry and no consumer here, so no queue or worker is reachable through this type.
 *
 * <p>Construction re-proves {@link #eventHash()} against its own canonical material, so an event whose bytes
 * do not match its own hash is not representable. {@link #toString()} prints no owner value.
 */
public record ScheduleRegistrationOutboxEvent(
        String formatVersion,
        String eventId,
        String topic,
        String scope,
        String campaignId,
        String fullDefinitionId,
        long publicationRevision,
        ContentHash fullDefinitionHash,
        ContentHash baseDefinitionChecksum,
        ContentHash journeyHash,
        ContentHash scheduleHash,
        String runtimeSchemaVersion,
        ContentHash runtimeAvailabilityPinHash,
        Instant occurredAt,
        ContentHash eventHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ScheduleRegistrationOutboxEvent {
        if (!FullPublishVersions.OUTBOX_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_OUTBOX_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(eventId, "eventId");
        if (!FullPublishVersions.OUTBOX_TOPIC.equals(topic)
                || !FullPublishVersions.OUTBOX_SCOPE.equals(scope)) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_OUTBOX_INVALID, "topic");
        }
        FullPublishSupport.requireIdentifier(campaignId, "campaignId");
        FullPublishSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        if (publicationRevision < 1) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_OUTBOX_INVALID, "publicationRevision");
        }
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(baseDefinitionChecksum, "baseDefinitionChecksum");
        Objects.requireNonNull(journeyHash, "journeyHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        if (!FullPublishVersions.RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.FULL_OUTBOX_INVALID, "runtimeSchemaVersion");
        }
        Objects.requireNonNull(runtimeAvailabilityPinHash, "runtimeAvailabilityPinHash");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(eventHash, "eventHash");
        if (!eventHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, eventId, topic, scope, campaignId, fullDefinitionId, publicationRevision,
                fullDefinitionHash, baseDefinitionChecksum, journeyHash, scheduleHash, runtimeSchemaVersion,
                runtimeAvailabilityPinHash, occurredAt)))) {
            throw FullPublishSupport.invalid(FullPublishErrorCodes.FULL_OUTBOX_INVALID, "eventHash");
        }
    }

    /** Builds one scheduling-registration outbox event and stamps it with its own canonical hash. */
    public static ScheduleRegistrationOutboxEvent of(
            String eventId,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            ContentHash runtimeAvailabilityPinHash,
            Instant occurredAt) {
        return new ScheduleRegistrationOutboxEvent(
                FullPublishVersions.OUTBOX_FORMAT_VERSION,
                eventId, FullPublishVersions.OUTBOX_TOPIC, FullPublishVersions.OUTBOX_SCOPE, campaignId,
                fullDefinitionId, publicationRevision, fullDefinitionHash, baseDefinitionChecksum, journeyHash,
                scheduleHash, FullPublishVersions.RUNTIME_SCHEMA_VERSION, runtimeAvailabilityPinHash, occurredAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.OUTBOX_FORMAT_VERSION,
                        eventId, FullPublishVersions.OUTBOX_TOPIC, FullPublishVersions.OUTBOX_SCOPE, campaignId,
                        fullDefinitionId, publicationRevision, fullDefinitionHash, baseDefinitionChecksum,
                        journeyHash, scheduleHash, FullPublishVersions.RUNTIME_SCHEMA_VERSION,
                        runtimeAvailabilityPinHash, occurredAt)));
    }

    private static Material material(
            String formatVersion,
            String eventId,
            String topic,
            String scope,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            String runtimeSchemaVersion,
            ContentHash runtimeAvailabilityPinHash,
            Instant occurredAt) {
        return new Material(formatVersion, eventId, topic, scope, campaignId, fullDefinitionId,
                publicationRevision, fullDefinitionHash, baseDefinitionChecksum, journeyHash, scheduleHash,
                runtimeSchemaVersion, runtimeAvailabilityPinHash, occurredAt);
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "ScheduleRegistrationOutboxEvent[eventId=" + eventId
                + ", topic=" + topic
                + ", publicationRevision=" + publicationRevision
                + ", occurredAt=" + occurredAt
                + ", eventHash=" + eventHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String formatVersion,
            String eventId,
            String topic,
            String scope,
            String campaignId,
            String fullDefinitionId,
            long publicationRevision,
            ContentHash fullDefinitionHash,
            ContentHash baseDefinitionChecksum,
            ContentHash journeyHash,
            ContentHash scheduleHash,
            String runtimeSchemaVersion,
            ContentHash runtimeAvailabilityPinHash,
            Instant occurredAt) {
    }
}