package com.company.campaign.builder.authoring;

import com.company.campaign.builder.compiler.CapabilityReadiness;
import com.company.campaign.evaluation.segment.gate.SegmentReadinessProjection;

/**
 * The one adapter from the accepted T14 Gate projection to the compiler's neutral readiness compile input.
 *
 * <p>The generic compiler deliberately reaches no Gate, evaluation or capability package, so it accepts a
 * capability-neutral {@link CapabilityReadiness}. This adapter is where the concrete {@code segment.rule-based}
 * evidence becomes that neutral fact, and it is deliberately blind beyond the closed vocabulary it shares:
 * it re-states the projection's own state, {@code aiReady} flag and pins and invents nothing.
 *
 * <p>A projection for another capability is refused rather than reinterpreted: deriving this capability's
 * readiness from a different capability's evidence would relabel foreign Gate evidence as this one's, which is
 * exactly the kind of second truth the integration must never create. A missing projection is refused too,
 * because a compile that was pinned to no readiness is development evidence and nothing more.
 */
public final class SegmentAuthoringReadiness {

    private SegmentAuthoringReadiness() {
    }

    /**
     * Derives the frozen readiness input of one compile from the Gate's own projection.
     *
     * @throws SegmentAuthoringException when the projection is absent or belongs to another capability
     */
    public static CapabilityReadiness compileInput(SegmentReadinessProjection projection) {
        if (projection == null) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "readinessProjection",
                    "A compile requires the Gate's own readiness projection for this capability.");
        }
        if (!SegmentAuthoringVersions.CAPABILITY_CODE.equals(projection.capabilityId())) {
            throw new SegmentAuthoringException(
                    SegmentAuthoringErrorCodes.READINESS_INVALID,
                    "readinessCapability",
                    "A readiness projection of another capability is not this capability's evidence.");
        }
        return new CapabilityReadiness(
                SegmentAuthoringVersions.CAPABILITY_CODE,
                projection.state().name(),
                projection.aiReady(),
                projection.reasonCodes(),
                projection.approvedDecisionHash(),
                projection.currentInputsHash());
    }
}