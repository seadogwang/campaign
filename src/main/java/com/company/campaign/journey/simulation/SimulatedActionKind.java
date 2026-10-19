package com.company.campaign.journey.simulation;

/**
 * The closed virtual action codes a simulation may record.
 *
 * <p>A simulation records that a Message or Reward node was reached; it records no message body, no reward
 * description, no provider call, no grant and no external side effect. The two codes are the whole of what an
 * action record may carry.
 */
public enum SimulatedActionKind {

    /** A virtual message action code; no message is sent. */
    MESSAGE("MESSAGE"),

    /** A virtual reward action code; no reward is granted. */
    REWARD("REWARD");

    private final String code;

    SimulatedActionKind(String code) {
        this.code = code;
    }

    /** The bounded virtual action code. */
    public String code() {
        return code;
    }
}
