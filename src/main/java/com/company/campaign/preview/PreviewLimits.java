package com.company.campaign.preview;

import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

/**
 * Absolute upper bounds a {@link PreviewPolicy} may never exceed.
 *
 * <p>They are deliberately far above any sane server configuration but still bounded, so a mistyped policy
 * cannot turn the Preview boundary into an unbounded allocation or an unbounded response. A policy value
 * outside {@code [1, bound]} is rejected at construction rather than silently clamped, because a silently
 * weakened policy is indistinguishable from one that was never applied.
 */
public final class PreviewLimits {

    /** Maximum accepted HTTP request body size in bytes. */
    public static final int MAX_REQUEST_BYTES = 262_144;

    /** Maximum condition statistics a Preview response may carry. */
    public static final int MAX_CONDITION_STATS = 4_096;

    /** Maximum sample hashes a Preview response may carry. */
    public static final int MAX_SAMPLE_SIZE = 1_000;

    /** Maximum exact matched count a Preview response may report. */
    public static final int MAX_MATCHED_COUNT = 10_000_000;

    /** Maximum accepted {@code asOf - dataWatermark} freshness bound, in seconds. */
    public static final long MAX_DATA_AGE_SECONDS = 604_800L;

    /**
     * Absolute maximum length of one response-visible structural condition path, in UTF-16 code units.
     *
     * <p>The accepted T08 traversal emits one {@code .groupIndex}, {@code .not} or {@code .exists} step per
     * nesting level, so the deepest realistic path is well under 256 characters; this is the defensive cap
     * that keeps a faulty backend from projecting an arbitrarily long opaque string.
     */
    public static final int MAX_CONDITION_PATH_LENGTH = 256;

    /**
     * Absolute maximum number of dot-separated child steps in one structural condition path.
     *
     * <p>It is derived from the exact deepest predicate nesting the T05 parser accepts: the accepted traversal
     * starts at the root {@code r}, which is depth 1, and each path step descends exactly one predicate level,
     * so the deepest accepted path carries {@code SegmentDslLimits.MAX_PREDICATE_DEPTH - 1} child steps. A path
     * with one more step than that cannot come from any accepted T05/T08 predicate tree and is refused
     * deterministically.
     */
    public static final int MAX_CONDITION_PATH_STEPS = SegmentDslLimits.MAX_PREDICATE_DEPTH - 1;

    /**
     * Absolute maximum group index one structural condition path step may name.
     *
     * <p>It matches the largest direct ALL/ANY width a T07 complexity policy may permit, so every index the
     * accepted traversal can emit stays representable while a huge injected index is refused.
     */
    public static final int MAX_CONDITION_PATH_INDEX = 9_999;

    private PreviewLimits() {
    }
}