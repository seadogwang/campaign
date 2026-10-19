package com.company.campaign.journey.simulation;

import java.time.Instant;
import java.util.Objects;

/**
 * One virtual action a simulation recorded at a node entry.
 *
 * <p>It carries the closed {@link SimulatedActionKind} code, the reached node's stable logical identity and the
 * virtual instant of the node entry. It carries no message body, reward data, provider output or execution
 * identity, and it creates no Action record.
 */
public record SimulatedAction(SimulatedActionKind kind, String nodeLogicalId, Instant virtualInstant) {

    public SimulatedAction {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(virtualInstant, "virtualInstant");
    }

    @Override
    public String toString() {
        return "SimulatedAction[kind=" + kind + ", nodeLogicalId=" + nodeLogicalId
                + ", virtualInstant=" + virtualInstant + ']';
    }
}
