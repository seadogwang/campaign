package com.company.campaign.fullpublish;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The complete, immutable, hash-bound decision of one plannable schedule occurrence.
 *
 * <p>It freezes the exact published version identity and hash the occurrence belongs to, the schedule hash it
 * was planned from, the local date when the schedule is daily, the canonical zone, the nominal scheduled
 * instant, the resolved due instant, the closed decision, a bounded reason code and a deterministically derived
 * execution key. It carries no Execution, no Participant, no task, no message body, no reward and no member
 * identity: planning decides when work would become due, never what the work is.
 *
 * <p>The execution key is derived from canonical hash material over the exact published version identity and
 * the nominal scheduled instant, so it is never a random identifier and duplicate planning over the same state
 * always produces the same key. {@link #toString()} prints no owner value and no local date.
 */
public record ScheduledOccurrence(
        String occurrenceFormatVersion,
        String executionKeyVersion,
        String executionKey,
        String publishedId,
        long publishedRevision,
        ContentHash publishedDefinitionHash,
        ContentHash scheduleHash,
        @JsonInclude(JsonInclude.Include.NON_NULL) String localDate,
        String zoneId,
        Instant nominalInstant,
        Instant dueInstant,
        OccurrenceDecision decision,
        String reasonCode,
        ContentHash occurrenceHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();
    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);
    private static final Pattern MACHINE_CODE = Pattern.compile(ContractPatterns.MACHINE_CODE);

    public ScheduledOccurrence {
        if (!ScheduleVersions.OCCURRENCE_FORMAT_VERSION.equals(occurrenceFormatVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "occurrenceFormatVersion");
        }
        if (!ScheduleVersions.EXECUTION_KEY_VERSION.equals(executionKeyVersion)) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_VERSION_UNSUPPORTED, "executionKeyVersion");
        }
        if (executionKey == null || !IDENTIFIER.matcher(executionKey).matches()) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "executionKey");
        }
        ScheduleSupport.requireIdentifier(publishedId, "publishedId");
        if (publishedRevision < 1) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "publishedRevision");
        }
        Objects.requireNonNull(publishedDefinitionHash, "publishedDefinitionHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        ScheduleSupport.canonicalZone(zoneId);
        if (localDate != null) {
            ScheduleSupport.canonicalDate(localDate);
        }
        Objects.requireNonNull(nominalInstant, "nominalInstant");
        Objects.requireNonNull(dueInstant, "dueInstant");
        Objects.requireNonNull(decision, "decision");
        if (reasonCode == null || !MACHINE_CODE.matcher(reasonCode).matches()) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "reasonCode");
        }
        Objects.requireNonNull(occurrenceHash, "occurrenceHash");
        if (!occurrenceHash.equals(CANONICAL_JSON.hash(material(
                occurrenceFormatVersion, executionKeyVersion, executionKey, publishedId, publishedRevision,
                publishedDefinitionHash, scheduleHash, localDate, zoneId, nominalInstant, dueInstant,
                decision, reasonCode)))) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "occurrenceHash");
        }
    }

    /** Builds one occurrence decision and stamps it with its own canonical hash. */
    public static ScheduledOccurrence of(
            String executionKey,
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            String localDate,
            String zoneId,
            Instant nominalInstant,
            Instant dueInstant,
            OccurrenceDecision decision,
            String reasonCode) {
        String canonicalZone = ScheduleSupport.canonicalZone(zoneId);
        String canonicalDate = localDate == null ? null : ScheduleSupport.canonicalDate(localDate);
        return new ScheduledOccurrence(
                ScheduleVersions.OCCURRENCE_FORMAT_VERSION,
                ScheduleVersions.EXECUTION_KEY_VERSION,
                executionKey, publishedId, publishedRevision, publishedDefinitionHash, scheduleHash,
                canonicalDate, canonicalZone, nominalInstant, dueInstant, decision, reasonCode,
                CANONICAL_JSON.hash(material(
                        ScheduleVersions.OCCURRENCE_FORMAT_VERSION,
                        ScheduleVersions.EXECUTION_KEY_VERSION,
                        executionKey, publishedId, publishedRevision, publishedDefinitionHash, scheduleHash,
                        canonicalDate, canonicalZone, nominalInstant, dueInstant, decision, reasonCode)));
    }

    /** The canonical local date of a daily occurrence, or {@code null} for another form. */
    public LocalDate localDateValue() {
        return localDate == null ? null : LocalDate.parse(localDate);
    }

    /** The canonical zone the occurrence is defined in. */
    public ZoneId zone() {
        return ZoneId.of(zoneId);
    }

    /**
     * Deterministic canonical order: by nominal instant, then local date, then execution key.
     *
     * <p>The caller's collection order carries no meaning, so it is never trusted: two plans of the same state
     * have the same order and therefore the same plan hash.
     */
    public static int order(ScheduledOccurrence left, ScheduledOccurrence right) {
        int nominal = left.nominalInstant.compareTo(right.nominalInstant);
        if (nominal != 0) {
            return nominal;
        }
        int date = Comparator.<String>nullsFirst(Comparator.naturalOrder())
                .compare(left.localDate, right.localDate);
        return date != 0 ? date : left.executionKey.compareTo(right.executionKey);
    }

    /** Redacted diagnostics: no owner value and no local date is printed. */
    @Override
    public String toString() {
        return "ScheduledOccurrence[executionKey=" + executionKey
                + ", decision=" + decision
                + ", reasonCode=" + reasonCode
                + ", nominalInstant=" + nominalInstant + ']';
    }

    /**
     * The hash material without the hash itself.
     *
     * <p>It is a static form because a record's compact constructor runs before its fields are assigned.
     */
    private static Material material(
            String occurrenceFormatVersion,
            String executionKeyVersion,
            String executionKey,
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            String localDate,
            String zoneId,
            Instant nominalInstant,
            Instant dueInstant,
            OccurrenceDecision decision,
            String reasonCode) {
        return new Material(occurrenceFormatVersion, executionKeyVersion, executionKey, publishedId,
                publishedRevision, publishedDefinitionHash, scheduleHash, localDate, zoneId, nominalInstant,
                dueInstant, decision, reasonCode);
    }

    /** Package-private hash material; the local date is omitted when the form is not daily. */
    record Material(
            String occurrenceFormatVersion,
            String executionKeyVersion,
            String executionKey,
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) String localDate,
            String zoneId,
            Instant nominalInstant,
            Instant dueInstant,
            OccurrenceDecision decision,
            String reasonCode) {
    }
}