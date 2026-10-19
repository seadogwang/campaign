package com.company.campaign.segment.reference;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable, configurable bounds for one reference-bearing document.
 *
 * <p>Three independent bounds are enforced, because each catches a different failure: {@code maxDepth} stops
 * an ever-deeper dependency chain, {@code maxDistinctVersions} stops a wide fan-out of many versions and
 * {@code maxExpandedComplexity} stops a small number of individually enormous referenced definitions from
 * expanding into unbounded work.
 *
 * <p>Every value is positive and bounded by {@link ReferenceLimits}. A value outside those bounds is rejected
 * at construction with the stable {@link ReferenceErrorCodes#POLICY_INVALID} code rather than clamped, because
 * silently weakening a policy would be indistinguishable from validating a document that never met it.
 *
 * <p>{@link #standard()} is a fixed, bounded default. A request never supplies its own numbers, so a caller
 * cannot buy a wider graph by asking for one.
 *
 * @param maxDepth              maximum transitive reference depth, counted from the document root as depth 0
 * @param maxDistinctVersions   maximum number of distinct referenced versions in the transitive closure
 * @param maxExpandedComplexity maximum total expanded complexity contributed by referenced versions
 */
public record ReferencePolicy(int maxDepth, int maxDistinctVersions, int maxExpandedComplexity) {

    /** The configured default: generous for real authoring, far below every absolute bound. */
    private static final int DEFAULT_MAX_DEPTH = 4;
    private static final int DEFAULT_MAX_DISTINCT_VERSIONS = 32;
    private static final int DEFAULT_MAX_EXPANDED_COMPLEXITY = 8_192;

    public ReferencePolicy {
        requireBounded(maxDepth, "maxDepth", ReferenceLimits.MAX_DEPTH);
        requireBounded(maxDistinctVersions, "maxDistinctVersions", ReferenceLimits.MAX_DISTINCT_VERSIONS);
        requireBounded(maxExpandedComplexity, "maxExpandedComplexity", ReferenceLimits.MAX_EXPANDED_COMPLEXITY);
    }

    /** The one bounded default policy; never derived from a request. */
    public static ReferencePolicy standard() {
        return new ReferencePolicy(
                DEFAULT_MAX_DEPTH, DEFAULT_MAX_DISTINCT_VERSIONS, DEFAULT_MAX_EXPANDED_COMPLEXITY);
    }

    private static void requireBounded(int value, String field, int maximum) {
        if (value < 1 || value > maximum) {
            // The rejected value is a configuration number, not payload data, and is safe to report.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", field);
            details.put("minimum", 1);
            details.put("maximum", maximum);
            throw new ReferenceException(
                    ReferenceErrorCodes.POLICY_INVALID,
                    "A reference policy value for '" + field + "' must be a positive bounded value",
                    details);
        }
    }
}