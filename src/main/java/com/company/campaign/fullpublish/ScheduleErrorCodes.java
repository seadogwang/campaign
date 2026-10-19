package com.company.campaign.fullpublish;

/**
 * Stable machine-readable error codes for the T21 schedule compiler and occurrence planner.
 *
 * <p>These values are part of the wire contract: a caller, a test or a later task module may branch on them,
 * so an existing code is never renamed or reused for a different meaning. A new failure mode gets a new code.
 * Every code is a bounded uppercase machine code, so the same value can also be used as an audit reason code.
 *
 * <p>No code ever echoes a rejected value: a malformed payload, an unknown form, a non-canonical zone, an
 * offset that contradicts its zone and an invalid window each fail closed with one of these bounded codes.
 */
public final class ScheduleErrorCodes {

    /** A required compile or planning input was absent or not a bounded value. */
    public static final String SCHEDULE_INPUT_INVALID = "T21_SCHEDULE_INPUT_INVALID";

    /** The requested schedule or occurrence format version is not the one supported version. */
    public static final String SCHEDULE_VERSION_UNSUPPORTED = "T21_SCHEDULE_VERSION_UNSUPPORTED";

    /** The requested compiler version is not the one supported version. */
    public static final String SCHEDULE_COMPILER_VERSION_UNSUPPORTED = "T21_SCHEDULE_COMPILER_VERSION_UNSUPPORTED";

    /** The source capability code or contract version is not the accepted {@code schedule.basic/1.0}. */
    public static final String SCHEDULE_CAPABILITY_UNSUPPORTED = "T21_SCHEDULE_CAPABILITY_UNSUPPORTED";

    /** The payload declared a field this contract version does not declare. */
    public static final String SCHEDULE_UNKNOWN_FIELD = "T21_SCHEDULE_UNKNOWN_FIELD";

    /** A declared field is absent, contradictory for the declared form, or of the wrong type or shape. */
    public static final String SCHEDULE_FIELD_INVALID = "T21_SCHEDULE_FIELD_INVALID";

    /** The declared form is not one this contract version accepts. */
    public static final String SCHEDULE_FORM_UNSUPPORTED = "T21_SCHEDULE_FORM_UNSUPPORTED";

    /** The declared timezone is not a canonical IANA region identifier. */
    public static final String SCHEDULE_ZONE_INVALID = "T21_SCHEDULE_ZONE_INVALID";

    /** The declared instant, offset or calendar window is inconsistent or exceeds the frozen bound. */
    public static final String SCHEDULE_WINDOW_INVALID = "T21_SCHEDULE_WINDOW_INVALID";

    /** The planner was given more already-emitted execution keys than the frozen bound permits. */
    public static final String SCHEDULE_EMITTED_KEYS_INVALID = "T21_SCHEDULE_EMITTED_KEYS_INVALID";

    private ScheduleErrorCodes() {
    }
}