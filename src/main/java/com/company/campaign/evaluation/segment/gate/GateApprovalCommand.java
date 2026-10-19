package com.company.campaign.evaluation.segment.gate;

import com.company.campaign.shared.canonical.ContentHash;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One host-only approval command for one exact proposed-decision hash.
 *
 * <p>The command names the exact proposed-decision hash and the complete set of accountable owner approvals
 * for that hash. Each owner approval carries an opaque owner reference, its role and the explicit host-supplied
 * time. The command carries no report payload, no utterance, no member identifier and no trusted
 * tenant/user/DataScope fact; it is built only by the authenticated host, which is also the only caller of the
 * approval service.
 *
 * <p>Structural malformation is rejected at construction — a null owner, a duplicated role, a duplicated owner
 * reference or more than one approval per role — so a caller cannot smuggle conflicting or extra owner
 * evidence into a command. The owner approvals are canonicalized into stable role order here, so two commands
 * that differ only in the order the caller listed the same owners produce identical bytes. The command may
 * still carry an incomplete quorum; {@link GateApprovalService} rejects that with a bounded code before any
 * approval is produced.
 */
public record GateApprovalCommand(
        ContentHash decisionHash,
        List<GateOwnerApproval> ownerApprovals
) {

    public GateApprovalCommand {
        if (decisionHash == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approval command must name the proposed decision hash");
        }
        if (ownerApprovals == null) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID, "An approval command must declare its owner approvals");
        }
        if (ownerApprovals.size() > GateOwnerRole.values().length) {
            throw GateException.of(
                    GateErrorCodes.APPROVAL_INVALID,
                    "An approval command must not carry extra owner evidence beyond the required roles");
        }
        List<GateOwnerApproval> validated = new ArrayList<>(ownerApprovals.size());
        Set<GateOwnerRole> roles = new LinkedHashSet<>();
        Set<String> owners = new LinkedHashSet<>();
        for (GateOwnerApproval owner : ownerApprovals) {
            if (owner == null) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID, "An approval command contains a null owner approval");
            }
            if (!roles.add(owner.ownerRole())) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID,
                        "An approval command names the same owner role more than once");
            }
            if (!owners.add(owner.ownerRef())) {
                throw GateException.of(
                        GateErrorCodes.APPROVAL_INVALID,
                        "One owner reference cannot hold two different owner roles");
            }
            validated.add(owner);
        }
        validated.sort((left, right) -> left.ownerRole().compareTo(right.ownerRole()));
        ownerApprovals = List.copyOf(validated);
    }
}