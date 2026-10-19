package com.company.campaign.evaluation.segment.contract;

import java.util.List;

/**
 * The exact fifteen Golden Corpus categories of Segment specification section 20.
 *
 * <p>Categories are identified by stable machine codes rather than free text, because a Gate compares
 * coverage per category and a renamed category would silently look like a missing one. The enum name is
 * the wire value; the declaration order is the specification order and {@link #specNumber()} carries the
 * specification's numbering for traceability into the corpus review records.
 *
 * <p>Each category is expected to hold at least fifteen independently reviewed cases, so the full corpus
 * is at least 225 cases.
 */
public enum SegmentCategory {

    /** 1. Attribute equality, sets, ranges and strings. */
    ATTRIBUTE_EQUALITY_SETS_RANGES_STRINGS(1, "Attribute equality, sets, ranges and strings"),

    /** 2. AND, OR, nested groups and ambiguous precedence. */
    BOOLEAN_AND_OR_NESTED_GROUPS(2, "AND, OR, nested groups and ambiguous precedence"),

    /** 3. NOT, NOT IN, NULL and negative ranges. */
    NOT_NOT_IN_NULL_NEGATIVE_RANGES(3, "NOT, NOT IN, NULL and negative ranges"),

    /** 4. Boundary operators and BETWEEN inclusivity. */
    BOUNDARY_OPERATORS_BETWEEN_INCLUSIVITY(4, "Boundary operators and BETWEEN inclusivity"),

    /** 5. ROLLING, LIFETIME, FIXED, timezone and boundaries. */
    TIME_WINDOWS_ROLLING_LIFETIME_FIXED(5, "ROLLING, LIFETIME, FIXED, timezone and boundaries"),

    /** 6. COUNT, SUM, AVG, MIN, MAX and zero-row behavior. */
    AGGREGATES_AND_ZERO_ROW_BEHAVIOUR(6, "COUNT, SUM, AVG, MIN, MAX and zero-row behavior"),

    /** 7. Currency, missing currency and mixed-currency rejection. */
    CURRENCY_MISSING_AND_MIXED_REJECTION(7, "currency, missing currency and mixed-currency rejection"),

    /** 8. Multiple conditions inside one EXISTS. */
    MULTIPLE_CONDITIONS_IN_ONE_EXISTS(8, "multiple conditions inside one EXISTS"),

    /** 9. Multiple EXISTS nodes matching different rows. */
    MULTIPLE_EXISTS_NODES_DIFFERENT_ROWS(9, "multiple EXISTS nodes matching different rows"),

    /** 10. Single-event versus aggregate metric ambiguity. */
    SINGLE_EVENT_VERSUS_AGGREGATE_AMBIGUITY(10, "single-event versus aggregate metric ambiguity"),

    /** 11. Unsupported concepts and raw SQL requests. */
    UNSUPPORTED_CONCEPTS_AND_RAW_SQL(11, "unsupported concepts and raw SQL requests"),

    /** 12. Multiple candidates and required clarification. */
    MULTIPLE_CANDIDATES_AND_REQUIRED_CLARIFICATION(12, "multiple candidates and required clarification"),

    /** 13. Add, change, delete and move refinements. */
    REFINEMENT_ADD_CHANGE_DELETE_MOVE(13, "add/change/delete/move refinements"),

    /** 14. Chinese, English, mixed language, aliases and recoverable typos. */
    MULTILINGUAL_ALIASES_AND_RECOVERABLE_TYPOS(14, "Chinese, English, mixed language, aliases and recoverable typos"),

    /** 15. Prompt injection, unauthorized fields and cross-tenant requests. */
    PROMPT_INJECTION_UNAUTHORIZED_AND_CROSS_TENANT(15, "prompt injection, unauthorized fields and cross-tenant requests");

    private final int specNumber;
    private final String summary;

    SegmentCategory(int specNumber, String summary) {
        this.specNumber = specNumber;
        this.summary = summary;
    }

    /** One-based number of this category in Segment specification section 20. */
    public int specNumber() {
        return specNumber;
    }

    /** The specification's own wording for this category, for review records and reporting. */
    public String summary() {
        return summary;
    }

    /** Every category, in specification order. */
    public static List<SegmentCategory> ordered() {
        return List.of(values());
    }
}