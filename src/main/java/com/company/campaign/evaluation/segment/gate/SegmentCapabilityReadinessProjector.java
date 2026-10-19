package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Optional;

/**
 * The host-owned projection from approved Gate evidence to capability readiness.
 *
 * <p>It never evaluates, approves or activates anything by itself. It looks up the latest approved decision
 * through the host port, re-derives the decision hash, approval hash, verdict equality, decision-hash binding
 * and complete owner quorum from the looked-up evidence, and derives exactly one state:
 *
 * <ul>
 *   <li>no approved decision, or an approved decision that is not {@code PASSED} → {@code EXPERIMENTAL};</li>
 *   <li>an approved {@code PASSED} decision whose frozen pins differ from the current gated inputs in any
 *       way → {@code EVALUATION_REQUIRED};</li>
 *   <li>an approved {@code PASSED} decision whose frozen pins equal the current gated inputs exactly →
 *       {@code ACTIVE}, and only then is the Campaign {@code AI_READY} projection true.</li>
 * </ul>
 *
 * <p>Only the single Gate-gated {@code segment.rule-based} capability can be projected; every other
 * capability stays {@code EXPERIMENTAL} with a bounded reason code. No production path, and no other
 * capability, is activated here.
 */
public final class SegmentCapabilityReadinessProjector {

    /** The one capability this Gate may promote. */
    public static final String SEGMENT_RULE_BASED = "segment.rule-based";

    private final GateApprovalLookupPort lookupPort;

    public SegmentCapabilityReadinessProjector(GateApprovalLookupPort lookupPort) {
        if (lookupPort == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "The readiness projector requires its lookup port");
        }
        this.lookupPort = lookupPort;
    }

    /** Projects the readiness of one capability against the current gated inputs. */
    public SegmentReadinessProjection project(String capabilityId, GateRunPins currentInputs) {
        if (currentInputs == null) {
            throw GateException.of(
                    GateErrorCodes.GATED_INPUT_CHANGED, "A readiness projection requires the current gated pins");
        }
        ContentHash currentHash = currentInputs.contentHash();
        if (capabilityId == null) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED, "A readiness projection requires its capability id");
        }
        if (!SEGMENT_RULE_BASED.equals(capabilityId)) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.CAPABILITY_NOT_GATED), null, currentHash);
        }

        Optional<GateApprovedDecision> latest;
        try {
            latest = lookupPort.latestApproved(capabilityId);
        } catch (RuntimeException failure) {
            // A throwing or malformed lookup answer can never activate a capability; it is bounded evidence.
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (latest == null) {
            // A null answer is malformed evidence, not an absent approval.
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (latest.isEmpty()) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_MISSING), null, currentHash);
        }

        GateApprovedDecision approved = latest.get();
        if (!capabilityId.equals(approved.capabilityId())) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        // Re-derive the decision hash, approval hash, outcome equality, decision-hash binding and complete
        // quorum from the evidence before trusting any of it.
        if (!approved.isValid()) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        GateDecision decision = approved.decision();
        if (decision.outcome() != GateOutcome.PASSED) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.NOT_PASSED), decision.decisionHash(), currentHash);
        }
        if (!decision.runPins().equals(currentInputs)) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EVALUATION_REQUIRED, false,
                    List.of(GateErrorCodes.GATED_INPUT_CHANGED), decision.decisionHash(), currentHash);
        }
        return SegmentReadinessProjection.of(
                capabilityId, CapabilityState.ACTIVE, true, List.of(), decision.decisionHash(), currentHash);
    }
}