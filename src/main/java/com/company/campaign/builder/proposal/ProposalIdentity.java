package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractPatterns;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The identity an operation addresses, in exactly one of the two permitted forms.
 *
 * <p>An existing object is addressed by the one stable {@code logicalId} it already owns. A new object is
 * addressed by a {@code clientRef} the client chose, which the compiler — never the caller — turns into a
 * Plan-scoped {@code logicalId}. Exactly one form may be present, so a request cannot both address an
 * existing object and claim to be creating a new one, and a caller can never choose a new object's
 * {@code logicalId} for itself.
 */
public record ProposalIdentity(String logicalId, String clientRef) {

    private static final Pattern FORMAT = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ProposalIdentity {
        boolean hasLogicalId = logicalId != null;
        boolean hasClientRef = clientRef != null;
        if (hasLogicalId == hasClientRef) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        }
        if (hasLogicalId && !FORMAT.matcher(logicalId).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        }
        if (hasClientRef && !FORMAT.matcher(clientRef).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_IDENTITY_INVALID);
        }
    }

    public static ProposalIdentity ofLogicalId(String logicalId) {
        return new ProposalIdentity(logicalId, null);
    }

    public static ProposalIdentity ofClientRef(String clientRef) {
        return new ProposalIdentity(null, clientRef);
    }

    /** True when this identity addresses an existing object by its stable logical identifier. */
    public boolean isLogicalId() {
        return logicalId != null;
    }

    /** The single identifier this identity carries, whichever form it is. */
    public String value() {
        return isLogicalId() ? logicalId : clientRef;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProposalIdentity identity
                && Objects.equals(logicalId, identity.logicalId)
                && Objects.equals(clientRef, identity.clientRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalId, clientRef);
    }

    @Override
    public String toString() {
        return isLogicalId()
                ? "ProposalIdentity[logicalId=" + logicalId + ']'
                : "ProposalIdentity[clientRef=" + clientRef + ']';
    }
}