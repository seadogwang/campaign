package com.company.campaign.builder.tool;

import com.company.campaign.contracts.Disposition;

/**
 * One bounded Tool implementation result before it is admitted into a {@link SegmentToolOutcome} envelope.
 *
 * <p>A resolved execution carries the public outcome and no code; a non-resolved execution carries one stable
 * code and no outcome. The invariant is enforced here so a contradictory execution cannot be built.
 *
 * <p>Either shape may additionally carry the host-private typed {@link SegmentToolArtifact} of the operation
 * the handler really executed. That is how a deterministic resolution which legitimately ends at
 * {@code NEEDS_INPUT} still hands the Agent the genuine clarification without publishing it in the bounded
 * public envelope, and how an admitted stage hands back the accepted component result instead of a
 * model-visible value.
 */
public record SegmentToolExecution(Disposition disposition, String code, SegmentToolOutcome outcome,
        SegmentToolArtifact artifact) {

    public SegmentToolExecution {
        if (disposition == null) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "A Tool execution requires a disposition");
        }
        if (disposition.isResolved() != (outcome != null)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "Only a resolved Tool execution may carry an outcome");
        }
        if (disposition.isResolved() != (code == null)) {
            throw SegmentToolException.invalid(
                    SegmentToolErrorCodes.TOOL_RESULT_INCOHERENT, "Only a non-resolved Tool execution may carry a code");
        }
    }

    /** A resolved execution carrying the bounded public outcome and no host-private artifact. */
    public static SegmentToolExecution resolved(SegmentToolOutcome outcome) {
        return new SegmentToolExecution(Disposition.RESOLVED, null, outcome, null);
    }

    /** A resolved execution carrying the bounded public outcome and its host-private typed artifact. */
    public static SegmentToolExecution resolved(SegmentToolOutcome outcome, SegmentToolArtifact artifact) {
        return new SegmentToolExecution(Disposition.RESOLVED, null, outcome, artifact);
    }

    /** A non-resolved execution carrying one stable code and no host-private artifact. */
    public static SegmentToolExecution failed(Disposition disposition, String code) {
        return new SegmentToolExecution(disposition, code, null, null);
    }

    /** A non-resolved execution carrying one stable code and the host-private evidence it refused with. */
    public static SegmentToolExecution failed(
            Disposition disposition, String code, SegmentToolArtifact artifact) {
        return new SegmentToolExecution(disposition, code, null, artifact);
    }
}