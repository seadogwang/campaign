package com.company.campaign.segment.reference;

/**
 * Absolute defensive bounds for the reference graph, independent of any configured policy.
 *
 * <p>They exist so no request can widen a policy into unbounded traversal, and so a hostile or corrupt
 * catalog cannot drive validation into excessive work. A configured {@link ReferencePolicy} must stay inside
 * these bounds; the values are deliberately generous for real authoring and far above the shipped defaults.
 */
public final class ReferenceLimits {

    /** Absolute maximum transitive reference depth, counted from the document root as depth 0. */
    public static final int MAX_DEPTH = 8;

    /** Absolute maximum number of distinct referenced versions in one document's closure. */
    public static final int MAX_DISTINCT_VERSIONS = 256;

    /** Absolute maximum expanded complexity contributed by referenced versions. */
    public static final int MAX_EXPANDED_COMPLEXITY = 65_536;

    private ReferenceLimits() {
    }
}