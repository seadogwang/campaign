package com.company.campaign.segment.resolution;

/**
 * Explicit bounds for resolver output and clarification presentation.
 *
 * <p>Clarification text and option lists are presentation data produced by the resolver, so they are bounded
 * here to keep a hostile or verbose definition from producing an unbounded safe-label list.
 */
public final class ResolutionLimits {

    private ResolutionLimits() {
    }

    /** Maximum number of clarification options offered for one clause. */
    public static final int MAX_CLARIFICATION_OPTIONS = 64;
    /** Maximum number of concept-to-definition mappings one resolution policy may carry. */
    public static final int MAX_POLICY_CONCEPT_MAPPINGS = 256;
    /** Maximum UTF-16 length of the safe question text. */
    public static final int MAX_QUESTION_TEXT_LENGTH = 512;
    /** Maximum UTF-16 length of one safe option label. */
    public static final int MAX_OPTION_LABEL_LENGTH = 256;
}