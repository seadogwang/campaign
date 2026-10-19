package com.company.campaign.journey.simulation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One fully resolved, immutable simulated path.
 *
 * <p>It carries the closed terminal status, the identity of the node the path ended at, the ordered visited
 * node identities with the parallel virtual entry instants, the virtual action codes it recorded and the
 * bounded unknown-fact assumptions it made. Every collection is defensively copied and ordered, so a caller
 * cannot mutate a reported path.
 *
 * <p>The path carries no Execution, Participant, runtime task, message body or reward data: it is a pure,
 * side-effect-free projection of the virtual clock.
 */
public record SimulatedPath(
        SimulatedPathStatus status,
        String terminalNodeLogicalId,
        List<String> visitedNodeLogicalIds,
        List<Instant> entryVirtualInstants,
        List<SimulatedAction> actions,
        List<SimulatedAssumption> assumptions,
        String errorReasonCode) {

    public SimulatedPath {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(terminalNodeLogicalId, "terminalNodeLogicalId");
        visitedNodeLogicalIds = List.copyOf(visitedNodeLogicalIds);
        entryVirtualInstants = List.copyOf(entryVirtualInstants);
        actions = List.copyOf(actions);
        assumptions = List.copyOf(assumptions);
        if (visitedNodeLogicalIds.isEmpty()
                || visitedNodeLogicalIds.size() != entryVirtualInstants.size()) {
            throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "visitedNodeLogicalIds",
                    "A path must carry one virtual instant per visited node.");
        }
        if (status == SimulatedPathStatus.ERRORED) {
            if (errorReasonCode == null) {
                throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "errorReasonCode",
                        "An errored path must carry a bounded reason code.");
            }
        } else if (errorReasonCode != null) {
            throw new SimulationException(SimulationErrorCodes.SIMULATION_INVALID, "errorReasonCode",
                    "A non-errored path must not carry a reason code.");
        }
    }

    /** True when the path ended on an explicit simulated error. */
    public boolean isErrored() {
        return status == SimulatedPathStatus.ERRORED;
    }

    /** The virtual instant at which the path ended. */
    public Instant terminalVirtualInstant() {
        return entryVirtualInstants.get(entryVirtualInstants.size() - 1);
    }

    /** The deterministic order key of this path, independent of traversal order. */
    String orderKey() {
        StringBuilder key = new StringBuilder(128);
        key.append(status.name()).append('|').append(terminalNodeLogicalId).append('|');
        key.append(String.join(",", visitedNodeLogicalIds)).append('|');
        for (SimulatedAssumption assumption : assumptions) {
            key.append(assumption.factCode()).append(':').append(assumption.assumedBranch()).append(',');
        }
        return key.toString();
    }

    @Override
    public String toString() {
        return "SimulatedPath[status=" + status
                + ", terminal=" + terminalNodeLogicalId
                + ", visited=" + visitedNodeLogicalIds.size()
                + ", actions=" + actions.size()
                + ", assumptions=" + assumptions.size() + ']';
    }
}
