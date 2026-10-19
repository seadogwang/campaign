package com.company.campaign.segment.canonical;

/**
 * Stable machine-readable failure codes owned by the canonical semantic boundary.
 *
 * <p>A public canonical artifact — a canonical value, node, window, root or validation result — either
 * represents exactly the normalized semantic state its name promises or it does not exist. Every rejection
 * of a forged or non-canonical state carries one of these codes, so a caller can branch on it without
 * parsing prose. Codes are part of the wire contract and are never renamed or reused. No message or detail
 * ever echoes a payload value, an identifier, an owner field or a thrown cause.
 */
public final class CanonicalErrorCodes {

    /** A canonical root does not carry the one supported canonicalizer version. */
    public static final String CANONICALIZER_VERSION_UNSUPPORTED =
            "SEGMENT_CANONICAL_VERSION_UNSUPPORTED";

    /** A canonical root does not carry the one supported Segment DSL schema version. */
    public static final String CANONICAL_SCHEMA_UNSUPPORTED =
            "SEGMENT_CANONICAL_SCHEMA_UNSUPPORTED";

    /** A canonical scalar is not in the exact normalized form its kind promises. */
    public static final String CANONICAL_VALUE_FORM_INVALID =
            "SEGMENT_CANONICAL_VALUE_FORM_INVALID";

    /** A canonical LIST is not the sorted, duplicate-free set-like form the canonicalizer emits. */
    public static final String CANONICAL_LIST_NOT_CANONICAL =
            "SEGMENT_CANONICAL_LIST_NOT_CANONICAL";

    /** A commutative ALL/ANY group does not use the canonical child order or repeats a child. */
    public static final String CANONICAL_GROUP_NOT_CANONICAL =
            "SEGMENT_CANONICAL_GROUP_NOT_CANONICAL";

    /** A canonical RANGE does not pair compatible bound kinds. */
    public static final String CANONICAL_RANGE_INCOMPATIBLE =
            "SEGMENT_CANONICAL_RANGE_INCOMPATIBLE";

    /** A canonical predicate pairs an operator with a value shape the canonicalizer never emits. */
    public static final String CANONICAL_OPERATOR_VALUE_SHAPE_INVALID =
            "SEGMENT_CANONICAL_OPERATOR_VALUE_SHAPE_INVALID";

    /** A canonical window is not a representable normalized window. */
    public static final String CANONICAL_WINDOW_INVALID =
            "SEGMENT_CANONICAL_WINDOW_INVALID";

    /** A canonical definition pins a combination the owning Metadata contract forbids. */
    public static final String CANONICAL_DEFINITION_INVALID =
            "SEGMENT_CANONICAL_DEFINITION_INVALID";

    /** A canonical relation reference pins an incomplete or invalid declaration. */
    public static final String CANONICAL_RELATION_INVALID =
            "SEGMENT_CANONICAL_RELATION_INVALID";

    /** A canonical EXISTS boundary does not keep the scope the canonicalizer always emits. */
    public static final String CANONICAL_SCOPE_INVALID =
            "SEGMENT_CANONICAL_SCOPE_INVALID";

    /** A canonical reference identifier is missing or outside the bounded reference grammar. */
    public static final String CANONICAL_REFERENCE_ID_INVALID =
            "SEGMENT_CANONICAL_REFERENCE_ID_INVALID";

    /**
     * A public canonical boundary received a null required input, list element or child.
     *
     * <p>Null is a missing input rather than a malformed one, so it gets its own stable code instead of a raw
     * {@link NullPointerException}. The code, message and {@code invariant} detail never echo the argument.
     */
    public static final String CANONICAL_INPUT_MISSING =
            "SEGMENT_CANONICAL_INPUT_MISSING";

    /** A validation result disagrees with its own canonical root, version pins or issue list. */
    public static final String CANONICAL_RESULT_INCONSISTENT =
            "SEGMENT_CANONICAL_RESULT_INCONSISTENT";

    private CanonicalErrorCodes() {
    }
}