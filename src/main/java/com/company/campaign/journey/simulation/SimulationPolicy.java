package com.company.campaign.journey.simulation;

/**
 * The bounded, immutable hard limits of one Journey simulation.
 *
 * <p>A policy can only lower a hard maximum, never raise it: the three frozen maxima (64 distinct paths, 256
 * steps per path and 256 total actions) are the whole of what a simulation may ever produce. A value outside
 * {@code 1..hardMax} fails closed at construction, so a caller cannot configure an unbounded simulation.
 *
 * <p>The policy is an explicit frozen input. Exhausting a limit is reported as a bounded issue and fails
 * closed; it is never a silent truncation of the reported paths.
 */
public record SimulationPolicy(int maxPaths, int maxStepsPerPath, int maxActions) {

    /** The hard maximum number of distinct paths any simulation may produce. */
    public static final int HARD_MAX_PATHS = 64;

    /** The hard maximum number of node steps any single path may take. */
    public static final int HARD_MAX_STEPS_PER_PATH = 256;

    /** The hard maximum number of total actions any simulation may record. */
    public static final int HARD_MAX_ACTIONS = 256;

    public SimulationPolicy {
        requireBound(maxPaths, HARD_MAX_PATHS, "maxPaths");
        requireBound(maxStepsPerPath, HARD_MAX_STEPS_PER_PATH, "maxStepsPerPath");
        requireBound(maxActions, HARD_MAX_ACTIONS, "maxActions");
    }

    /** The policy that permits exactly the hard maxima, no more and no fewer. */
    public static SimulationPolicy defaults() {
        return new SimulationPolicy(HARD_MAX_PATHS, HARD_MAX_STEPS_PER_PATH, HARD_MAX_ACTIONS);
    }

    private static void requireBound(int value, int hardMax, String field) {
        if (value < 1 || value > hardMax) {
            throw new SimulationException(SimulationErrorCodes.POLICY_INVALID, field,
                    "A simulation policy bound must be between one and its hard maximum.");
        }
    }
}
