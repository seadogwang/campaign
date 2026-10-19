package com.company.campaign.journey.graph;

/**
 * The one closed branch kind a {@link JourneyTopologyEdge} may carry.
 *
 * <p>A Journey edge is not labelled by a free-form string or by a caller ordering: it is exactly one of a
 * default successor, a condition's true arm or a condition's false arm. The closed set is what lets the graph
 * contract prove that a Condition node has exactly one {@code TRUE} and one {@code FALSE} out-edge and that a
 * non-condition node never claims a condition arm.
 */
public enum JourneyBranchKind {

    /** The single unconditional successor of a non-condition node. */
    DEFAULT(0),

    /** The arm a Condition node takes when its predicate is true. */
    TRUE(1),

    /** The arm a Condition node takes when its predicate is false. */
    FALSE(2);

    private final int canonicalRank;

    JourneyBranchKind(int canonicalRank) {
        this.canonicalRank = canonicalRank;
    }

    /** The frozen rank used for deterministic canonical edge ordering; it is not a semantic priority. */
    public int canonicalRank() {
        return canonicalRank;
    }

    /** True when this kind is a condition arm rather than the default successor. */
    public boolean isConditionArm() {
        return this == TRUE || this == FALSE;
    }

    /**
     * Resolves the exact canonical wire spelling, or null when the value is not part of the closed set.
     *
     * <p>Matching is exact, so {@code true}, {@code True} and {@code default} are refused rather than silently
     * normalized into a branch a caller did not name.
     */
    public static JourneyBranchKind fromWireName(String wireName) {
        for (JourneyBranchKind branch : values()) {
            if (branch.name().equals(wireName)) {
                return branch;
            }
        }
        return null;
    }
}
