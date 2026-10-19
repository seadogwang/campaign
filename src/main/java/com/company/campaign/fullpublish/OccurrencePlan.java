package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The complete, immutable, hash-bound outcome of one deterministic occurrence planning evaluation.
 *
 * <p>It carries the explicit {@code asOf} it was planned at, the exact published version and schedule identity
 * it planned for, and the canonical, ordered occurrence decisions — a {@code DUE} occurrence, an explicit
 * {@code SKIPPED} older missed date, or nothing at all for a date that has not become due yet. It carries no
 * Execution, Participant, task or provider fact: planning decides what would become due, never what runs.
 *
 * <p>The plan is deterministically ordered and its own canonical hash covers the plan material, so planning the
 * same state twice produces byte-identical output and re-planning a state whose occurrences were already
 * emitted produces no occurrence for them.
 */
public record OccurrencePlan(
        String publishedId,
        long publishedRevision,
        ContentHash publishedDefinitionHash,
        ContentHash scheduleHash,
        Instant publishedInstant,
        Instant asOf,
        List<ScheduledOccurrence> occurrences,
        ContentHash planHash
) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public OccurrencePlan {
        ScheduleSupport.requireIdentifier(publishedId, "publishedId");
        if (publishedRevision < 1) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "publishedRevision");
        }
        Objects.requireNonNull(publishedDefinitionHash, "publishedDefinitionHash");
        Objects.requireNonNull(scheduleHash, "scheduleHash");
        Objects.requireNonNull(publishedInstant, "publishedInstant");
        Objects.requireNonNull(asOf, "asOf");
        if (occurrences == null || occurrences.size() > ScheduleVersions.MAX_OCCURRENCES) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "occurrences");
        }
        List<ScheduledOccurrence> ordered = occurrences.stream()
                .peek(occurrence -> {
                    if (occurrence == null || !occurrence.scheduleHash().equals(scheduleHash)
                            || !occurrence.publishedId().equals(publishedId)
                            || occurrence.publishedRevision() != publishedRevision
                            || !occurrence.publishedDefinitionHash().equals(publishedDefinitionHash)) {
                        throw ScheduleSupport.invalid(
                                ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "occurrenceBinding");
                    }
                })
                .sorted(ScheduledOccurrence::order)
                .toList();
        Objects.requireNonNull(planHash, "planHash");
        if (!planHash.equals(CANONICAL_JSON.hash(material(
                publishedId, publishedRevision, publishedDefinitionHash, scheduleHash, publishedInstant, asOf,
                ordered)))) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "planHash");
        }
        occurrences = ordered;
    }

    /** Builds one occurrence plan and stamps it with its own canonical hash. */
    public static OccurrencePlan of(
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            Instant publishedInstant,
            Instant asOf,
            List<ScheduledOccurrence> occurrences) {
        List<ScheduledOccurrence> ordered = Objects.requireNonNull(occurrences, "occurrences").stream()
                .sorted(ScheduledOccurrence::order)
                .toList();
        return new OccurrencePlan(
                publishedId, publishedRevision, publishedDefinitionHash, scheduleHash, publishedInstant, asOf,
                ordered,
                CANONICAL_JSON.hash(material(
                        publishedId, publishedRevision, publishedDefinitionHash, scheduleHash, publishedInstant,
                        asOf, ordered)));
    }

    /** The occurrences with a {@link OccurrenceDecision#DUE} decision, in canonical order. */
    public List<ScheduledOccurrence> due() {
        return occurrences.stream().filter(occurrence -> occurrence.decision().due()).toList();
    }

    private static Material material(
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            Instant publishedInstant,
            Instant asOf,
            List<ScheduledOccurrence> occurrences) {
        return new Material(publishedId, publishedRevision, publishedDefinitionHash, scheduleHash,
                publishedInstant, asOf, occurrences);
    }

    /** Redacted diagnostics: no owner value is printed. */
    @Override
    public String toString() {
        return "OccurrencePlan[publishedRevision=" + publishedRevision
                + ", occurrences=" + occurrences.size()
                + ", due=" + due().size()
                + ", asOf=" + asOf
                + ", planHash=" + planHash + ']';
    }

    /** Package-private hash material. */
    record Material(
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            Instant publishedInstant,
            Instant asOf,
            List<ScheduledOccurrence> occurrences) {
    }
}