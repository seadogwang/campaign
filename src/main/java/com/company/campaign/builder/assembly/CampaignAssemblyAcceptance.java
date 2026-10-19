package com.company.campaign.builder.assembly;

/**
 * The bounded outcome of one workspace step.
 *
 * <p>A successful acceptance or observation carries the new revision and the new immutable snapshot; a refusal
 * carries exactly one stable code and the unchanged snapshot, so a caller can branch deterministically without
 * observing any partial state.
 */
public record CampaignAssemblyAcceptance(
        boolean accepted,
        long revision,
        String code,
        CampaignAssemblySnapshot snapshot) {

    public CampaignAssemblyAcceptance {
        if (snapshot == null) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID, "An acceptance requires its snapshot.");
        }
        if (accepted == (code != null)) {
            throw CampaignAssemblyException.invalid(
                    CampaignAssemblyErrorCodes.REQUEST_INVALID,
                    "Only a refused acceptance may carry a code.");
        }
    }

    /** A bounded diagnostic: the decision, the revision and the code, never a value. */
    @Override
    public String toString() {
        return "CampaignAssemblyAcceptance[accepted=" + accepted
                + ", revision=" + revision
                + ", code=" + (code == null ? "-" : code) + ']';
    }
}