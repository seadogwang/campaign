package com.company.campaign.journey.simulation;

import com.company.campaign.journey.compiler.JourneyVersions;

/**
 * The version discriminators and frozen codes stamped into every T20 Simulation artifact.
 *
 * <p>A simulation is a deterministic, versioned transformation: a change to an evaluation rule, a limit or a
 * traversal rule is a new simulator version, so two simulation reports can never silently compare as equal
 * when they were produced under different rules. Every value here is part of the simulation report hash and is
 * reported verbatim.
 *
 * <p>The environment code is the one closed value a report may declare, and it is {@code SIMULATED}: a report
 * is an explicitly simulated, side-effect-free projection and never a claim of Runtime execution.
 */
public final class SimulationVersions {

    /** The T20 Journey simulation document format version. */
    public static final String SIMULATION_FORMAT_VERSION = "1.0";

    /** The T20 pure Journey simulator version. */
    public static final String SIMULATOR_VERSION = "journey-simulator/1.0";

    /** The exact runtime schema version a simulated Journey was compiled against. */
    public static final String RUNTIME_SCHEMA_VERSION = JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION;

    /** The one closed environment code every simulation report declares. */
    public static final String ENVIRONMENT_SIMULATED = "SIMULATED";

    private SimulationVersions() {
    }
}
