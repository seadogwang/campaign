package com.company.campaign.builder.refine;

/**
 * One explicit, deterministic transition of the refinement state machine.
 *
 * <p>It records the exact source and target state, one stable evidence code derived from explicit input, the
 * server-owned Tool that supplied the evidence (when one did) and the sequence position. It carries no wall
 * clock, no random value, no patch value and no trusted identifier, so the whole trace is replayable and
 * audit-safe.
 */
public record SegmentRefinementTransition(
        SegmentRefinementState fromState,
        SegmentRefinementState toState,
        String evidenceCode,
        String toolId,
        int sequence
) {

    public SegmentRefinementTransition {
        if (toState == null || evidenceCode == null) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.ILLEGAL_TRANSITION,
                    "A transition requires its target state and evidence");
        }
        if (sequence < 0) {
            throw SegmentRefinementException.of(
                    SegmentRefinementAdapterErrorCodes.ILLEGAL_TRANSITION,
                    "A transition sequence must not be negative");
        }
    }
}