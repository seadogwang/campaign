package com.company.campaign.journey.simulation;

import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.journey.compiler.JourneyManifest;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * The explicit, frozen inputs of one pure Journey simulation.
 *
 * <p>A request supplies only what the simulation may read: the compiled {@link JourneyManifest}, the exact
 * source {@link ResolvedCampaignManifest} whose payloads carry the Condition predicate and Wait
 * anchor/duration (bound to the Journey by its source Manifest hash), an explicit virtual start instant and
 * timezone, the immutable {@link ScenarioFacts} and the bounded {@link SimulationPolicy}, at the one supported
 * simulator version. It carries no repository, system clock, UUID, random source, ThreadLocal, model, network,
 * Provider handle, Execution, Participant or Action record.
 *
 * <p>The source Manifest must be the exact Manifest the Journey was compiled from: a mismatched Manifest hash
 * fails closed rather than letting the simulation read configuration the Journey never bound.
 */
public record SimulationRequest(
        JourneyManifest journey,
        ResolvedCampaignManifest sourceManifest,
        Instant virtualStartInstant,
        ZoneId virtualTimezone,
        ScenarioFacts scenarioFacts,
        SimulationPolicy policy,
        String simulatorVersion) {

    public SimulationRequest {
        Objects.requireNonNull(journey, "journey");
        Objects.requireNonNull(sourceManifest, "sourceManifest");
        Objects.requireNonNull(virtualStartInstant, "virtualStartInstant");
        Objects.requireNonNull(virtualTimezone, "virtualTimezone");
        Objects.requireNonNull(scenarioFacts, "scenarioFacts");
        Objects.requireNonNull(policy, "policy");
        if (!SimulationVersions.SIMULATOR_VERSION.equals(simulatorVersion)) {
            throw new SimulationException(SimulationErrorCodes.SIMULATOR_VERSION_UNSUPPORTED, "simulatorVersion",
                    "The requested simulator version is not supported.");
        }
        if (!sourceManifest.manifestHash().equals(journey.sourceManifestHash())) {
            throw new SimulationException(SimulationErrorCodes.SOURCE_MANIFEST_MISMATCH, "sourceManifest",
                    "The source Manifest is not the Journey's bound source Manifest.");
        }
    }
}
