package com.company.campaign.fullpublish;

/**
 * Stable machine-readable error codes owned by the T21 full-publication and durable scheduling layer.
 *
 * <p>These values are part of the wire contract: a caller, a test or a later task module may branch on them,
 * so an existing code is never renamed or reused for a different meaning. A new failure mode gets a new code.
 * Every code is a bounded uppercase machine code, so the same value can also be used as an audit reason code.
 *
 * <p>None of these codes distinguishes an absent resource from one outside the caller's boundary: that
 * decision is reported with the accepted {@code RESOURCE_NOT_FOUND} contract failure instead.
 */
public final class FullPublishErrorCodes {

    /** A full publication artifact would not be a bounded, self-binding, hash-bound record. */
    public static final String FULL_PREPARATION_INVALID = "FULL_PREPARATION_INVALID";

    /** A full publication definition would not be a bounded, self-binding, hash-bound record. */
    public static final String FULL_DEFINITION_INVALID = "FULL_DEFINITION_INVALID";

    /** A full publication receipt would not be a bounded, self-binding, hash-bound record. */
    public static final String FULL_RECEIPT_INVALID = "FULL_RECEIPT_INVALID";

    /** The full publication command is structurally invalid and was not evaluated. */
    public static final String FULL_COMMAND_INVALID = "FULL_COMMAND_INVALID";

    /** No trustworthy host runtime-availability evidence exists for the frozen runtime schema version. */
    public static final String FULL_RUNTIME_UNAVAILABLE = "FULL_RUNTIME_UNAVAILABLE";

    /** No single enabled {@code schedule.basic/1.0} component exists in the frozen Manifest. */
    public static final String FULL_SCHEDULE_UNAVAILABLE = "FULL_SCHEDULE_UNAVAILABLE";

    /** The frozen Schedule does not match the source component, payload or Journey it descends from. */
    public static final String FULL_SCHEDULE_MISMATCH = "FULL_SCHEDULE_MISMATCH";

    /** No trustworthy Journey source exists, or the Journey could not be compiled from it. */
    public static final String FULL_JOURNEY_UNAVAILABLE = "FULL_JOURNEY_UNAVAILABLE";

    /** The Journey, its source Manifest hash or its compiler/runtime versions do not match the frozen facts. */
    public static final String FULL_JOURNEY_MISMATCH = "FULL_JOURNEY_MISMATCH";

    /** A scheduling-registration outbox event would not be a bounded, hash-bound, side-effect-free record. */
    public static final String FULL_OUTBOX_INVALID = "FULL_OUTBOX_INVALID";

    /** An active schedule projection would not be a bounded, self-binding, compare-and-set record. */
    public static final String SCHEDULE_STATE_INVALID = "SCHEDULE_STATE_INVALID";

    /** A committed occurrence record would not be a bounded, execution-key-bound record. */
    public static final String OCCURRENCE_RECORD_INVALID = "OCCURRENCE_RECORD_INVALID";

    private FullPublishErrorCodes() {
    }
}