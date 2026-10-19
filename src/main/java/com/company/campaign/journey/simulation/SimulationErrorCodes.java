package com.company.campaign.journey.simulation;

/**
 * Stable machine-readable codes owned by the T20 pure Journey simulation.
 *
 * <p>These values are part of the wire contract: a host or a review envelope branches on them, so an existing
 * code is never renamed or reused for a different meaning and a new failure mode gets a new code. Every code
 * carries fixed prose only; no code ever echoes a fact value, a scenario value, a logical identifier, template
 * or reward data, PII or an unrestricted exception text.
 */
public final class SimulationErrorCodes {

    /** The requested simulator version is not the one supported simulator version. */
    public static final String SIMULATOR_VERSION_UNSUPPORTED = "JOURNEY_SIMULATION_VERSION_UNSUPPORTED";

    /** A required simulation input was absent. */
    public static final String INPUT_MISSING = "JOURNEY_SIMULATION_INPUT_MISSING";

    /** The source Manifest supplied for node configuration is not the Journey's bound source Manifest. */
    public static final String SOURCE_MANIFEST_MISMATCH = "JOURNEY_SIMULATION_SOURCE_MANIFEST_MISMATCH";

    /** The simulation policy is outside the hard bounds. */
    public static final String POLICY_INVALID = "JOURNEY_SIMULATION_POLICY_INVALID";

    /** A scenario fact is malformed or two facts declare the same fact code. */
    public static final String SCENARIO_INVALID = "JOURNEY_SIMULATION_SCENARIO_INVALID";

    /** A node's configuration (a Condition predicate or a Wait anchor/duration) is not the required shape. */
    public static final String NODE_CONFIG_INVALID = "JOURNEY_SIMULATION_NODE_CONFIG_INVALID";

    /** A Journey node required an outgoing edge that the topology does not carry. */
    public static final String EDGE_MISSING = "JOURNEY_SIMULATION_EDGE_MISSING";

    /** The distinct path count would exceed the configured hard path bound. */
    public static final String PATH_LIMIT_EXCEEDED = "JOURNEY_SIMULATION_PATH_LIMIT_EXCEEDED";

    /** One path would exceed the configured hard step bound. */
    public static final String STEP_LIMIT_EXCEEDED = "JOURNEY_SIMULATION_STEP_LIMIT_EXCEEDED";

    /** The total action count would exceed the configured hard action bound. */
    public static final String ACTION_LIMIT_EXCEEDED = "JOURNEY_SIMULATION_ACTION_LIMIT_EXCEEDED";

    /** A simulation report that failed closed was asked for its paths. */
    public static final String SIMULATION_INVALID = "JOURNEY_SIMULATION_INVALID";

    /** A scenario fact carries an explicit simulated error; the reason code is the fact's own bounded code. */
    public static final String CONDITION_FACT_ERROR = "JOURNEY_SIMULATION_FACT_ERROR";

    /** A missing or unknown fact with the unknown-fail-closed policy terminates the path as an error. */
    public static final String CONDITION_FACT_UNKNOWN_FAIL_CLOSED =
            "JOURNEY_SIMULATION_FACT_UNKNOWN_FAIL_CLOSED";

    /** A scenario fact value kind does not match the Condition predicate value kind. */
    public static final String CONDITION_FACT_TYPE_MISMATCH = "JOURNEY_SIMULATION_FACT_TYPE_MISMATCH";

    /** A scenario fact value is not the canonical text of its declared value kind. */
    public static final String CONDITION_FACT_VALUE_INVALID = "JOURNEY_SIMULATION_FACT_VALUE_INVALID";

    /** A Condition node predicates on a fact code the scenario did not supply. */
    public static final String CONDITION_FACT_MISSING = "JOURNEY_SIMULATION_FACT_MISSING";

    /** A deterministic UNKNOWN fork recorded an unknown fact and branch as a bounded assumption. */
    public static final String UNKNOWN_FACT_ASSUMPTION = "JOURNEY_SIMULATION_UNKNOWN_FACT_ASSUMPTION";

    private SimulationErrorCodes() {
    }
}
