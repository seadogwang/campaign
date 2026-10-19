package com.company.campaign.journey.simulation;

import com.company.campaign.contracts.Issue;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * The bounded, immutable outcome of one pure Journey simulation.
 *
 * <p>It is explicitly environment {@link SimulationVersions#ENVIRONMENT_SIMULATED} and binds the Journey
 * content hash, the topology hash, the source Manifest hash, the scenario hash, the virtual start and timezone
 * and the simulator version, so a report can never be mistaken for Runtime execution or compared across
 * different inputs. A successful report carries a deterministic bounded path list and no issue; a report that
 * exhausted a hard limit fails closed with a bounded issue and no path, never a silently truncated list.
 *
 * <p>It contains no template body, reward description or secret, provider output, credential, member identity,
 * PII, SQL, trusted security context or unrestricted exception text, and it creates no external side effect.
 */
public record SimulationResult(
        String environment,
        String simulationFormatVersion,
        String simulatorVersion,
        String runtimeSchemaVersion,
        ContentHash journeyContentHash,
        ContentHash topologyHash,
        ContentHash sourceManifestHash,
        ContentHash scenarioHash,
        Instant virtualStartInstant,
        ZoneId virtualTimezone,
        List<SimulatedPath> paths,
        List<Issue> issues) {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public SimulationResult {
        if (!SimulationVersions.ENVIRONMENT_SIMULATED.equals(environment)) {
            throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "environment",
                    "A simulation report must declare the simulated environment.");
        }
        if (!SimulationVersions.SIMULATION_FORMAT_VERSION.equals(simulationFormatVersion)
                || !SimulationVersions.SIMULATOR_VERSION.equals(simulatorVersion)) {
            throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "versions",
                    "A simulation report must carry supported versions.");
        }
        Objects.requireNonNull(journeyContentHash, "journeyContentHash");
        Objects.requireNonNull(topologyHash, "topologyHash");
        Objects.requireNonNull(sourceManifestHash, "sourceManifestHash");
        Objects.requireNonNull(scenarioHash, "scenarioHash");
        Objects.requireNonNull(virtualStartInstant, "virtualStartInstant");
        Objects.requireNonNull(virtualTimezone, "virtualTimezone");
        paths = List.copyOf(paths);
        issues = List.copyOf(issues);
    }

    /** True when the simulation resolved every path without exhausting a hard limit. */
    public boolean isValid() {
        return issues.isEmpty();
    }

    /** True when the simulation failed closed; no path is reported and an issue explains why. */
    public boolean isFailedClosed() {
        return !issues.isEmpty();
    }

    /**
     * The deterministic bounded path list, or a bounded rejection when the simulation failed closed.
     *
     * <p>It fails closed rather than returning a partial list, so a caller cannot mistake an exhausted limit
     * for a complete simulation.
     */
    public List<SimulatedPath> requirePaths() {
        if (isFailedClosed()) {
            throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "paths",
                    "The simulation failed closed and reports no paths.", issues);
        }
        return paths;
    }

    /** The canonical UTF-8 bytes every simulation semantic is covered by. */
    public byte[] canonicalBytes() {
        return CANONICAL_JSON.write(material());
    }

    /** The complete simulation report hash, computed from strict canonical bytes. */
    public ContentHash contentHash() {
        return CANONICAL_JSON.hash(material());
    }

    @Override
    public String toString() {
        return "SimulationResult[environment=" + environment
                + ", paths=" + paths.size()
                + ", issues=" + issues.size()
                + ", scenarioHash=" + scenarioHash + ']';
    }

    private Material material() {
        return new Material(environment, simulationFormatVersion, simulatorVersion, runtimeSchemaVersion,
                journeyContentHash, topologyHash, sourceManifestHash, scenarioHash,
                virtualStartInstant, virtualTimezone.toString(), paths, issues);
    }

    /** Package-private hash material; it is never public and never exposed to callers. */
    record Material(
            String environment,
            String simulationFormatVersion,
            String simulatorVersion,
            String runtimeSchemaVersion,
            ContentHash journeyContentHash,
            ContentHash topologyHash,
            ContentHash sourceManifestHash,
            ContentHash scenarioHash,
            Instant virtualStartInstant,
            String virtualTimezone,
            List<SimulatedPath> paths,
            List<Issue> issues) {
    }
}
