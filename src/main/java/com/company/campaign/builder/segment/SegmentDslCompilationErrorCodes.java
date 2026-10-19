package com.company.campaign.builder.segment;

/**
 * The closed set of stable failures of the deterministic resolved-Intent to DSL compiler.
 *
 * <p>Each code names one re-proof or one conversion failure. None of them carries a value, a definition id or
 * any other caller data: the code is the machine interface and the message is caller-safe.
 */
public final class SegmentDslCompilationErrorCodes {

    /** The recomputed Intent hash does not equal the one the resolution recorded. */
    public static final String INTENT_HASH_MISMATCH = "SEGMENT_DSL_COMPILE_INTENT_HASH_MISMATCH";

    /** Re-resolving the exact Intent against the exact snapshot/policy/defaults is not the supplied result. */
    public static final String RESOLUTION_STALE = "SEGMENT_DSL_COMPILE_RESOLUTION_STALE";

    /** The overall resolution is not {@code RESOLVED}. */
    public static final String RESOLUTION_NOT_RESOLVED = "SEGMENT_DSL_COMPILE_RESOLUTION_NOT_RESOLVED";

    /** A clause resolution is missing, not resolved, or names no accepted definition. */
    public static final String CLAUSE_NOT_RESOLVED = "SEGMENT_DSL_COMPILE_CLAUSE_NOT_RESOLVED";

    /** A clause appears in the logical expression more than once. */
    public static final String CLAUSE_COMPILED_TWICE = "SEGMENT_DSL_COMPILE_CLAUSE_COMPILED_TWICE";

    /** A declared clause never appears in the logical expression. */
    public static final String CLAUSE_NOT_COMPILED = "SEGMENT_DSL_COMPILE_CLAUSE_NOT_COMPILED";

    /** The accepted definition id is absent from the exact permitted snapshot. */
    public static final String DEFINITION_UNKNOWN = "SEGMENT_DSL_COMPILE_DEFINITION_UNKNOWN";

    /** The accepted definition kind is not the kind the clause requests. */
    public static final String DEFINITION_KIND_INCOMPATIBLE = "SEGMENT_DSL_COMPILE_DEFINITION_KIND_INCOMPATIBLE";

    /** The accepted definition does not belong to the Intent subject entity. */
    public static final String SUBJECT_NOT_REPRESENTABLE = "SEGMENT_DSL_COMPILE_SUBJECT_NOT_REPRESENTABLE";

    /** A relation clause or relation-target attribute cannot be represented by the current Intent. */
    public static final String RELATION_NESTING_NOT_REPRESENTABLE =
            "SEGMENT_DSL_COMPILE_RELATION_NESTING_NOT_REPRESENTABLE";

    /** A non-null operator is required but absent. */
    public static final String OPERATOR_MISSING = "SEGMENT_DSL_COMPILE_OPERATOR_MISSING";

    /** The operator is not declared on the accepted definition. */
    public static final String OPERATOR_NOT_ALLOWED = "SEGMENT_DSL_COMPILE_OPERATOR_NOT_ALLOWED";

    /** A value is required but absent, or must be absent but present. */
    public static final String VALUE_SHAPE_INCOMPATIBLE = "SEGMENT_DSL_COMPILE_VALUE_SHAPE_INCOMPATIBLE";

    /** The raw value kind does not match the accepted definition's data type. */
    public static final String VALUE_TYPE_INCOMPATIBLE = "SEGMENT_DSL_COMPILE_VALUE_TYPE_INCOMPATIBLE";

    /** A DATE/TIMESTAMP range is not the required lower-inclusive, upper-exclusive interval. */
    public static final String TEMPORAL_RANGE_NOT_HALF_OPEN =
            "SEGMENT_DSL_COMPILE_TEMPORAL_RANGE_NOT_HALF_OPEN";

    /**
     * A raw value or window is outside the representable T05 typed-value/typed-window domain: a canonical
     * Intent integer outside the T05 64-bit range, a decimal/money lexeme beyond the T05 precision, scale or
     * length bound, a range rejected by the typed-value constructor, or a window the typed-window constructor
     * refuses. The raw value is never echoed.
     */
    public static final String VALUE_NOT_REPRESENTABLE = "SEGMENT_DSL_COMPILE_VALUE_NOT_REPRESENTABLE";

    /**
     * A bounded internal lowering step outside value conversion failed: a derived node identity, the published
     * document or its Metadata binding was rejected by the accepted T05 boundary. The rejected input is never
     * echoed.
     */
    public static final String INTERNAL_LOWERING_FAILED = "SEGMENT_DSL_COMPILE_INTERNAL_LOWERING_FAILED";

    /** An enum raw label matches no permitted enum code. */
    public static final String ENUM_UNRESOLVED = "SEGMENT_DSL_COMPILE_ENUM_UNRESOLVED";

    /** An enum raw label matches more than one permitted enum code. */
    public static final String ENUM_AMBIGUOUS = "SEGMENT_DSL_COMPILE_ENUM_AMBIGUOUS";

    /** A monetary value has neither an explicit currency nor an exact registered default. */
    public static final String CURRENCY_MISSING = "SEGMENT_DSL_COMPILE_CURRENCY_MISSING";

    /** A registered-currency default could not be re-proved against the exact registry. */
    public static final String CURRENCY_DEFAULT_UNRESOLVED = "SEGMENT_DSL_COMPILE_CURRENCY_DEFAULT_UNRESOLVED";

    /** A metric clause carries no explicit window. */
    public static final String WINDOW_MISSING = "SEGMENT_DSL_COMPILE_WINDOW_MISSING";

    /** The window kind is not supported by the accepted metric definition. */
    public static final String WINDOW_UNSUPPORTED = "SEGMENT_DSL_COMPILE_WINDOW_UNSUPPORTED";

    private SegmentDslCompilationErrorCodes() {
    }
}
