package com.company.campaign.segment.intent;

/**
 * Explicit defensive bounds for the structured Intent document.
 *
 * <p>They exist so an untrusted model response cannot allocate or recurse without limit before it is
 * rejected. The whole document is bounded by bytes, every object by its field count, every list by its
 * size, every text by its length and the logical expression by both node count and depth.
 */
public final class IntentLimits {

    private IntentLimits() {
    }

    /** Maximum accepted UTF-8 byte length of the raw JSON document. */
    public static final int MAX_INPUT_BYTES = 262_144;
    /** Maximum number of clauses in one Intent. */
    public static final int MAX_CLAUSES = 256;
    /**
     * Maximum number of first-class Existing Audience References in one Intent.
     *
     * <p>It is far below the clause bound because each reference is resolved against a host-owned directory and
     * contributes to the bounded reference graph, so a model cannot buy an unbounded number of host lookups by
     * repeating the same request.
     */
    public static final int MAX_AUDIENCE_REFERENCES = 16;
    /** Maximum UTF-16 length of a clause id. */
    public static final int MAX_CLAUSE_ID_LENGTH = 64;
    /** Maximum UTF-16 length of any bounded free-text field (raw values, span text, labels). */
    public static final int MAX_TEXT_LENGTH = 4_096;
    /** Maximum number of items in one LIST raw value. */
    public static final int MAX_LIST_ITEMS = 512;
    /** Maximum number of entries in a hint/assumption list. */
    public static final int MAX_LIST_ENTRIES = 64;
    /** Maximum number of user-supplied filters scoped inside one Metric clause. */
    public static final int MAX_SCOPE_FILTERS = 16;
    /** Maximum number of ambiguities or unsupported requirements. */
    public static final int MAX_CODE_ENTRIES = 32;
    /** Maximum number of nodes in the logical expression. */
    public static final int MAX_EXPRESSION_NODES = 1_024;
    /** Maximum nesting depth of the logical expression. */
    public static final int MAX_EXPRESSION_DEPTH = 32;
    /** Maximum children of one ALL/ANY group. */
    public static final int MAX_EXPRESSION_WIDTH = 256;
}
