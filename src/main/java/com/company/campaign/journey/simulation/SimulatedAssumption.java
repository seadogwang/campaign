package com.company.campaign.journey.simulation;

import com.company.campaign.journey.graph.JourneyBranchKind;

import java.util.Objects;

/**
 * One bounded machine assumption a deterministic UNKNOWN fork recorded.
 *
 * <p>When a Condition node predicates on an unknown fact, the simulation forks the path into both arms and
 * records, on each arm, the unknown fact code and the branch it assumed. It carries no fact value and no
 * member identity: the assumption is a bounded machine fact, not a data value.
 */
public record SimulatedAssumption(
        String nodeLogicalId, String factCode, JourneyBranchKind assumedBranch) {

    public SimulatedAssumption {
        Objects.requireNonNull(nodeLogicalId, "nodeLogicalId");
        Objects.requireNonNull(factCode, "factCode");
        Objects.requireNonNull(assumedBranch, "assumedBranch");
        if (!assumedBranch.isConditionArm()) {
            throw new SimulationException(SimulationErrorCodes.NODE_CONFIG_INVALID, "assumedBranch",
                    "An unknown-fact assumption must name a TRUE or FALSE condition arm.");
        }
    }

    /** The bounded machine assumption code. */
    public String code() {
        return SimulationErrorCodes.UNKNOWN_FACT_ASSUMPTION;
    }

    @Override
    public String toString() {
        return "SimulatedAssumption[nodeLogicalId=" + nodeLogicalId
                + ", factCode=" + factCode + ", assumedBranch=" + assumedBranch + ']';
    }
}
