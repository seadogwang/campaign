package com.company.campaign.query.sql;

/**
 * Immutable, bounded compile policy: the maximum IN-list width, parameter count and subquery depth.
 *
 * <p>Every bound is mandatory and has a hard ceiling, so a host cannot configure an unbounded compiler and a
 * plan cannot produce an unbounded statement. Exceeding a bound is a stable {@code SQL_LIMIT_EXCEEDED}
 * failure, never a truncated or partially compiled statement.
 */
public record SqlCompilePolicy(int maxInListItems, int maxParameters, int maxSubqueryDepth) {

    /** The largest declarable IN-list width. */
    public static final int MAX_IN_LIST_ITEMS_CEILING = 1_000;

    /** The largest declarable bind-parameter count. */
    public static final int MAX_PARAMETERS_CEILING = 10_000;

    /** The largest declarable correlated-subquery nesting depth. */
    public static final int MAX_SUBQUERY_DEPTH_CEILING = 32;

    public SqlCompilePolicy {
        requireBounded(maxInListItems, MAX_IN_LIST_ITEMS_CEILING, "maxInListItems");
        requireBounded(maxParameters, MAX_PARAMETERS_CEILING, "maxParameters");
        requireBounded(maxSubqueryDepth, MAX_SUBQUERY_DEPTH_CEILING, "maxSubqueryDepth");
    }

    /** The one standard policy, at the hard ceilings. */
    public static SqlCompilePolicy standard() {
        return new SqlCompilePolicy(
                MAX_IN_LIST_ITEMS_CEILING, MAX_PARAMETERS_CEILING, MAX_SUBQUERY_DEPTH_CEILING);
    }

    /** A narrower policy within the hard ceilings. */
    public static SqlCompilePolicy publish(int maxInListItems, int maxParameters, int maxSubqueryDepth) {
        return new SqlCompilePolicy(maxInListItems, maxParameters, maxSubqueryDepth);
    }

    private static void requireBounded(int value, int ceiling, String invariant) {
        if (value < 1 || value > ceiling) {
            throw new SqlException(SqlErrorCodes.SQL_LIMIT_EXCEEDED, invariant,
                    "A compile-policy bound must be positive and within its hard ceiling.");
        }
    }
}