package com.company.campaign.segment.reference;

/**
 * Absolute defensive bounds for the reference discovery surface.
 *
 * <p>They exist because a discovery port is host-owned but not necessarily perfect: a corrupt or hostile
 * directory must not be able to drive a resolver into unbounded work or an unbounded clarification. A result
 * that exceeds a bound is refused outright rather than truncated, because silently keeping the first N
 * candidates would turn an over-bounded answer into a different, quieter one.
 */
public final class ReferenceDiscoveryLimits {

    /** Maximum candidate texts one audience reference may carry. */
    public static final int MAX_CANDIDATE_TEXTS = 16;

    /** Maximum UTF-16 length of one candidate text. */
    public static final int MAX_CANDIDATE_TEXT_LENGTH = 512;

    /** Maximum audience summaries one candidate text may resolve to. */
    public static final int MAX_CANDIDATES = 16;

    /** Maximum eligible published versions one audience may offer. */
    public static final int MAX_PUBLISHED_VERSIONS = 32;

    private ReferenceDiscoveryLimits() {
    }
}