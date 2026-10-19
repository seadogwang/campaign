package com.company.campaign.builder.journey;

import com.company.campaign.journey.simulation.SimulationPolicy;

import java.util.List;

/**
 * The explicit, bounded virtual-clock inputs of one Journey preview simulation.
 *
 * <p>It carries an explicit virtual start instant, an explicit timezone, explicit bounded scenario facts and an
 * explicit policy no wider than the accepted hard maxima. It carries no system clock, no random source, no
 * member fact, no Execution, no Action record and no Provider handle, so a simulation built from it can only
 * project a virtual clock and can create no Runtime work.
 */
public record JourneySimulationInput(
        String virtualStartInstant,
        String virtualTimezone,
        List<JourneyScenarioFact> facts,
        int maxPaths,
        int maxStepsPerPath,
        int maxActions) {

    public JourneySimulationInput {
        if (virtualStartInstant == null || virtualStartInstant.isEmpty()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        if (virtualTimezone == null || virtualTimezone.isEmpty()) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        facts = facts == null ? List.of() : List.copyOf(facts);
        if (maxPaths < 1 || maxPaths > SimulationPolicy.HARD_MAX_PATHS
                || maxStepsPerPath < 1 || maxStepsPerPath > SimulationPolicy.HARD_MAX_STEPS_PER_PATH
                || maxActions < 1 || maxActions > SimulationPolicy.HARD_MAX_ACTIONS) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
    }

    /** The accepted default policy: exactly the hard maxima, no more and no fewer. */
    public static JourneySimulationInput defaults(
            String virtualStartInstant, String virtualTimezone, List<JourneyScenarioFact> facts) {
        return new JourneySimulationInput(
                virtualStartInstant, virtualTimezone, facts,
                SimulationPolicy.HARD_MAX_PATHS, SimulationPolicy.HARD_MAX_STEPS_PER_PATH,
                SimulationPolicy.HARD_MAX_ACTIONS);
    }

    /** Redacted diagnostics: the virtual pin facts and the scenario bound, never a fact value. */
    @Override
    public String toString() {
        return "JourneySimulationInput[start=" + virtualStartInstant
                + ", timezone=" + virtualTimezone
                + ", facts=" + facts.size() + ']';
    }
}