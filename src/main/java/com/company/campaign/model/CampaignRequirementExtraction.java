package com.company.campaign.model;

import com.company.campaign.builder.campaignagent.CampaignRequirementDelta;

/**
 * The bounded outcome of one strict structured-model requirement extraction.
 *
 * <p>It is one closed two-way result: either the model returned a document the strict decoder accepted, in
 * which case {@link #accepted()} is true and {@link #delta()} is the coherent typed facts of the turn, or it
 * did not, in which case {@link #accepted()} is false and {@link #delta()} is the one empty delta, so a caller
 * that ignores the flag still changes no accepted fact. A rejected extraction never carries a partial fact.
 */
public record CampaignRequirementExtraction(boolean accepted, CampaignRequirementDelta delta) {

    public CampaignRequirementExtraction {
        if (delta == null) {
            delta = CampaignRequirementDelta.empty();
        }
    }

    /** The one rejected outcome: no fact was supplied because no document was accepted. */
    public static CampaignRequirementExtraction rejected() {
        return new CampaignRequirementExtraction(false, CampaignRequirementDelta.empty());
    }

    /** The one accepted outcome over the coherent typed facts of the turn. */
    public static CampaignRequirementExtraction of(CampaignRequirementDelta delta) {
        return new CampaignRequirementExtraction(true, delta);
    }

    /** A bounded diagnostic: the flag and whether the delta is empty, never a fact value. */
    @Override
    public String toString() {
        return "CampaignRequirementExtraction[accepted=" + accepted + ", empty=" + delta.isEmpty() + ']';
    }
}