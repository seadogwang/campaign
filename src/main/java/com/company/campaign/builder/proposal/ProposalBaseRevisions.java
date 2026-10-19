package com.company.campaign.builder.proposal;

import com.company.campaign.contracts.ContractPatterns;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The exact base revisions a proposal was written against.
 *
 * <p>An EDIT proposal binds to the Campaign revision it read, plus the frozen capability-catalog and policy
 * versions and content hashes. A CREATE proposal has no Campaign revision, because there is no Current
 * Campaign to be stale against. The compiler compares every field with the trusted inputs and refuses a
 * stale proposal before merge; a proposal cannot carry any other revision override — the model is closed and
 * reserved names are refused inside payloads.
 */
public record ProposalBaseRevisions(
        Long campaignRevision,
        String catalogVersion,
        ContentHash catalogHash,
        String policyVersion,
        ContentHash policyHash) {

    private static final Pattern VERSION = Pattern.compile(ContractPatterns.REFERENCE_ID);

    public ProposalBaseRevisions {
        if (campaignRevision != null && campaignRevision < 1) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
        if (catalogVersion == null || !VERSION.matcher(catalogVersion).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
        if (policyVersion == null || !VERSION.matcher(policyVersion).matches()) {
            throw ProposalException.of(ProposalErrorCodes.PROPOSAL_BASE_REVISIONS_INVALID);
        }
        Objects.requireNonNull(catalogHash, "catalogHash");
        Objects.requireNonNull(policyHash, "policyHash");
    }

    @Override
    public String toString() {
        return "ProposalBaseRevisions[campaignRevision=" + campaignRevision
                + ", catalogVersion=" + catalogVersion
                + ", policyVersion=" + policyVersion + ']';
    }
}