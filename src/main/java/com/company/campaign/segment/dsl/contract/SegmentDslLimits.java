package com.company.campaign.segment.dsl.contract;

/**
 * Explicit defensive representation bounds for the editable Segment DSL document.
 *
 * <p>These bounds exist so a single field, value or nested document can never be individually unbounded and
 * a parser cannot be driven into excessive allocation or stack exhaustion. They are deliberately generous
 * for real authoring and much smaller than any tree-wide complexity limit, which remains a T07 policy
 * input (maximum nodes, OR width, relation nesting, preview rows, execution cost).
 */
public final class SegmentDslLimits {

    /** Maximum accepted UTF-8 input size for a serialized document, before any tree is built. */
    public static final int MAX_INPUT_BYTES = 262_144;

    /** Maximum predicate nesting depth, counted from the root predicate. */
    public static final int MAX_PREDICATE_DEPTH = 32;

    /** Maximum length of a STRING typed value, in UTF-16 code units. Empty strings remain valid. */
    public static final int MAX_STRING_LENGTH = 4096;

    /** Maximum length of a DECIMAL/MONEY plain-decimal text, including sign and decimal point. */
    public static final int MAX_DECIMAL_TEXT_LENGTH = 64;

    /** Maximum number of fractional digits preserved in an exact DATETIME/FIXED boundary. */
    public static final int MAX_DATETIME_FRACTIONAL_DIGITS = 9;

    /**
     * Maximum length of an exact offset date-time: {@code YYYY-MM-DDTHH:mm:ss}, a full nine-digit fraction and
     * a six-character numeric offset.
     */
    public static final int MAX_DATETIME_TEXT_LENGTH = 35;

    /** Maximum number of fractional digits preserved in an editable DECIMAL/MONEY text. */
    public static final int MAX_DECIMAL_SCALE = 18;

    /** Maximum number of significant digits in an editable DECIMAL/MONEY text. */
    public static final int MAX_DECIMAL_PRECISION = 38;

    /** Maximum number of items in one LIST typed value. */
    public static final int MAX_LIST_ITEMS = 512;

    /** Maximum row-scope filters on one Metric aggregate. */
    public static final int MAX_METRIC_SCOPE_FILTERS = 16;

    /** Maximum arithmetic-expression depth, counted from the expression root. */
    public static final int MAX_METRIC_EXPRESSION_DEPTH = 8;

    /** Maximum arithmetic nodes and Metric references in one expression. */
    public static final int MAX_METRIC_EXPRESSION_NODES = 32;

    /** Maximum ROLLING amount in its declared unit. */
    public static final int MAX_WINDOW_AMOUNT = 100_000;

    private SegmentDslLimits() {
    }
}
