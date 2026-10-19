package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.evaluation.segment.contract.GateOutcome;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;
import java.util.Optional;

/**
 * The host-owned projection from approved reference Gate evidence to Existing Audience Reference readiness.
 *
 * <p>It is the dedicated counterpart of {@link SegmentCapabilityReadinessProjector} and is deliberately
 * narrower: the reference extension is its own Gate identity, so this projector promotes only
 * {@link #REFERENCE_EXISTING_AUDIENCE_REFERENCE} and never {@code segment.rule-based}. An old rule-based
 * approval never satisfies the reference capability, and a reference approval never satisfies the base.
 *
 * <p>It never evaluates, approves or activates anything by itself. It looks up the latest approved reference
 * decision through the host port, re-derives the decision hash, approval hash, verdict equality,
 * decision-hash binding and complete owner quorum from the looked-up evidence, and derives exactly one state:
 *
 * <ul>
 *   <li>no reference inputs at all (a reference-free compile) → {@code EXPERIMENTAL} and the port is never
 *       consulted;</li>
 *   <li>no approved decision, or malformed/tampered/wrong-capability evidence → {@code EXPERIMENTAL};</li>
 *   <li>an approved decision whose owner quorum is not yet complete → {@code EXPERIMENTAL} (pending);</li>
 *   <li>an approved decision that is not {@code PASSED} → {@code EXPERIMENTAL};</li>
 *   <li>an approved {@code PASSED} decision whose frozen reference inputs differ from the current ones in any
 *       way → {@code EVALUATION_REQUIRED};</li>
 *   <li>an approved {@code PASSED} decision whose frozen reference inputs equal the current ones exactly →
 *       {@code ACTIVE}, and only then is the Campaign {@code AI_READY} projection true.</li>
 * </ul>
 *
 * <p>Only an exact, genuine, approved {@code PASSED} reference decision over the exact current inputs
 * activates the capability. The honest default for the current code, which has no approved reference Gate
 * decision, is {@code EXPERIMENTAL}; nothing here manufactures a state the authoring path would overstate.
 */
public final class ReferenceReadinessProjector {

    /** The one capability this reference Gate may promote. */
    public static final String REFERENCE_EXISTING_AUDIENCE_REFERENCE = "segment.existing-audience-reference";

    private final ReferenceApprovalLookupPort lookupPort;

    public ReferenceReadinessProjector(ReferenceApprovalLookupPort lookupPort) {
        if (lookupPort == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "The reference readiness projector requires its lookup port");
        }
        this.lookupPort = lookupPort;
    }

    /** Projects the readiness of one capability against the current reference inputs. */
    public SegmentReadinessProjection project(String capabilityId, ReferenceReadinessInputs inputs) {
        if (capabilityId == null) {
            throw GateException.of(
                    GateErrorCodes.CAPABILITY_NOT_GATED, "A reference readiness projection requires its capability id");
        }
        if (inputs == null) {
            // A reference-free compile supplies no reference inputs: the reference Gate port must not be
            // consulted at all, and the projection can never be ACTIVE.
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.REFERENCE_NOT_PRESENT), null, null);
        }
        ContentHash currentHash = inputs.contentHash();
        if (!REFERENCE_EXISTING_AUDIENCE_REFERENCE.equals(capabilityId)) {
            return SegmentReadinessProjection.of(
                    capabilityId, CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.CAPABILITY_NOT_GATED), null, currentHash);
        }

        Optional<ReferenceApprovedDecision> latest;
        try {
            latest = lookupPort.latestApproved(capabilityId);
        } catch (RuntimeException failure) {
            // A throwing lookup answer is malformed evidence and can never activate the reference capability.
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (latest == null) {
            // A null answer is malformed evidence, not an absent approval.
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (latest.isEmpty()) {
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_MISSING), null, currentHash);
        }

        ReferenceApprovedDecision approved = latest.get();
        if (!REFERENCE_EXISTING_AUDIENCE_REFERENCE.equals(approved.capabilityId())) {
            // An approval bound to another capability, such as segment.rule-based, is not reference evidence.
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (!approved.isStructurallyValid()) {
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.APPROVAL_INVALID), null, currentHash);
        }
        if (!approved.approval().hasCompleteQuorum()) {
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.REFERENCE_APPROVAL_PENDING), null, currentHash);
        }
        ReferenceGateDecision decision = approved.decision();
        if (decision.outcome() != GateOutcome.PASSED) {
            return projection(CapabilityState.EXPERIMENTAL, false,
                    List.of(GateErrorCodes.NOT_PASSED), decision.decisionHash(), currentHash);
        }
        if (!decision.inputs().equals(inputs)) {
            return projection(CapabilityState.EVALUATION_REQUIRED, false,
                    List.of(GateErrorCodes.GATED_INPUT_CHANGED), decision.decisionHash(), currentHash);
        }
        return projection(CapabilityState.ACTIVE, true, List.of(), decision.decisionHash(), currentHash);
    }

    private SegmentReadinessProjection projection(
            CapabilityState state, boolean aiReady, List<String> reasonCodes,
            ContentHash approvedDecisionHash, ContentHash currentInputsHash) {
        return SegmentReadinessProjection.of(
                REFERENCE_EXISTING_AUDIENCE_REFERENCE, state, aiReady, reasonCodes,
                approvedDecisionHash, currentInputsHash);
    }
}