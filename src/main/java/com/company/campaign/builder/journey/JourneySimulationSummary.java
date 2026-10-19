package com.company.campaign.builder.journey;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/**
 * The bounded, immutable public projection of one Journey preview simulation.
 *
 * <p>It carries only the closed simulated environment code, the exact simulator version, the explicit virtual
 * start and timezone, the scenario, journey and simulation hashes, the bounded path count, the closed path
 * statuses and the bounded issue codes. It carries no template body, reward description or secret, member
 * identity, Provider response, credential, SQL or unrestricted exception text, and it creates no side effect.
 *
 * <p>Simulation is pure preview evidence only: it is never stageable and never a substitute for validation.
 */
public record JourneySimulationSummary(
        String environment,
        String simulatorVersion,
        String virtualStartInstant,
        String virtualTimezone,
        int pathCount,
        List<String> pathStatuses,
        List<String> issueCodes,
        ContentHash scenarioHash,
        ContentHash simulationHash) {

    /** The one closed environment code every summary declares. */
    public static final String ENVIRONMENT_SIMULATED = "SIMULATED";

    public JourneySimulationSummary {
        if (!ENVIRONMENT_SIMULATED.equals(environment) || simulatorVersion == null
                || virtualStartInstant == null || virtualTimezone == null
                || scenarioHash == null || simulationHash == null) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        if (pathCount < 0) {
            throw new JourneyAuthoringException(JourneyAuthoringErrorCodes.SIMULATION_INPUT_INVALID);
        }
        pathStatuses = pathStatuses == null ? List.of() : List.copyOf(pathStatuses);
        issueCodes = issueCodes == null ? List.of() : List.copyOf(issueCodes);
    }

    /** True when the simulation resolved every path without exhausting a hard limit. */
    public boolean isValid() {
        return issueCodes.isEmpty();
    }

    /** A bounded diagnostic: the counts and hashes only, never a fact value. */
    @Override
    public String toString() {
        return "JourneySimulationSummary[paths=" + pathCount
                + ", issues=" + issueCodes.size()
                + ", simulationHash=" + simulationHash + ']';
    }
}