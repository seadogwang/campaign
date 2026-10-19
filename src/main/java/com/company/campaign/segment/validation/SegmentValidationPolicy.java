package com.company.campaign.segment.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable complexity and safety policy for one deterministic validation run.
 *
 * <p>Every value is positive and bounded by {@link SegmentValidationLimits}. A value outside those bounds is
 * rejected at construction with the stable {@link SegmentValidationErrorCodes#POLICY_INVALID} code rather
 * than being clamped, because silently weakening a policy would be indistinguishable from validating a
 * document that never met it.
 *
 * <p>The policy is deliberately structural: it bounds predicate nodes, depth, direct commutative width and
 * relation nesting. It never estimates database cost, generates a plan or consults a database.
 *
 * @param maxPredicateNodes      maximum total predicate nodes across the whole tree
 * @param maxPredicateDepth      maximum predicate nesting depth, counted from the root predicate as 1
 * @param maxAnyWidth            maximum direct child count of one {@code ANY} group
 * @param maxAllWidth            maximum direct child count of one {@code ALL} group
 * @param maxRelationNestingDepth maximum nested {@code EXISTS} depth
 */
public record SegmentValidationPolicy(
        int maxPredicateNodes,
        int maxPredicateDepth,
        int maxAnyWidth,
        int maxAllWidth,
        int maxRelationNestingDepth
) {

    public SegmentValidationPolicy {
        requirePositive(maxPredicateNodes, "maxPredicateNodes", SegmentValidationLimits.MAX_PREDICATE_NODES);
        requirePositive(maxPredicateDepth, "maxPredicateDepth", SegmentValidationLimits.MAX_PREDICATE_DEPTH);
        requirePositive(maxAnyWidth, "maxAnyWidth", SegmentValidationLimits.MAX_ANY_WIDTH);
        requirePositive(maxAllWidth, "maxAllWidth", SegmentValidationLimits.MAX_ALL_WIDTH);
        requirePositive(
                maxRelationNestingDepth, "maxRelationNestingDepth", SegmentValidationLimits.MAX_RELATION_NESTING_DEPTH);
    }

    /** The default policy: generous enough for real authoring, far below every absolute bound. */
    public static SegmentValidationPolicy standard() {
        return new SegmentValidationPolicy(2_000, SegmentValidationLimits.MAX_PREDICATE_DEPTH, 64, 256, 4);
    }

    private static void requirePositive(int value, String field, int maximum) {
        if (value < 1 || value > maximum) {
            // The rejected value is a configuration number, not a payload value, and is safe to report.
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("field", field);
            details.put("minimum", 1);
            details.put("maximum", maximum);
            throw new SegmentValidationException(
                    SegmentValidationErrorCodes.POLICY_INVALID,
                    "A complexity policy value for '" + field + "' must be a positive bounded value",
                    details);
        }
    }
}