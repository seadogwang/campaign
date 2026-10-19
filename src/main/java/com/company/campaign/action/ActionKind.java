package com.company.campaign.action;

import com.company.campaign.builder.capability.flow.FlowPackVersions;
import com.company.campaign.journey.graph.JourneyNodeKind;

/**
 * The closed kinds of external action this layer executes.
 *
 * <p>It is exactly the two frozen Journey node kinds that owe a remote effect, so no action kind can be
 * invented that no frozen definition could ever produce. A {@code MESSAGE} action creates no reward budget
 * exposure at all; a {@code REWARD} action reserves, holds, finalizes and releases the exact conservative
 * exposure of one grant.
 */
public enum ActionKind {

    MESSAGE("MESSAGE"),
    REWARD("REWARD");

    private final String code;

    ActionKind(String code) {
        this.code = code;
    }

    /** The bounded machine code this kind is recorded and hashed under. */
    public String code() {
        return code;
    }

    /** True when this kind creates a reward budget reservation. */
    public boolean reservesRewardBudget() {
        return this == REWARD;
    }

    /** The exact frozen T19 Flow capability code this kind's node is bound to. */
    public String capabilityCode() {
        return this == MESSAGE ? FlowPackVersions.MESSAGE : FlowPackVersions.REWARD;
    }

    /**
     * The exact action kind one frozen Journey node kind names.
     *
     * <p>Every other node kind fails closed here rather than defaulting: a START, WAIT, CONDITION or END node
     * has no remote effect to execute.
     */
    public static ActionKind from(JourneyNodeKind nodeKind) {
        if (nodeKind == JourneyNodeKind.MESSAGE) {
            return MESSAGE;
        }
        if (nodeKind == JourneyNodeKind.REWARD) {
            return REWARD;
        }
        throw ActionSupport.invalid(ActionErrorCodes.ACTION_INPUT_INVALID, "nodeKind");
    }
}