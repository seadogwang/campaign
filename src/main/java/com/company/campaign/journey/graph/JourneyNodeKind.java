package com.company.campaign.journey.graph;

/**
 * The closed Journey node kind derived from one enabled T19 Flow capability.
 *
 * <p>The mapping is total and exclusive: {@code flow.start} to {@link #START}, {@code flow.message} to
 * {@link #MESSAGE}, {@code flow.wait} to {@link #WAIT}, {@code flow.condition} to {@link #CONDITION},
 * {@code flow.reward} to {@link #REWARD} and {@code flow.end} to {@link #END} or {@link #EXIT} according to
 * its terminal payload. No other kind is representable.
 */
public enum JourneyNodeKind {

    START("flow.start", false),
    MESSAGE("flow.message", false),
    WAIT("flow.wait", false),
    CONDITION("flow.condition", false),
    REWARD("flow.reward", false),
    END("flow.end", true),
    EXIT("flow.end", true);

    private final String capabilityCode;
    private final boolean terminal;

    JourneyNodeKind(String capabilityCode, boolean terminal) {
        this.capabilityCode = capabilityCode;
        this.terminal = terminal;
    }

    /** The exact T19 Flow capability code this kind is derived from. */
    public String capabilityCode() {
        return capabilityCode;
    }

    /** True when this kind terminates a path. */
    public boolean isTerminal() {
        return terminal;
    }

    /** True when this kind is a Condition node that selects a TRUE or FALSE arm. */
    public boolean isCondition() {
        return this == CONDITION;
    }
}
