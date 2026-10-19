package com.company.campaign.builder.compiler;

import java.util.List;

/**
 * One bounded, safe condition statistic carried by a {@link PlanPreviewCard}.
 *
 * <p>The path is the server-produced structural AST path the accepted T08/T09 traversal emits — the root
 * {@code r} followed only by bounded dot-separated group indices, {@code not} or {@code exists} steps. It is
 * never a Metadata label, a definition identifier, an operand, SQL or comment text, and it never carries
 * whitespace, signs, leading-zero ambiguous indices, slashes or brackets.
 *
 * <p>The grammar is re-proved here rather than trusted, because a Plan is constructible from raw values: a
 * card must not be able to carry an unbounded or hostile value into the Plan hash material even if some
 * later caller built it from a foreign projection. Counts are non-negative three-valued-logic counts.
 *
 * <p>This is the compiler-side projection of the accepted {@code PreviewConditionStatistic}: T15/T16's
 * compiler package reaches no Preview, Query or Segment package, so it re-states the accepted traversal
 * shape instead of importing it.
 */
public record PlanPreviewStatistic(String path, int trueCount, int falseCount, int unknownCount) {

    /** The one accepted structural root a condition path starts from. */
    private static final String ROOT = "r";
    private static final String NEGATION_STEP = "not";
    private static final String EXISTS_STEP = "exists";

    /**
     * The accepted absolute predicate nesting the T05 parser permits.
     *
     * <p>The compiler package reaches no Segment package, so the accepted depth is re-stated here and the
     * path budget is derived from it rather than approximated.
     */
    static final int MAX_PREDICATE_DEPTH = 32;

    /**
     * The bounded path budget a card statistic may use.
     *
     * <p>These are the exact accepted values: the accepted traversal starts at the root {@code r}, which is
     * depth 1, and each path step descends exactly one predicate level, so the deepest accepted path carries
     * {@code MAX_PREDICATE_DEPTH - 1} child steps. The accepted absolute path-length and group-index budgets
     * are re-stated too. A narrower bound would refuse a path an accepted traversal legitimately emits; a
     * wider one would accept a path no accepted predicate tree can produce.
     */
    static final int MAX_PATH_LENGTH = 256;
    static final int MAX_PATH_STEPS = MAX_PREDICATE_DEPTH - 1;
    static final int MAX_PATH_INDEX = 9_999;

    public PlanPreviewStatistic {
        requireValidStructuralPath(path);
        if (trueCount < 0 || falseCount < 0 || unknownCount < 0) {
            throw invalid("planPreviewStatistic");
        }
    }

    /** Proves one value is exactly an accepted structural path, or fails with one bounded code. */
    static void requireValidStructuralPath(String path) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
            throw invalid("planPreviewStatisticPath");
        }
        if (ROOT.equals(path)) {
            return;
        }
        if (path.length() < ROOT.length() + 2
                || path.charAt(0) != ROOT.charAt(0)
                || path.charAt(1) != '.') {
            throw invalid("planPreviewStatisticPath");
        }
        int index = 1;
        int steps = 0;
        while (index < path.length()) {
            if (path.charAt(index) != '.') {
                throw invalid("planPreviewStatisticPath");
            }
            index++;
            if (++steps > MAX_PATH_STEPS) {
                throw invalid("planPreviewStatisticPath");
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
            throw invalid("planPreviewStatisticPath");
        }
        int value = 0;
        for (int position = start; position < end; position++) {
            char character = path.charAt(position);
            if (character < '0' || character > '9') {
                throw invalid("planPreviewStatisticPath");
            }
            value = value * 10 + (character - '0');
        }
        if (value > MAX_PATH_INDEX) {
            throw invalid("planPreviewStatisticPath");
        }
    }

    /** Defensively freezes and validates a statistic list against the card's own bound. */
    static List<PlanPreviewStatistic> canonical(List<PlanPreviewStatistic> statistics, int bound) {
        if (statistics == null) {
            return null;
        }
        if (statistics.size() > bound) {
            throw invalid("planPreviewConditionStatistics");
        }
        for (PlanPreviewStatistic statistic : statistics) {
            if (statistic == null) {
                throw invalid("planPreviewConditionStatistics");
            }
        }
        return List.copyOf(statistics);
    }

    private static CompilerException invalid(String invariant) {
        return new CompilerException(
                CompilerErrorCodes.COMPILER_OUTPUT_INCONSISTENT,
                invariant,
                "A plan preview condition statistic must be a bounded structural fact.");
    }

    /** A bounded diagnostic; the value is already a structural path, never a label or an operand. */
    @Override
    public String toString() {
        return "PlanPreviewStatistic[path=" + path + ']';
    }
}