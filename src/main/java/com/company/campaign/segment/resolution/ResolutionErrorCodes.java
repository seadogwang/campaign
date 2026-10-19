package com.company.campaign.segment.resolution;

/**
 * Stable machine codes owned by the deterministic resolver and its clarification boundary.
 *
 * <p>Constant names are uppercase snake case; wire values carry the {@code SEGMENT_RESOLUTION_} prefix.
 * Codes are never renamed or reused. No message or detail built from them echoes the utterance, span text,
 * candidate labels, Metadata content or a trusted identifier, and a denied definition is never disclosed.
 */
public final class ResolutionErrorCodes {

    private ResolutionErrorCodes() {
    }

    /** A required resolver input is missing. */
    public static final String INPUT_MISSING = "SEGMENT_RESOLUTION_INPUT_MISSING";
    /** The permitted Metadata snapshot is missing. */
    public static final String SNAPSHOT_MISSING = "SEGMENT_RESOLUTION_SNAPSHOT_MISSING";
    /** The resolution policy is missing or malformed. */
    public static final String POLICY_INVALID = "SEGMENT_RESOLUTION_POLICY_INVALID";
    /** The permitted snapshot owner does not match the policy expectation. */
    public static final String SNAPSHOT_OWNER_MISMATCH = "SEGMENT_RESOLUTION_SNAPSHOT_OWNER_MISMATCH";
    /** The clarification resolution id is malformed. */
    public static final String RESOLUTION_ID_INVALID = "SEGMENT_RESOLUTION_ID_INVALID";
    /** A clarification choice no longer matches the current exact inputs. */
    public static final String STALE_CLARIFICATION = "SEGMENT_RESOLUTION_STALE_CLARIFICATION";
    /** A clarification choice is missing a required pin or option. */
    public static final String CHOICE_INVALID = "SEGMENT_RESOLUTION_CHOICE_INVALID";
    /** The selected option is not compatible with the clause any more. */
    public static final String OPTION_INCOMPATIBLE = "SEGMENT_RESOLUTION_OPTION_INCOMPATIBLE";
    /** The resolver detected an internally inconsistent state. */
    public static final String INTERNAL_INCONSISTENT = "SEGMENT_RESOLUTION_INTERNAL_INCONSISTENT";
    /**
     * The Intent asks for an Existing Audience Reference but no host directory context was supplied.
     *
     * <p>Resolution fails closed rather than degrading to "no directory, so the first candidate is fine": the
     * exact immutable version can only come from the host-owned directory, and inventing one would be exactly
     * the unchecked version the contract forbids.
     */
    public static final String REFERENCE_DIRECTORY_MISSING = "SEGMENT_RESOLUTION_REFERENCE_DIRECTORY_MISSING";
    /** The host directory returned material this contract cannot accept, such as an over-bounded result. */
    public static final String REFERENCE_DIRECTORY_INVALID = "SEGMENT_RESOLUTION_REFERENCE_DIRECTORY_INVALID";
}