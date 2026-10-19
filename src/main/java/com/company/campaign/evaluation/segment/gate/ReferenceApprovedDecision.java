package com.company.campaign.evaluation.segment.gate;

import java.util.regex.Pattern;

/**
 * One approved Existing Audience Reference Gate decision as looked up for the reference capability.
 *
 * <p>The host binds the dedicated reference capability to the decisions approved for it; this value pairs
 * that binding with the exact {@link ReferenceGateDecision} and its approval evidence. The approval must name
 * the exact decision hash, so a mismatched pair is detectable rather than silently trusted.
 *
 * <p>The pair is only <em>structurally</em> paired at construction. {@link #isStructurallyValid()} re-derives
 * the decision hash and the approval hash from their canonical bytes and proves the exact decision-hash
 * binding and the decision/approval outcome equality. {@link #isValid()} additionally requires the complete
 * PRODUCT/DATA/TEST owner quorum. The readiness projection refuses to activate unless {@code isValid()} is
 * true, so tampered lookup evidence that a host returns can never become {@code ACTIVE}.
 */
public record ReferenceApprovedDecision(
        String capabilityId,
        ReferenceGateDecision decision,
        GateApprovalRecord approval
) {

    /** The bounded capability-identifier grammar, which admits the {@code -} in the reference identity. */
    static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9.-]{0,127}$");

    public ReferenceApprovedDecision {
        if (capabilityId == null || !CAPABILITY_ID.matcher(capabilityId).matches()) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A reference approved decision requires a bounded capability id");
        }
        if (decision == null || approval == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "A reference approved decision requires its decision and approval");
        }
        if (!approval.decisionHash().equals(decision.decisionHash())) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH,
                    "A reference approval must name the exact decision hash it approves");
        }
    }

    /**
     * True only when the decision and its approval recompute to their declared hashes, name the same decision
     * hash and agree on the verdict. The owner quorum is deliberately not required here, because an
     * incomplete quorum is <em>pending</em> rather than invalid and is named separately.
     */
    public boolean isStructurallyValid() {
        if (decision.decisionHash() == null || !decision.decisionHash().equals(decision.recomputeHash())) {
            return false;
        }
        if (approval.approvalHash() == null || !approval.approvalHash().equals(approval.recomputeHash())) {
            return false;
        }
        if (!approval.decisionHash().equals(decision.decisionHash())) {
            return false;
        }
        return approval.outcome() == decision.outcome();
    }

    /** True only when the evidence is structurally sound and carries a complete owner quorum. */
    public boolean isValid() {
        return isStructurallyValid() && approval.hasCompleteQuorum();
    }
}