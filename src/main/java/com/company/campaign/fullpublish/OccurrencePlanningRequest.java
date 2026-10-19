package com.company.campaign.fullpublish;

import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * The explicit, frozen inputs of one deterministic occurrence planning evaluation.
 *
 * <p>A request supplies only what the planner may read: the exact published version identity and full-definition
 * hash the occurrences belong to, the frozen compiled schedule, the publication instant, an explicit
 * {@code asOf}, and the execution keys that were already emitted. It carries no repository, clock, system
 * timezone, UUID, random source, model, network or provider handle, so the planner is a pure function of these
 * explicit inputs and the same state always plans to the same occurrences and the same keys.
 *
 * <p>Already-emitted keys are explicit immutable input: they are bounded, must be well formed and are never
 * silently truncated. A planner handed more than the frozen bound refuses rather than serving a partial answer
 * that would recreate work.
 */
public record OccurrencePlanningRequest(
        String publishedId,
        long publishedRevision,
        ContentHash publishedDefinitionHash,
        CompiledSchedule schedule,
        Instant publishedInstant,
        Instant asOf,
        List<String> alreadyEmittedExecutionKeys
) {

    public OccurrencePlanningRequest {
        ScheduleSupport.requireIdentifier(publishedId, "publishedId");
        if (publishedRevision < 1) {
            throw ScheduleSupport.invalid(ScheduleErrorCodes.SCHEDULE_INPUT_INVALID, "publishedRevision");
        }
        Objects.requireNonNull(publishedDefinitionHash, "publishedDefinitionHash");
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(publishedInstant, "publishedInstant");
        Objects.requireNonNull(asOf, "asOf");
        if (alreadyEmittedExecutionKeys == null
                || alreadyEmittedExecutionKeys.size() > ScheduleVersions.MAX_EMITTED_KEYS) {
            throw ScheduleSupport.invalid(
                    ScheduleErrorCodes.SCHEDULE_EMITTED_KEYS_INVALID, "alreadyEmittedExecutionKeys");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String key : alreadyEmittedExecutionKeys) {
            ScheduleSupport.requireIdentifier(key, "executionKey");
            if (!distinct.add(key)) {
                // A repeated key is a contradictory input, not a harmless duplicate: it would make the count of
                // already-emitted work ambiguous.
                throw ScheduleSupport.invalid(
                        ScheduleErrorCodes.SCHEDULE_EMITTED_KEYS_INVALID, "alreadyEmittedExecutionKeys");
            }
        }
        alreadyEmittedExecutionKeys = List.copyOf(distinct);
    }

    /** True when this exact execution key was already emitted. */
    public boolean wasEmitted(String executionKey) {
        return alreadyEmittedExecutionKeys.contains(executionKey);
    }

    @Override
    public String toString() {
        return "OccurrencePlanningRequest[publishedRevision=" + publishedRevision
                + ", form=" + schedule.form()
                + ", asOf=" + asOf
                + ", emitted=" + alreadyEmittedExecutionKeys.size() + ']';
    }
}