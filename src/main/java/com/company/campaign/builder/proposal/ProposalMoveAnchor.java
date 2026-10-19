package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The one stable relative anchor of a MOVE operation.
 *
 * <p>Relocation is always expressed relative to another stable identity — {@code before} or {@code after}
 * that object — never as an array index, so the meaning of a move cannot depend on an index that shifts as
 * unrelated objects are added or deleted. Exactly one anchor is present.
 *
 * <p>The anchor value is a single canonical identifier that may name either form of identity. It is resolved
 * by the compiler against the proposal's declared {@code clientRef}s and the trusted Current campaign's
 * {@code logicalId}s; because a {@code clientRef} may never collide with a current {@code logicalId}, that
 * resolution is unambiguous, and an anchor that names neither fails closed.
 */
public record ProposalMoveAnchor(MoveAnchorKind kind, String target) {

    private static final Pattern FORMAT = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ProposalMoveAnchor {
        Objects.requireNonNull(kind, "kind");
        if (target == null || !FORMAT.matcher(target).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_ANCHOR_INVALID);
        }
    }

    @Override
    public String toString() {
        return "ProposalMoveAnchor[" + kind + " " + target + ']';
    }
}