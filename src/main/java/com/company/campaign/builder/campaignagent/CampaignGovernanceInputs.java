package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.compiler.CapabilityPayload;

/**
 * The host-owned typed inputs of one Governance requirement.
 *
 * <p>The host — never the routing model — decides which exact bounded {@code governance.limit} declaration a
 * Governance component would use: a Tool declaration is host evidence, and the routing vocabulary has no field
 * that could carry one. An absent declaration means the runner must ask a typed clarification rather than
 * fabricate an artifact.
 *
 * <p>The declaration is not normalized here: the accepted {@code governance.limit} pack is the only authority on
 * the exact {@code maxRecipients}, {@code maxRewardGrants}, {@code maxRewardFaceValue} and canonical currency
 * fields, and this record never claims a reservation, a balance or a Provider fact.
 */
public record CampaignGovernanceInputs(CapabilityPayload declaration) {

    /** The bounded "nothing resolved yet" input, which makes the runner ask for the governance limits. */
    public static CampaignGovernanceInputs unresolved() {
        return new CampaignGovernanceInputs(null);
    }

    /** True when the host resolved an exact bounded governance declaration for this utterance. */
    public boolean resolved() {
        return declaration != null && !declaration.isEmpty();
    }
}