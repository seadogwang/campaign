package com.company.campaign.segment.reference;

/**
 * Stable machine-readable failure codes owned by the Existing Audience Reference core.
 *
 * <p>They are part of the outward contract: the validator, the API error envelope and the later planning,
 * preview and lifecycle tasks branch on them, so an existing code is never renamed or reused for another
 * meaning. A new failure mode gets a new code.
 *
 * <p>Two deliberate non-disclosure properties hold here. {@link #VERSION_NOT_FOUND} is returned for a version
 * that does not exist, for one another tenant owns and for one the catalog cannot see at all — a caller can
 * never tell "absent" from "someone else's" by the code, the message or the details. And
 * {@link #RESOURCE_NOT_ACCESSIBLE} is returned for every authorization failure without stating whether the
 * version exists or which permission was missing.
 */
public final class ReferenceErrorCodes {

    /** A validated document contains a reference but no trusted reference-resolution context was supplied. */
    public static final String CONTEXT_MISSING = "SEGMENT_REFERENCE_CONTEXT_MISSING";

    /**
     * The exact pinned version is not visible to the caller: it does not exist, it belongs to another tenant,
     * or the host catalog cannot see it. All three share this one non-enumerating result.
     */
    public static final String VERSION_NOT_FOUND = "SEGMENT_REFERENCE_VERSION_NOT_FOUND";

    /** The pinned version ID exists with different content than the pin's hash; bytes changed under one ID. */
    public static final String VERSION_CONFLICT = "SEGMENT_REFERENCE_VERSION_CONFLICT";

    /** The exact version is deleted, no longer available, or was unavailable when re-checked. */
    public static final String VERSION_UNAVAILABLE = "SEGMENT_REFERENCE_VERSION_UNAVAILABLE";

    /** The host program-relation policy does not allow the caller's program to reach the referenced program. */
    public static final String PROGRAM_NOT_ALLOWED = "SEGMENT_REFERENCE_PROGRAM_NOT_ALLOWED";

    /** The trusted authorization boundary did not allow access to the referenced segment. */
    public static final String RESOURCE_NOT_ACCESSIBLE = "SEGMENT_REFERENCE_RESOURCE_NOT_ACCESSIBLE";

    /** The trusted DataScope intersection is empty, so no member could ever be in scope. */
    public static final String DATASCOPE_INTERSECTION_EMPTY = "SEGMENT_REFERENCE_DATASCOPE_INTERSECTION_EMPTY";

    /** A trusted DataScope intersection could not be computed, so the reference fails closed. */
    public static final String DATASCOPE_UNAVAILABLE = "SEGMENT_REFERENCE_DATASCOPE_UNAVAILABLE";

    /** The referenced version's root subject does not match the scope in which it is referenced. */
    public static final String SUBJECT_MISMATCH = "SEGMENT_REFERENCE_SUBJECT_MISMATCH";

    /** The reference graph is cyclic, including a candidate edit whose graph reaches its own root version. */
    public static final String CYCLE_DETECTED = "SEGMENT_REFERENCE_CYCLE_DETECTED";

    /** The transitive reference depth exceeds the configured maximum. */
    public static final String DEPTH_EXCEEDED = "SEGMENT_REFERENCE_DEPTH_EXCEEDED";

    /** The number of distinct referenced versions exceeds the configured maximum. */
    public static final String DISTINCT_VERSIONS_EXCEEDED = "SEGMENT_REFERENCE_DISTINCT_VERSIONS_EXCEEDED";

    /** The expanded complexity contributed by the referenced versions exceeds the configured maximum. */
    public static final String EXPANDED_COMPLEXITY_EXCEEDED = "SEGMENT_REFERENCE_EXPANDED_COMPLEXITY_EXCEEDED";

    /** A reference policy value is non-positive or exceeds the absolute supported bound. */
    public static final String POLICY_INVALID = "SEGMENT_REFERENCE_POLICY_INVALID";

    /** A trusted port supplied malformed material, or a caller supplied an incomplete required input. */
    public static final String INPUT_INVALID = "SEGMENT_REFERENCE_INPUT_INVALID";

    /** A hold was taken on, or a deletion attempted against, a version this store does not hold. */
    public static final String RETENTION_TARGET_UNKNOWN = "SEGMENT_REFERENCE_RETENTION_TARGET_UNKNOWN";

    private ReferenceErrorCodes() {
    }
}