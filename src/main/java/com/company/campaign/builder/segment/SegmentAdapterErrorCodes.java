package com.company.campaign.builder.segment;

/**
 * The closed set of stable model-adapter failure codes.
 *
 * <p>Every model-boundary failure maps to exactly one code. The codes are the only thing a caller sees: no
 * provider payload, provider message, endpoint, credential or model name is ever echoed into a code, a message
 * or an exception detail.
 */
public final class SegmentAdapterErrorCodes {

    /** The injected model port threw, timed out or otherwise failed before returning bytes. */
    public static final String PROVIDER_FAILED = "SEGMENT_MODEL_PROVIDER_FAILED";

    /** The model port returned null. */
    public static final String OUTPUT_MISSING = "SEGMENT_MODEL_OUTPUT_MISSING";

    /** The model port returned zero bytes or only whitespace. */
    public static final String OUTPUT_EMPTY = "SEGMENT_MODEL_OUTPUT_EMPTY";

    /** The response exceeds {@link SegmentModelLimits#MAX_MODEL_OUTPUT_BYTES}. */
    public static final String OUTPUT_OVERSIZED = "SEGMENT_MODEL_OUTPUT_OVERSIZED";

    /** The response bytes are not well-formed UTF-8. */
    public static final String OUTPUT_NOT_UTF8 = "SEGMENT_MODEL_OUTPUT_NOT_UTF8";

    /** The response is wrapped in a Markdown code fence instead of being one raw JSON object. */
    public static final String OUTPUT_FENCED = "SEGMENT_MODEL_OUTPUT_FENCED";

    /** The response carries prose before the JSON object instead of being one raw JSON object. */
    public static final String OUTPUT_PROSE = "SEGMENT_MODEL_OUTPUT_PROSE";

    /** The response is not a well-formed Intent document. */
    public static final String OUTPUT_MALFORMED = "SEGMENT_MODEL_OUTPUT_MALFORMED";

    /** The response carries a field outside the closed Intent schema. */
    public static final String OUTPUT_UNKNOWN_FIELD = "SEGMENT_MODEL_OUTPUT_UNKNOWN_FIELD";

    /** The response carries a reserved trusted/host field that the Intent schema never admits. */
    public static final String OUTPUT_RESERVED_FIELD = "SEGMENT_MODEL_OUTPUT_RESERVED_FIELD";

    /** The response is well-formed JSON but violates the Intent contract in some other way. */
    public static final String OUTPUT_INVALID = "SEGMENT_MODEL_OUTPUT_INVALID";

    /** The response declares a window shape, bound or unit outside the closed Intent window contract. */
    public static final String OUTPUT_WINDOW_INVALID = "SEGMENT_MODEL_OUTPUT_WINDOW_INVALID";

    /**
     * A value in the response has the wrong JSON shape for its declared kind — for example, an object
     * member that should be a nested object is a plain string, or a required discriminator is missing.
     * Covers {@code SHAPE_INVALID}, {@code RELATION_SCOPE_INVALID}, {@code SCOPE_FILTER_INVALID},
     * {@code VALUE_INVALID} and {@code CURRENCY_INVALID}.
     */
    public static final String OUTPUT_SHAPE_INVALID = "SEGMENT_MODEL_OUTPUT_SHAPE_INVALID";

    /**
     * The response carries a source span that cannot be grounded in the utterance: the text does not
     * match, the span is empty, out of range or surrogate-splitting, or the provenance object is
     * structurally invalid.
     */
    public static final String OUTPUT_PROVENANCE_INVALID = "SEGMENT_MODEL_OUTPUT_PROVENANCE_INVALID";

    /** The host-supplied request inputs are missing or exceed the bounded request limits. */
    public static final String REQUEST_INVALID = "SEGMENT_MODEL_REQUEST_INVALID";

    private SegmentAdapterErrorCodes() {
    }
}
