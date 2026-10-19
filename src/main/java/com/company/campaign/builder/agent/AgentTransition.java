package com.company.campaign.builder.agent;

/**
 * One explicit, deterministic transition of the Agent state machine.
 *
 * <p>It records the exact source and target state, one stable evidence code derived from explicit input, the
 * server-owned Tool that supplied the evidence (when one did) and the sequence position. It carries no wall
 * clock, no random value, no payload and no trusted identifier, so the whole trace is replayable and
 * audit-safe.
 */
public record AgentTransition(
        SegmentAgentState fromState,
        SegmentAgentState toState,
        String evidenceCode,
        String toolId,
        int sequence
) {

    public AgentTransition {
        if (toState == null || evidenceCode == null) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.ILLEGAL_TRANSITION, "A transition requires its target state and evidence");
        }
        if (sequence < 0) {
            throw SegmentAgentException.invalid(
                    SegmentAgentErrorCodes.ILLEGAL_TRANSITION, "A transition sequence must not be negative");
        }
    }
}