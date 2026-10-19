package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;

/**
 * The accepted execution-schedule evidence of one Campaign requirement brief.
 *
 * <p>The execution mode — whether the Campaign runs once or repeatedly — is the brief's
 * {@link CampaignCadence}; this value carries the remaining, mode-dependent evidence the accepted
 * {@code schedule.basic/1.0} contract already declares under its own frozen field semantics:
 * <ul>
 *   <li>{@link #timeZone()}: the canonical region-based timezone identifier, required for either mode;</li>
 *   <li>{@link #runAt()}: the offset instant of a single execution;</li>
 *   <li>{@link #localTime()}: the local time-of-day of a repeated execution;</li>
 *   <li>{@link #startDate()}/{@link #endDate()}: the inclusive calendar window of a repeated execution.</li>
 * </ul>
 *
 * <p>Every field is a bounded accepted fact and every field is nullable: an absent field is a fact the user has
 * not supplied yet, never a default. This value deliberately does not compute an occurrence, resolve a DST
 * transition, stage {@code IMMEDIATE}, or claim any scheduling work — the accepted {@code schedule.basic} pack
 * remains the only authority on which forms and fields are valid, and this record reuses that vocabulary rather
 * than declaring a second, conflicting meaning of a schedule.
 */
public record CampaignExecutionSchedule(
        String timeZone,
        String runAt,
        String localTime,
        String startDate,
        String endDate
) {

    /** The largest accepted canonical timezone identifier, in UTF-16 code units. */
    public static final int MAX_TIMEZONE_LENGTH = ScheduleBasicVersions.MAX_ZONE_ID_LENGTH;

    /** The largest accepted offset-instant text, in UTF-16 code units. */
    public static final int MAX_INSTANT_LENGTH = ScheduleBasicVersions.MAX_INSTANT_LENGTH;

    /** The largest accepted local time-of-day text, in UTF-16 code units. */
    public static final int MAX_LOCAL_TIME_LENGTH = ScheduleBasicVersions.MAX_LOCAL_TIME_LENGTH;

    /** The largest accepted calendar-date text, in UTF-16 code units. */
    public static final int MAX_DATE_LENGTH = 16;

    /**
     * A complete one-time execution instant that is not strictly after the trusted turn time.
     */
    public static final String CODE_RUN_AT_NOT_FUTURE = "EXECUTION_RUN_AT_NOT_FUTURE";

    /**
     * A complete repeated-execution window whose inclusive end date precedes its start date, or whose supplied
     * window dates are not the accepted calendar-date shape.
     */
    public static final String CODE_WINDOW_RANGE_INVALID = "EXECUTION_WINDOW_RANGE_INVALID";

    /** A complete repeated-execution window whose inclusive end date is already before the trusted turn date. */
    public static final String CODE_WINDOW_IN_PAST = "EXECUTION_WINDOW_IN_PAST";

    public CampaignExecutionSchedule {
        timeZone = CampaignRequirementTexts.boundedText(timeZone, MAX_TIMEZONE_LENGTH, "scheduleTimeZone");
        runAt = CampaignRequirementTexts.boundedText(runAt, MAX_INSTANT_LENGTH, "scheduleRunAt");
        localTime = CampaignRequirementTexts.boundedText(localTime, MAX_LOCAL_TIME_LENGTH, "scheduleLocalTime");
        startDate = CampaignRequirementTexts.boundedText(startDate, MAX_DATE_LENGTH, "scheduleStartDate");
        endDate = CampaignRequirementTexts.boundedText(endDate, MAX_DATE_LENGTH, "scheduleEndDate");
    }

    /** The one schedule value that holds no accepted evidence at all. */
    public static CampaignExecutionSchedule empty() {
        return new CampaignExecutionSchedule(null, null, null, null, null);
    }

    /** True when no schedule evidence has been supplied, so merging it can change no accepted fact. */
    public boolean isEmpty() {
        return timeZone == null && runAt == null && localTime == null && startDate == null && endDate == null;
    }

    /**
     * This schedule with every fact the supplied evidence carries, and every other fact unchanged.
     *
     * <p>A scalar fact the supplied value omits keeps its accepted value, so a coherent explicit correction is
     * exactly an accepted value supplied for the field being corrected. A null value leaves this one unchanged.
     */
    public CampaignExecutionSchedule overlay(CampaignExecutionSchedule supplied) {
        if (supplied == null) {
            return this;
        }
        return new CampaignExecutionSchedule(
                supplied.timeZone() != null ? supplied.timeZone() : timeZone,
                supplied.runAt() != null ? supplied.runAt() : runAt,
                supplied.localTime() != null ? supplied.localTime() : localTime,
                supplied.startDate() != null ? supplied.startDate() : startDate,
                supplied.endDate() != null ? supplied.endDate() : endDate);
    }

    /** Keeps only the fields owned by the selected execution mode. */
    public CampaignExecutionSchedule forCadence(CampaignCadence cadence) {
        if (cadence == CampaignCadence.ONE_TIME) {
            return new CampaignExecutionSchedule(timeZone, runAt, null, null, null);
        }
        if (cadence == CampaignCadence.DAILY) {
            return new CampaignExecutionSchedule(timeZone, null, localTime, startDate, endDate);
        }
        return this;
    }

    /**
     * The stable outcome codes of the schedule evidence still missing for one execution mode.
     *
     * <p>It is a pure function of the accepted evidence and the mode: a null mode makes every mode-dependent
     * fact unknowable, a single execution needs its executable instant, and a repeated execution needs its local
     * time and its inclusive window. The timezone is required for either mode. Nothing here stages a default
     * form, so a Campaign whose user said "run once" but gave no time can never be treated as executable.
     */
    public java.util.List<CampaignRequirementCode> missing(CampaignCadence mode) {
        java.util.List<CampaignRequirementCode> missing = new java.util.ArrayList<>();
        if (mode == null) {
            return java.util.List.of();
        }
        if (timeZone == null) {
            missing.add(CampaignRequirementCode.EXECUTION_TIMEZONE_REQUIRED);
        }
        if (mode == CampaignCadence.ONE_TIME) {
            if (runAt == null) {
                missing.add(CampaignRequirementCode.EXECUTION_TIME_REQUIRED);
            }
        } else {
            if (localTime == null) {
                missing.add(CampaignRequirementCode.EXECUTION_TIME_REQUIRED);
            }
            if (startDate == null) {
                missing.add(CampaignRequirementCode.SCHEDULE_START_DATE_REQUIRED);
            }
            if (endDate == null) {
                missing.add(CampaignRequirementCode.SCHEDULE_END_DATE_REQUIRED);
            }
        }
        return java.util.List.copyOf(missing);
    }

    /**
     * The stable bounded outcome codes of schedule evidence that is complete for its mode but not executable
     * against the trusted turn time, or an empty list when the evidence is missing for its mode, incomplete or
     * valid.
     *
     * <p>It is a pure function of the accepted evidence, the mode and the trusted turn time: a single execution
     * whose supplied offset instant is not strictly after the trusted turn time is rejected as not future, and a
     * repeated execution whose supplied inclusive window ends before it starts is rejected as a bad range, or whose
     * complete supplied window ends before the trusted turn date is rejected as already in the past. Evidence the
     * user has not yet supplied is not reported here — that is the {@link #missing(CampaignCadence)} outcome — so a
     * partial schedule keeps {@code NEEDS_INPUT} rather than {@code BLOCKED}, and a later natural-language
     * correction that supplies a valid value clears the code without re-asking the rest. A {@code null} mode or
     * trusted time disables the check, so a caller without a trusted clock observes no invalid code.
     */
    public java.util.List<String> invalid(
            CampaignCadence mode, java.time.Instant trustedNow, java.time.ZoneId trustedZone) {
        if (mode == null || trustedNow == null || trustedZone == null) {
            return java.util.List.of();
        }
        java.util.List<String> codes = new java.util.ArrayList<>();
        if (mode == CampaignCadence.ONE_TIME) {
            if (runAt != null) {
                java.time.Instant instant = parseInstant(runAt);
                if (instant == null || !instant.isAfter(trustedNow)) {
                    codes.add(CODE_RUN_AT_NOT_FUTURE);
                }
            }
        } else if (mode == CampaignCadence.DAILY) {
            if (startDate != null && endDate != null) {
                java.time.LocalDate start = parseDate(startDate);
                java.time.LocalDate end = parseDate(endDate);
                if (start == null || end == null || end.isBefore(start)) {
                    codes.add(CODE_WINDOW_RANGE_INVALID);
                } else if (end.isBefore(trustedNow.atZone(trustedZone).toLocalDate())) {
                    codes.add(CODE_WINDOW_IN_PAST);
                }
            }
        }
        return java.util.List.copyOf(codes);
    }

    private static java.time.Instant parseInstant(String value) {
        try {
            return java.time.Instant.parse(value);
        } catch (RuntimeException failure) {
            return null;
        }
    }

    private static java.time.LocalDate parseDate(String value) {
        try {
            return java.time.LocalDate.parse(value);
        } catch (RuntimeException failure) {
            return null;
        }
    }
}
