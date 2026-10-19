package com.company.campaign.fullpublish;

import com.company.campaign.builder.capability.schedule.ScheduleBasicVersions;

/**
 * Every version discriminator, closed code and frozen bound owned by the T21 schedule compiler and occurrence
 * planner.
 *
 * <p>Each value here is part of a compiled schedule's canonical hash, of a planned occurrence's hash or of an
 * execution key, so changing any of them is a new version rather than a silent reinterpretation of an
 * already-frozen schedule or an already-emitted occurrence key. The bounds are the frozen policy bounds of
 * this contract version: the inclusive calendar-day maximum is inherited verbatim from the accepted T19
 * {@code schedule.basic/1.0} contract rather than restated, so the two can never drift apart.
 *
 * <p>Nothing here claims a production Runtime: T21 compiles a schedule and plans bounded occurrences, and the
 * durable work it emits is delivered by the still-absent T22 Runtime.
 */
public final class ScheduleVersions {

    /** The compiled schedule document format version. */
    public static final String SCHEDULE_FORMAT_VERSION = "campaign-schedule/1.0";

    /** The T21 schedule compiler version, which is part of every compiled schedule's hash. */
    public static final String SCHEDULE_COMPILER_VERSION = "schedule-compiler/1.0";

    /** The exact runtime schema version a compiled schedule is bound to, inherited from T19. */
    public static final String RUNTIME_SCHEMA_VERSION = ScheduleBasicVersions.RUNTIME_SCHEMA_VERSION;

    /** The planned occurrence document format version. */
    public static final String OCCURRENCE_FORMAT_VERSION = "campaign-schedule-occurrence/1.0";

    /** The execution-key derivation version, which is part of every derived execution key. */
    public static final String EXECUTION_KEY_VERSION = "schedule-execution-key/1.0";

    /** The source capability code a compiled schedule must declare. */
    public static final String CAPABILITY_CODE = ScheduleBasicVersions.CAPABILITY_CODE;

    /** The source contract version a compiled schedule must declare. */
    public static final String CONTRACT_VERSION = ScheduleBasicVersions.CONTRACT_VERSION;

    /**
     * The frozen inclusive calendar-day maximum of one {@code DAILY} window.
     *
     * <p>It is the accepted T19 bound itself, so a window this compiler accepts is exactly a window the frozen
     * {@code schedule.basic/1.0} contract accepts.
     */
    public static final int MAX_CALENDAR_DAYS = (int) ScheduleBasicVersions.MAX_CALENDAR_DAYS;

    /** The largest number of planned occurrences one planner evaluation may emit. */
    public static final int MAX_OCCURRENCES = MAX_CALENDAR_DAYS;

    /**
     * The largest number of already-emitted execution keys one planner evaluation may be given.
     *
     * <p>Already-emitted keys are explicit immutable input and are never silently truncated: a caller holding
     * more than this bounded amount is refused rather than served a partial answer.
     */
    public static final int MAX_EMITTED_KEYS = 4 * MAX_CALENDAR_DAYS;

    /** The bounded reason code of an occurrence that became due at its nominal instant. */
    public static final String REASON_DUE = "SCHEDULE_DUE";

    /** The bounded reason code of an occurrence that became due at its nominal instant but is caught up. */
    public static final String REASON_DUE_CATCH_UP = "SCHEDULE_DUE_CATCH_UP";

    /** The bounded reason code of a DAILY occurrence skipped because a more recent date became due. */
    public static final String REASON_SKIPPED = "SCHEDULE_SKIPPED";

    private ScheduleVersions() {
    }
}