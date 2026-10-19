package com.company.campaign.preview;

/**
 * One structural-AST-path condition statistic of a Preview execution.
 *
 * <p>The path is a server-produced structural path such as {@code r.0} or {@code r.1.exists}; it is not a
 * Metadata label, a definition identifier or an operand. Counts are non-negative three-valued-logic counts.
 *
 * <p>The path is validated against the exact accepted T08/T09 traversal grammar at construction: the root
 * {@code r}, followed only by bounded dot-separated group indices, {@code not} or {@code exists} steps. A
 * member identifier, a Metadata identifier, SQL or comment text, a secret-like value, whitespace, signs,
 * leading-zero ambiguous indices, identifiers, slashes, brackets, overlong paths and over-deep paths are all
 * refused with the one stable {@link PreviewErrorCodes#EXECUTION_RESULT_INCOHERENT} code, so a faulty backend
 * can never make a hostile value response-visible while still producing a self-consistent result hash.
 */
public record PreviewConditionStatistic(String path, int trueCount, int falseCount, int unknownCount) {

    /** The one accepted structural root a condition path starts from. */
    private static final String ROOT = "r";

    private static final String NEGATION_STEP = "not";
    private static final String EXISTS_STEP = "exists";

    public PreviewConditionStatistic {
        requireValidStructuralPath(path);
        if (trueCount < 0 || falseCount < 0 || unknownCount < 0) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
        }
    }

    /**
     * Proves one value is exactly an accepted structural path, or fails with the one bounded code.
     *
     * <p>It is package-visible so the application service can re-prove a path carried by a backend result
     * that may have bypassed this record's constructor, so a malformed result is refused before projection.
     */
    static void requireValidStructuralPath(String path) {
        if (path == null || path.isEmpty() || path.length() > PreviewLimits.MAX_CONDITION_PATH_LENGTH) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
        }
        if (ROOT.equals(path)) {
            return;
        }
        if (path.length() < ROOT.length() + 2
                || path.charAt(0) != ROOT.charAt(0)
                || path.charAt(1) != '.') {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
        }
        int index = 1;
        int steps = 0;
        while (index < path.length()) {
            if (path.charAt(index) != '.') {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
            }
            index++;
            if (++steps > PreviewLimits.MAX_CONDITION_PATH_STEPS) {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
            }
            int start = index;
            while (index < path.length() && path.charAt(index) != '.') {
                index++;
            }
            requireValidStep(path, start, index);
        }
    }

    /** One step is exactly a canonical group index, {@code not} or {@code exists}; nothing else. */
    private static void requireValidStep(String path, int start, int end) {
        int length = end - start;
        if (length == NEGATION_STEP.length() && path.regionMatches(start, NEGATION_STEP, 0, length)) {
            return;
        }
        if (length == EXISTS_STEP.length() && path.regionMatches(start, EXISTS_STEP, 0, length)) {
            return;
        }
        if (length == 0 || length > 4 || (path.charAt(start) == '0' && length != 1)) {
            // No leading-zero form is ever emitted by the accepted traversal, so it is not accepted here.
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
        }
        int value = 0;
        for (int position = start; position < end; position++) {
            char character = path.charAt(position);
            if (character < '0' || character > '9') {
                throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
            }
            value = value * 10 + (character - '0');
        }
        if (value > PreviewLimits.MAX_CONDITION_PATH_INDEX) {
            throw PreviewException.of(PreviewErrorCodes.EXECUTION_RESULT_INCOHERENT, "conditionStatistic");
        }
    }
}