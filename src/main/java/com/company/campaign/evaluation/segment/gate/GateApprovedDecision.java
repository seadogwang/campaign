package com.company.campaign.evaluation.segment.gate;

import java.util.regex.Pattern;

/**
 * One approved Gate decision as looked up for one capability.
 *
 * <p>The host binds a capability to the decisions approved for it; this value pairs that binding with the
 * exact decision and its approval evidence. The approval must name the exact decision hash, so a mismatched
 * pair is detectable rather than silently trusted.
 *
 * <p>The pair is only <em>structurally</em> paired at construction. {@link #isValid()} is the complete
 * readiness gate: it re-derives the decision hash and the approval hash from their canonical bytes and proves
 * the exact decision-hash binding, the decision/approval outcome equality and the complete PRODUCT/DATA/TEST
 * quorum. The readiness projection refuses to activate unless {@code isValid()} is true, so tampered lookup
 * evidence that a host port returns can never become {@code ACTIVE}.
 */
public record GateApprovedDecision(
        String capabilityId,
        GateDecision decision,
        GateApprovalRecord approval
) {

    /** The bounded capability-identifier grammar, which admits the {@code -} in {@code segment.rule-based}. */
    static final Pattern CAPABILITY_ID = Pattern.compile("^[a-z][a-z0-9.-]{0,127}$");

    public GateApprovedDecision {
        if (capabilityId == null || !CAPABILITY_ID.matcher(capabilityId).matches()) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approved decision requires a bounded capability id");
        }
        if (decision == null || approval == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approved decision requires its decision and approval");
        }
        if (!approval.decisionHash().equals(decision.decisionHash())) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH,
                    "An approval must name the exact decision hash it approves");
        }
    }

    /**
     * True only when the decision and its approval recompute to their declared hashes, agree on the verdict,
     * name the same decision hash and carry a complete owner quorum.
     *
     * <p>Every condition is re-derived from the evidence, so a forged decision hash, a tampered approval hash,
     * a conflicting outcome or an incomplete quorum is detected here rather than trusted.
     */
    public boolean isValid() {
        if (decision.decisionHash() == null || !decision.decisionHash().equals(decision.recomputeHash())) {
            return false;
        }
        if (approval.approvalHash() == null || !approval.approvalHash().equals(approval.recomputeHash())) {
            return false;
        }
        if (!approval.decisionHash().equals(decision.decisionHash())) {
            return false;
        }
        if (approval.outcome() != decision.outcome()) {
            return false;
        }
        return approval.hasCompleteQuorum();
    }
}