package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicForm;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The deterministic, pure, bounded T21 occurrence planner.
 *
 * <p>It is a pure function of one explicit {@link OccurrencePlanningRequest} with an explicit {@code asOf} and
 * no system clock: the same request always yields the same decisions, the same order and the same plan hash,
 * and re-planning the same state after some occurrences were committed produces no occurrence for a key that
 * was already emitted.
 *
 * <p>Occurrence semantics, frozen by this version:
 *
 * <ul>
 *   <li>{@code IMMEDIATE} has one nominal occurrence at the publication instant, and a first evaluation that
 *       happens later catches that one occurrence up;</li>
 *   <li>{@code ONCE} has one nominal occurrence at its canonical {@code runAt}, and a first evaluation after it
 *       catches that one occurrence up;</li>
 *   <li>{@code DAILY} has one nominal occurrence per inclusive local date, bounded to the accepted ninety-day
 *       window, and at most one occurrence per local date. After downtime only the most recent due un-emitted
 *       local date becomes {@link OccurrenceDecision#DUE}; every older missed due date becomes an explicit
 *       {@link OccurrenceDecision#SKIPPED}; a future date remains pending and produces no decision at all;</li>
 *   <li>a nonexistent daylight-saving local time resolves to the transition's next valid local instant and a
 *       repeated one to the earlier valid offset.</li>
 * </ul>
 *
 * <p>The execution key is derived from canonical hash material over the exact published version identity and
 * the nominal scheduled instant, so it is never a random identifier, it is stable across re-planning, and two
 * published versions can never collide on one key.
 */
public final class ScheduleOccurrencePlanner {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    /**
     * The deterministic execution key of one occurrence.
     *
     * <p>It binds the exact published identifier, revision and full-definition content hash to the exact frozen
     * schedule and the nominal scheduled instant, so the same occurrence of the same published version always
     * derives the same key while a superseding version or a moved schedule derives a different one.
     */
    public static String executionKey(
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            Instant nominalInstant) {
        ScheduleSupport.requireIdentifier(publishedId, "publishedId");
        if (publishedRevision < 1) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "publishedRevision");
        }
        if (publishedDefinitionHash == null || scheduleHash == null || nominalInstant == null) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "executionKey");
        }
        return CANONICAL_JSON.hash(new ExecutionKeyMaterial(
                ScheduleVersions.EXECUTION_KEY_VERSION, publishedId, publishedRevision,
                publishedDefinitionHash, scheduleHash, nominalInstant)).value();
    }

    /** Plans the occurrences one frozen published schedule has due at the request's explicit {@code asOf}. */
    public OccurrencePlan plan(OccurrencePlanningRequest request) {
        if (request == null) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "request");
        }
        CompiledSchedule schedule = request.schedule();
        List<ScheduledOccurrence> occurrences = switch (schedule.form()) {
            case IMMEDIATE -> single(request, schedule, request.publishedInstant(), null);
            case ONCE -> single(request, schedule, schedule.runAtInstant(), null);
            case DAILY -> daily(request, schedule);
        };
        return OccurrencePlan.of(
                request.publishedId(), request.publishedRevision(), request.publishedDefinitionHash(),
                schedule.scheduleHash(), request.publishedInstant(), request.asOf(), occurrences);
    }

    /** The one nominal occurrence of an {@code IMMEDIATE} or {@code ONCE} schedule. */
    private static List<ScheduledOccurrence> single(
            OccurrencePlanningRequest request, CompiledSchedule schedule, Instant nominal, LocalDate localDate) {
        if (nominal == null || nominal.isAfter(request.asOf())) {
            // The occurrence has not become due yet: a future occurrence produces no decision at all.
            return List.of();
        }
        String key = executionKey(
                request.publishedId(), request.publishedRevision(), request.publishedDefinitionHash(),
                schedule.scheduleHash(), nominal);
        if (request.wasEmitted(key)) {
            // Already-emitted keys are explicit immutable input and are never recreated.
            return List.of();
        }
        return List.of(occurrence(
                request, schedule, key, nominal, localDate, OccurrenceDecision.DUE,
                nominal.equals(request.asOf())
                        ? ScheduleVersions.REASON_DUE : ScheduleVersions.REASON_DUE_CATCH_UP));
    }

    /**
     * The bounded daily occurrences: only the most recent due un-emitted local date is due, older missed due
     * dates are explicitly skipped, and future dates are pending.
     */
    private static List<ScheduledOccurrence> daily(
            OccurrencePlanningRequest request, CompiledSchedule schedule) {
        LocalDate start = schedule.startDateValue();
        LocalDate end = schedule.endDateValue();
        String zoneId = schedule.zoneId();

        List<LocalDate> dueDates = new ArrayList<>();
        List<Instant> dueInstants = new ArrayList<>();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Instant nominal = ScheduleInstants.resolve(schedule.zone(), date, schedule.localTimeValue());
            if (nominal.isAfter(request.asOf())) {
                // A future date remains pending and creates no due work yet.
                continue;
            }
            dueDates.add(date);
            dueInstants.add(nominal);
        }

        List<Integer> unemitted = new ArrayList<>();
        for (int index = 0; index < dueDates.size(); index++) {
            String key = executionKey(
                    request.publishedId(), request.publishedRevision(), request.publishedDefinitionHash(),
                    schedule.scheduleHash(), dueInstants.get(index));
            if (!request.wasEmitted(key)) {
                unemitted.add(index);
            }
        }
        if (unemitted.isEmpty()) {
            return List.of();
        }

        int mostRecent = unemitted.get(unemitted.size() - 1);
        List<ScheduledOccurrence> occurrences = new ArrayList<>(unemitted.size());
        for (int index : unemitted) {
            Instant nominal = dueInstants.get(index);
            LocalDate date = dueDates.get(index);
            String key = executionKey(
                    request.publishedId(), request.publishedRevision(), request.publishedDefinitionHash(),
                    schedule.scheduleHash(), nominal);
            if (index == mostRecent) {
                occurrences.add(occurrence(request, schedule, key, nominal, date, OccurrenceDecision.DUE,
                        nominal.equals(request.asOf())
                                ? ScheduleVersions.REASON_DUE : ScheduleVersions.REASON_DUE_CATCH_UP));
            } else {
                occurrences.add(occurrence(request, schedule, key, nominal, date, OccurrenceDecision.SKIPPED,
                        ScheduleVersions.REASON_SKIPPED));
            }
        }
        return occurrences;
    }

    private static ScheduledOccurrence occurrence(
            OccurrencePlanningRequest request,
            CompiledSchedule schedule,
            String key,
            Instant nominal,
            LocalDate localDate,
            OccurrenceDecision decision,
            String reasonCode) {
        return ScheduledOccurrence.of(
                key, request.publishedId(), request.publishedRevision(), request.publishedDefinitionHash(),
                schedule.scheduleHash(), localDate == null ? null : localDate.toString(), schedule.zoneId(),
                nominal, nominal, decision, reasonCode);
    }

    @Override
    public String toString() {
        return "ScheduleOccurrencePlanner[versions=" + ScheduleVersions.EXECUTION_KEY_VERSION
                + ", maxOccurrences=" + ScheduleVersions.MAX_OCCURRENCES + ']';
    }

    /** Package-private execution-key hash material. */
    record ExecutionKeyMaterial(
            String executionKeyVersion,
            String publishedId,
            long publishedRevision,
            ContentHash publishedDefinitionHash,
            ContentHash scheduleHash,
            Instant nominalInstant) {
    }
}