package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.List;

/**
 * The host-only approval service for one proposed Gate decision.
 *
 * <p>It is deliberately separate from the deterministic evaluator. The evaluator produces a canonical
 * proposed decision and can never approve it; this service accepts only a decision whose recomputed canonical
 * hash matches both its own declared hash and the exact proposed-decision hash named by the command, plus an
 * explicit approval from the trusted host authorization port for each exact {@code (ownerRef, role,
 * decisionHash)} in a complete PRODUCT/DATA/TEST owner quorum.
 *
 * <p>Every owner approval must name the same proposed-decision hash and carry its own explicit host-supplied
 * time. A missing, partial, duplicated, conflicting or extra owner set is rejected before a record is
 * produced, and each owner is authorized individually: a denial, a mismatched echo, an exception or a partial
 * quorum fails the whole approval closed, producing no record.
 *
 * <p>The service reads no clock, generates no identifier and touches no model, provider, network, database,
 * repository or runtime. The owner references and decision times come from the authenticated host command.
 * Every failure is one stable bounded code and produces no approval record.
 */
public final class GateApprovalService {

    private final GateApprovalAuthorizationPort authorizationPort;

    public GateApprovalService(GateApprovalAuthorizationPort authorizationPort) {
        if (authorizationPort == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "The approval service requires its authorization port");
        }
        this.authorizationPort = authorizationPort;
    }

    /**
     * Approves one exact proposed Gate decision and returns the immutable approval evidence.
     *
     * <p>The decision's canonical bytes are re-hashed before anything else, so a forged or stale decision
     * whose recomputed hash differs from the proposal is rejected without consulting the authorization port.
     * The complete quorum is proved next, so no partial authorization is ever attempted.
     */
    public GateApprovalRecord approve(GateDecision decision, GateApprovalCommand command) {
        if (decision == null || command == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approval requires the proposed decision and command");
        }
        if (!decision.decisionHash().equals(decision.recomputeHash())
                || !command.decisionHash().equals(decision.decisionHash())) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_DECISION_HASH_MISMATCH,
                    "The approval does not name the exact proposed-decision hash");
        }
        List<GateOwnerApproval> owners = command.ownerApprovals();
        if (!GateApprovalRecord.hasCompleteQuorum(owners)) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_QUORUM_INCOMPLETE,
                    "An approval requires exactly one authorized owner for each required role");
        }
        for (GateOwnerApproval owner : owners) {
            authorizeOnce(command.decisionHash(), owner);
        }
        return GateApprovalRecord.of(command.decisionHash(), decision.outcome(), owners);
    }

    private void authorizeOnce(ContentHash decisionHash, GateOwnerApproval owner) {
        GateApprovalAuthorizationDecision authorization;
        try {
            authorization = authorizationPort.authorize(
                    owner.ownerRef(), owner.ownerRole(), decisionHash);
        } catch (RuntimeException failure) {
            throw unauthorized();
        }
        if (authorization == null
                || !authorization.allowed()
                || !owner.ownerRef().equals(authorization.ownerRef())
                || authorization.ownerRole() != owner.ownerRole()
                || !decisionHash.equals(authorization.decisionHash())) {
            throw unauthorized();
        }
    }

    private static GateException unauthorized() {
        return GateException.of(
                GateErrorCodes.APPROVAL_UNAUTHORIZED,
                "Trusted Gate authorization did not approve this exact owner, role and decision hash");
    }
}