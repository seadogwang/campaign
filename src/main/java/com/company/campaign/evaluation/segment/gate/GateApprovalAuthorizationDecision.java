package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.ContentHash;

/**
 * The explicit answer of the trusted Gate-authorization port for one exact owner/role/decision-hash request.
 *
 * <p>The decision echoes back the owner reference, role and decision hash it approved, so the service can
 * prove the port answered the exact question it asked. An approval for a different owner, role or decision
 * hash is treated as a denial: a port that returns a generic "allowed" cannot silently widen the request. A
 * decision is a value, not a mutable flag, so it cannot be flipped after the port returns it.
 */
public record GateApprovalAuthorizationDecision(
        boolean allowed,
        String ownerRef,
        GateOwnerRole ownerRole,
        ContentHash decisionHash
) {

    public GateApprovalAuthorizationDecision {
        if (ownerRef == null || ownerRole == null || decisionHash == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_UNAUTHORIZED,
                    "A Gate authorization decision must name the exact owner, role and decision hash");
        }
    }

    /** An explicit approval of the exact owner, role and decision hash. */
    public static GateApprovalAuthorizationDecision allow(
            String ownerRef, GateOwnerRole ownerRole, ContentHash decisionHash) {
        return new GateApprovalAuthorizationDecision(true, ownerRef, ownerRole, decisionHash);
    }

    /** An explicit denial. The echoed identity is still required so the answer is unambiguous. */
    public static GateApprovalAuthorizationDecision deny(
            String ownerRef, GateOwnerRole ownerRole, ContentHash decisionHash) {
        return new GateApprovalAuthorizationDecision(false, ownerRef, ownerRole, decisionHash);
    }
}