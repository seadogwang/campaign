package com.company.campaign.lifecycle;

/**
 * Stable machine-readable error codes for the host-only lifecycle boundary.
 *
 * <p>These values are part of the wire contract: a caller, a test or a later task module may branch on
 * them, so an existing code is never renamed or reused for a different meaning. A new failure mode gets a
 * new code. Every code is also a bounded uppercase machine code, so the same value is a valid lifecycle
 * audit reason code.
 *
 * <p>None of these codes distinguishes an absent resource from one outside the caller's boundary: that
 * decision is reported with the accepted {@code RESOURCE_NOT_FOUND} contract failure instead.
 */
public final class LifecycleErrorCodes {

    /** The Apply command is structurally invalid and was not evaluated. */
    public static final String LIFECYCLE_COMMAND_INVALID = "LIFECYCLE_COMMAND_INVALID";

    /** The presented hash is not the loaded Plan's own hash, or the Plan no longer self-binds to its id. */
    public static final String LIFECYCLE_PLAN_MISMATCH = "LIFECYCLE_PLAN_MISMATCH";

    /** The loaded Plan is at or past its expiry instant. */
    public static final String LIFECYCLE_PLAN_EXPIRED = "LIFECYCLE_PLAN_EXPIRED";

    /** The loaded Plan carries a blocking validation issue and is not applicable. */
    public static final String LIFECYCLE_PLAN_INVALID = "LIFECYCLE_PLAN_INVALID";

    /** The editable Draft no longer matches the Plan's base campaign/Draft identity or revisions. */
    public static final String LIFECYCLE_DRAFT_STALE = "LIFECYCLE_DRAFT_STALE";

    /** The editable Draft identity is already bound to different content. */
    public static final String LIFECYCLE_DRAFT_CONFLICT = "LIFECYCLE_DRAFT_CONFLICT";

    /** The Plan's frozen catalog/policy/capability/contract pins no longer match the current frozen inputs. */
    public static final String LIFECYCLE_COMPATIBILITY_STALE = "LIFECYCLE_COMPATIBILITY_STALE";

    /** No trustworthy compatibility decision could be made; the operation fails closed. */
    public static final String LIFECYCLE_COMPATIBILITY_UNAVAILABLE = "LIFECYCLE_COMPATIBILITY_UNAVAILABLE";

    /** The final Manifest revalidation reported a blocking issue. */
    public static final String LIFECYCLE_VALIDATION_REJECTED = "LIFECYCLE_VALIDATION_REJECTED";

    /** The final Manifest revalidation could not produce a trustworthy result; the operation fails closed. */
    public static final String LIFECYCLE_VALIDATION_UNAVAILABLE = "LIFECYCLE_VALIDATION_UNAVAILABLE";

    /** A receipt identifier is already bound to a different immutable receipt. */
    public static final String LIFECYCLE_RECEIPT_CONFLICT = "LIFECYCLE_RECEIPT_CONFLICT";

    /** A lifecycle collaborator failed unexpectedly and the operation was rolled back. */
    public static final String LIFECYCLE_INTERNAL_ERROR = "LIFECYCLE_INTERNAL_ERROR";

    // ------------------------------------------------------------------ publish phase

    /** The loaded preparation is at or past its expiry instant. */
    public static final String LIFECYCLE_PREPARATION_EXPIRED = "LIFECYCLE_PREPARATION_EXPIRED";

    /** The preparation was already consumed by one successful Publish; it is consumed at most once. */
    public static final String LIFECYCLE_PREPARATION_CONSUMED = "LIFECYCLE_PREPARATION_CONSUMED";

    /** The preparation's frozen Draft, root revisions, DataScope or frozen inputs have drifted. */
    public static final String LIFECYCLE_PREPARATION_STALE = "LIFECYCLE_PREPARATION_STALE";

    /** The presented preparation hash is not the loaded preparation's own hash. */
    public static final String LIFECYCLE_PREPARATION_MISMATCH = "LIFECYCLE_PREPARATION_MISMATCH";

    /** The risk confirmations are not exactly the preparation's frozen required risk codes. */
    public static final String LIFECYCLE_CONFIRMATION_MISMATCH = "LIFECYCLE_CONFIRMATION_MISMATCH";

    /** A saved Draft cannot be prepared for publication because its frozen source Plan is unavailable. */
    public static final String LIFECYCLE_DRAFT_NOT_PUBLISHABLE = "LIFECYCLE_DRAFT_NOT_PUBLISHABLE";

    /** The exact production Segment activation evidence was rejected; the operation fails closed. */
    public static final String LIFECYCLE_ACTIVATION_REJECTED = "LIFECYCLE_ACTIVATION_REJECTED";

    /** No trustworthy production Segment readiness evidence could be loaded; the operation fails closed. */
    public static final String LIFECYCLE_ACTIVATION_UNAVAILABLE = "LIFECYCLE_ACTIVATION_UNAVAILABLE";

    /**
     * The exact production Existing Audience Reference activation evidence was rejected.
     *
     * <p>It is deliberately distinct from {@link #LIFECYCLE_ACTIVATION_REJECTED}: the reference extension is
     * its own Gate identity, so a reference-bearing preparation that fails its dedicated activation must not be
     * diagnosable as a failure of the base {@code segment.rule-based} guard, and vice versa.
     */
    public static final String LIFECYCLE_REFERENCE_ACTIVATION_REJECTED =
            "LIFECYCLE_REFERENCE_ACTIVATION_REJECTED";

    /** No trustworthy reference resolution or reference readiness could be loaded; the operation fails closed. */
    public static final String LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE =
            "LIFECYCLE_REFERENCE_ACTIVATION_UNAVAILABLE";

    /** An idempotency key is already bound to a different publish request. */
    public static final String LIFECYCLE_PUBLISH_CONFLICT = "LIFECYCLE_PUBLISH_CONFLICT";

    /** A Published Definition or receipt identifier is already bound to different immutable material. */
    public static final String LIFECYCLE_PUBLISH_DEFINITION_CONFLICT = "LIFECYCLE_PUBLISH_DEFINITION_CONFLICT";

    /** The presented published identity is not the loaded Published Definition's own identity. */
    public static final String LIFECYCLE_PUBLISHED_MISMATCH = "LIFECYCLE_PUBLISHED_MISMATCH";

    /** A fork could not be committed because the Campaign's editable Draft moved under it. */
    public static final String LIFECYCLE_FORK_CONFLICT = "LIFECYCLE_FORK_CONFLICT";

    // ------------------------------------------------------------------ full publication phase (T21)

    /**
     * The full publication preparation is at or past its expiry instant.
     *
     * <p>It is deliberately distinct from {@link #LIFECYCLE_PREPARATION_EXPIRED}: the full preparation is its
     * own server artifact, and a caller must be able to tell which of the two expired.
     */
    public static final String LIFECYCLE_FULL_PREPARATION_EXPIRED = "LIFECYCLE_FULL_PREPARATION_EXPIRED";

    /** The full publication preparation was already consumed by one successful full publication. */
    public static final String LIFECYCLE_FULL_PREPARATION_CONSUMED = "LIFECYCLE_FULL_PREPARATION_CONSUMED";

    /** The full publication preparation's frozen compiled evidence has drifted. */
    public static final String LIFECYCLE_FULL_PREPARATION_STALE = "LIFECYCLE_FULL_PREPARATION_STALE";

    /** The presented full preparation hash is not the loaded full preparation's own hash. */
    public static final String LIFECYCLE_FULL_PREPARATION_MISMATCH = "LIFECYCLE_FULL_PREPARATION_MISMATCH";

    /** No trustworthy source for the compiled graph artifact was available; the operation fails closed. */
    public static final String LIFECYCLE_FULL_JOURNEY_UNAVAILABLE = "LIFECYCLE_FULL_JOURNEY_UNAVAILABLE";

    /** The compiled graph artifact, its source Manifest or its version pins do not match the frozen evidence. */
    public static final String LIFECYCLE_FULL_JOURNEY_MISMATCH = "LIFECYCLE_FULL_JOURNEY_MISMATCH";

    /** No single enabled bounded time component was available; the operation fails closed. */
    public static final String LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE = "LIFECYCLE_FULL_SCHEDULE_UNAVAILABLE";

    /** The frozen bounded time artifact does not match the source component or its payload. */
    public static final String LIFECYCLE_FULL_SCHEDULE_MISMATCH = "LIFECYCLE_FULL_SCHEDULE_MISMATCH";

    /**
     * No trustworthy host runtime-availability evidence was available for the frozen versions.
     *
     * <p>It is the T21 fail-closed boundary that keeps a preview-only capability from being relabelled as
     * production-ready: an absent, unknown or incompatible availability pin refuses the operation.
     */
    public static final String LIFECYCLE_FULL_RUNTIME_UNAVAILABLE = "LIFECYCLE_FULL_RUNTIME_UNAVAILABLE";

    /** A scheduling compare-and-set lost its race: the active schedule state moved under the operation. */
    public static final String LIFECYCLE_SCHEDULE_STATE_CONFLICT = "LIFECYCLE_SCHEDULE_STATE_CONFLICT";

    /** A scheduling operation was refused because the Campaign's active schedule is paused. */
    public static final String LIFECYCLE_SCHEDULE_PAUSED = "LIFECYCLE_SCHEDULE_PAUSED";

    private LifecycleErrorCodes() {
    }
}