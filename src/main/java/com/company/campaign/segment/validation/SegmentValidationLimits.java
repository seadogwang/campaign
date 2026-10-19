package com.company.campaign.segment.validation;

import com.company.campaign.segment.dsl.contract.SegmentDslLimits;

/**
 * Absolute, defensive bounds for every T07 complexity policy value.
 *
 * <p>A policy always carries positive values, and no policy value may exceed these caps. They exist so a
 * caller — and therefore a configuration error or a model-influenced default — cannot publish a policy that
 * makes the validator unbounded. They are deliberately far below any resource-exhaustion threshold and
 * strictly compatible with the T05 structural limits the parser already enforces.
 */
public final class SegmentValidationLimits {

    /** Maximum total predicate nodes one policy may permit. */
    public static final int MAX_PREDICATE_NODES = 100_000;

    /** Maximum predicate depth one policy may permit; the T05 parser never accepts more than this. */
    public static final int MAX_PREDICATE_DEPTH = SegmentDslLimits.MAX_PREDICATE_DEPTH;

    /** Maximum direct ANY width one policy may permit. */
    public static final int MAX_ANY_WIDTH = 10_000;

    /** Maximum direct ALL width one policy may permit. */
    public static final int MAX_ALL_WIDTH = 10_000;

    /** Maximum nested EXISTS depth one policy may permit. */
    public static final int MAX_RELATION_NESTING_DEPTH = 16;

    private SegmentValidationLimits() {
    }
}