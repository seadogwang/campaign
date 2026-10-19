package com.company.campaign.builder.segment;

/**
 * The bounded sizes of the safe model request and the raw model response.
 *
 * <p>Every bound is a compile-time constant so a request or response can never grow without limit. The
 * response byte bound reuses the accepted T10 Intent input bound: a provider response larger than the strict
 * Intent codec could ever accept is refused at the adapter boundary instead of being decoded.
 */
public final class SegmentModelLimits {

    /** The maximum length, in UTF-16 code units, of the user utterance. */
    public static final int MAX_UTTERANCE_LENGTH = 4_096;

    /** The maximum length of the locale tag. */
    public static final int MAX_LOCALE_TAG_LENGTH = 64;

    /** The maximum number of permitted definitions projected into one request. */
    public static final int MAX_PROJECTIONS = 512;

    /** The maximum number of aliases projected for one definition. */
    public static final int MAX_ALIASES_PER_DEFINITION = 64;

    /** The maximum number of allowed operators projected for one definition. */
    public static final int MAX_OPERATORS_PER_DEFINITION = 32;

    /** The maximum number of enum values projected for one definition. */
    public static final int MAX_ENUM_VALUES_PER_DEFINITION = 256;

    /** The maximum number of aliases projected for one enum value. */
    public static final int MAX_ENUM_ALIASES_PER_VALUE = 64;

    /** The maximum number of supported windows projected for one metric. */
    public static final int MAX_WINDOWS_PER_DEFINITION = 8;

    /** Maximum allowlisted row-filter dimensions projected for one Metric. */
    public static final int MAX_METRIC_FILTER_DIMENSIONS = 16;

    /** The maximum length of one projected display name, alias or disambiguation text. */
    public static final int MAX_PROJECTED_TEXT_LENGTH = 512;

    /** The maximum size, in bytes, of one raw model response. */
    public static final int MAX_MODEL_OUTPUT_BYTES = 262_144;

    private SegmentModelLimits() {
    }
}
