package com.company.campaign.builder.refine;

/**
 * Explicit defensive representation bounds of the versioned refinement patch contract.
 *
 * <p>They exist so a single model-generated patch can never be unbounded: the UTF-8 payload, the ordered
 * operation list, the whole refined tree and the per-run Tool path are all bounded before any allocation or
 * recursion happens. They are compile-time constants, so no request and no Skill resource can raise them.
 */
public final class RefinementLimits {

    /** Maximum accepted UTF-8 size of one serialized refinement patch, before any tree is built. */
    public static final int MAX_INPUT_BYTES = 65_536;

    /** Minimum number of operations a patch must carry: an empty patch is not an edit. */
    public static final int MIN_OPERATIONS = 1;

    /** Maximum number of ordered operations one patch may carry. */
    public static final int MAX_OPERATIONS = 32;

    /** Maximum number of predicate nodes the refined document may contain. */
    public static final int MAX_TREE_NODES = 512;

    /**
     * Absolute ceiling on the number of state entries one refinement run records, including the start state.
     *
     * <p>It is the Skill's own absolute step ceiling, so a Skill-declared bound is always inside this range
     * while the refinement machine still cannot be driven by an unbounded number.
     */
    public static final int MAX_STEPS = com.company.campaign.builder.skill.SegmentSkillLimits.MAX_STEPS;

    private RefinementLimits() {
    }
}