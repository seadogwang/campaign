package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.journey.graph.JourneyBranchKind;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One strictly typed edge declaration of a {@link JourneyTopologyMutation}.
 *
 * <p>An edge is a bounded identity and two typed endpoint identities, each of which is addressed in exactly one
 * of the two accepted forms: an existing component {@code logicalId}, or a {@code clientRef} the same proposal
 * declares as new. The form is part of the value rather than inferred from a string, so an endpoint always
 * states whether it names something that already exists or something this proposal creates.
 *
 * <p>The branch is the closed T20 {@link JourneyBranchKind}; there is no free-form label, weight, condition or
 * ordering priority, so an edge carries no data a Flow node payload would otherwise hold. This type is the
 * authoring form only: the compiler resolves both endpoints to final {@code logicalId}s and freezes a genuine
 * T20 topology from the result.
 */
public record ProposalTopologyEdge(
        String edgeId,
        ProposalIdentity from,
        ProposalIdentity to,
        JourneyBranchKind branch) {

    private static final Pattern IDENTIFIER = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ProposalTopologyEdge {
        if (edgeId == null || !IDENTIFIER.matcher(edgeId).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(branch, "branch");
        if (from.equals(to)) {
            // An edge that begins and ends at one identity is a self-edge, not a topology.
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_SELF_EDGE);
        }
    }

    /**
     * One edge whose two endpoints are references this same proposal declares as new.
     *
     * <p>It exists so an orchestration layer can build a declared edge without naming the T20 branch type at
     * all: only the closed canonical branch spelling is accepted, and the exact enum is resolved here, inside
     * the proposal boundary that owns the closed set.
     */
    public static ProposalTopologyEdge ofDeclaredEndpoints(
            String edgeId, String fromRef, String toRef, String branchName) {
        JourneyBranchKind branch = JourneyBranchKind.fromWireName(branchName);
        if (branch == null) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_TOPOLOGY_INVALID);
        }
        return new ProposalTopologyEdge(
                edgeId, ProposalIdentity.ofClientRef(fromRef), ProposalIdentity.ofClientRef(toRef), branch);
    }

    /** The canonical wire spelling of the branch, which is the frozen T20 rank name. */
    public String branchName() {
        return branch.name();
    }

    /** A bounded diagnostic: identity forms and the branch only, never a payload value. */
    @Override
    public String toString() {
        return "ProposalTopologyEdge[edgeId=" + edgeId
                + ", from=" + (from.isLogicalId() ? "logicalId" : "clientRef")
                + ", to=" + (to.isLogicalId() ? "logicalId" : "clientRef")
                + ", branch=" + branch + ']';
    }
}