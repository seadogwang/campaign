package com.company.campaign.journey.simulation;

/**
 * The closed terminal status of one simulated path.
 *
 * <p>A path either ends at an {@code END} node, exits at an {@code EXIT} node or terminates on an explicit
 * simulated error. There is no "abandoned", "pending" or "waiting" status: a path is always fully resolved by
 * the pure simulation.
 */
public enum SimulatedPathStatus {

    /** The path reached an END node. */
    COMPLETED,

    /** The path reached an EXIT node. */
    EXITED,

    /** The path terminated on an explicit simulated error; it never branched. */
    ERRORED
}
