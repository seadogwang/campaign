package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * One immutable committed occurrence decision, bound to its exact execution key and its frozen published
 * version.
 *
 * <p>It is durable scheduling work and nothing more: it records that one occurrence of one exact published
 * version became due, or was explicitly skipped, at one exact instant. It carries no Execution, no Participant,
 * no member identity, no enqueue, no message body, no reward secret, no SQL and no trusted security context.
 *
 * <p>An already-committed record is never rewritten and never reassigned: publishing a newer version leaves it
 * bound to the older frozen version, so the work a version generated always remains attributable to that
 * version. {@link #toString()} prints no owner value and no local date.
 */
public record ScheduledOccurrenceRecord(
        String formatVersion,
        String executionKey,
        String campaignId,
        String fullDefinitionId,
        ContentHash fullDefinitionHash,
        ContentHash scheduleHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) String localDate,
        String zoneId,
        Instant nominalInstant,
        Instant dueInstant,
        OccurrenceDecision decision,
        String reasonCode,
        Instant committedAt,
        ContentHash recordHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ScheduledOccurrenceRecord {
        if (!FullPublishVersions.OCCURRENCE_RECORD_FORMAT_VERSION.equals(formatVersion)) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.OCCURRENCE_RECORD_INVALID, "formatVersion");
        }
        FullPublishSupport.requireIdentifier(executionKey, "executionKey");
        FullPublishSupport.requireIdentifier(campaignId, "campaignId");
        FullPublishSupport.requireIdentifier(fullDefinitionId, "fullDefinitionId");
        Objects.requireNonNull(fullDefinitionHash, "fullDefinitionHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(nominalInstant, "nominalInstant");
        Objects.requireNonNull(dueInstant, "dueInstant");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(reasonCode, "reasonCode");
        Objects.requireNonNull(committedAt, "committedAt");
        Objects.requireNonNull(recordHash, "recordHash");
        if (localDate != null) {
            LocalDate.parse(localDate);
        }
        ZoneId.of(zoneId);
        if (!recordHash.equals(CANONICAL_JSON.hash(material(
                formatVersion, executionKey, campaignId, fullDefinitionId, fullDefinitionHash, scheduleHash,
                localDate, zoneId, nominalInstant, dueInstant, decision, reasonCode, committedAt)))) {
            throw FullPublishSupport.invalid(
                    FullPublishErrorCodes.OCCURRENCE_RECORD_INVALID, "recordHash");
        }
    }

    /** Commits one planned occurrence decision as an immutable durable record. */
    public static ScheduledOccurrenceRecord of(
            ScheduledOccurrence occurrence,
            String campaignId,
            String fullDefinitionId,
            Instant committedAt) {
        Objects.requireNonNull(occurrence, "occurrence");
        return new ScheduledOccurrenceRecord(
                FullPublishVersions.OCCURRENCE_RECORD_FORMAT_VERSION,
                occurrence.executionKey(), campaignId, fullDefinitionId, occurrence.publishedDefinitionHash(),
                occurrence.scheduleHash(), occurrence.localDate(), occurrence.zoneId(),
                occurrence.nominalInstant(), occurrence.dueInstant(), occurrence.decision(),
                occurrence.reasonCode(), committedAt,
                CANONICAL_JSON.hash(material(
                        FullPublishVersions.OCCURRENCE_RECORD_FORMAT_VERSION,
                        occurrence.executionKey(), campaignId, fullDefinitionId,
                        occurrence.publishedDefinitionHash(), occurrence.scheduleHash(), occurrence.localDate(),
                        occurrence.zoneId(), occurrence.nominalInstant(), occurrence.dueInstant(),
                        occurrence.decision(), occurrence.reasonCode(), committedAt)));
    }

    private static Material material(
            String formatVersion,
            String executionKey,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash scheduleHash,
            String localDate,
            String zoneId,
            Instant nominalInstant,
            Instant dueInstant,
            OccurrenceDecision decision,
            String reasonCode,
            Instant committedAt) {
        return new Material(formatVersion, executionKey, campaignId, fullDefinitionId, fullDefinitionHash,
                scheduleHash, localDate, zoneId, nominalInstant, dueInstant, decision, reasonCode, committedAt);
    }

    /** Redacted diagnostics: no owner value and no local date is printed. */
    @Override
    public String toString() {
        return "ScheduledOccurrenceRecord[executionKey=" + executionKey
                + ", decision=" + decision
                + ", nominalInstant=" + nominalInstant
                + ", recordHash=" + recordHash + ']';
    }

    /** Package-private hash material; the local date is omitted for a non-daily occurrence. */
    record Material(
            String formatVersion,
            String executionKey,
            String campaignId,
            String fullDefinitionId,
            ContentHash fullDefinitionHash,
            ContentHash scheduleHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String localDate,
            String zoneId,
            Instant nominalInstant,
            Instant dueInstant,
            OccurrenceDecision decision,
            String reasonCode,
            Instant committedAt) {
    }
}