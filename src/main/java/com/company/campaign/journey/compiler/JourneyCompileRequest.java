package com.company.campaign.journey.compiler;

import com.company.campaign.builder.capability.composition.T19PackIndex;
import com.company.campaign.builder.compiler.ResolvedCampaignManifest;
import com.company.campaign.journey.graph.JourneyTopology;

import java.util.Objects;

/**
 * The explicit, frozen inputs of one deterministic Journey compile.
 *
 * <p>A request supplies only what the compile may read: the source {@code ResolvedCampaignManifest}, its
 * {@link JourneyTopology}, the frozen {@code T19PackIndex} and the exact compiler and runtime schema
 * versions. It carries no repository, clock, UUID, random source, ThreadLocal, model, network or Provider
 * handle, so the compiler is a pure function of these explicit inputs.
 *
 * <p>The compiler and runtime schema versions must be exactly the supported discriminators; any other value
 * fails closed at construction rather than being silently reinterpreted.
 */
public record JourneyCompileRequest(
        ResolvedCampaignManifest manifest,
        JourneyTopology topology,
        T19PackIndex packIndex,
        String compilerVersion,
        String runtimeSchemaVersion) {

    public JourneyCompileRequest {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(topology, "topology");
        Objects.requireNonNull(packIndex, "packIndex");
        if (!JourneyVersions.JOURNEY_COMPILER_VERSION.equals(compilerVersion)) {
            throw new JourneyCompileException(JourneyCompileErrorCodes.VERSION_UNSUPPORTED,
                    "compilerVersion", "The requested Journey compiler version is not supported.");
        }
        if (!JourneyVersions.JOURNEY_RUNTIME_SCHEMA_VERSION.equals(runtimeSchemaVersion)) {
            throw new JourneyCompileException(JourneyCompileErrorCodes.VERSION_UNSUPPORTED,
                    "runtimeSchemaVersion", "The requested Journey runtime schema version is not supported.");
        }
        if (!runtimeSchemaVersion.equals(packIndex.expectedRuntimeSchemaVersion())) {
            throw new JourneyCompileException(JourneyCompileErrorCodes.RUNTIME_INDEX_MISMATCH,
                    "packIndex", "The frozen pack index declares a different runtime schema version.");
        }
    }
}
