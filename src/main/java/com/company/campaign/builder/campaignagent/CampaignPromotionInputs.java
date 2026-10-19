package com.company.campaign.builder.campaignagent;

import java.math.BigDecimal;

/**
 * The host-owned typed inputs of one Promotion requirement.
 *
 * <p>The host — never the routing model — decides the exact reward reference, the bounded quantity and the
 * explicit governance ceiling: those are Tool arguments, and the routing vocabulary has no field that could
 * carry one. An absent input means the runner must ask a typed clarification rather than fabricate an
 * artifact, and this record is deliberately not an inventory check, a reservation or a balance.
 */
public record CampaignPromotionInputs(String referenceCode, Integer quantity, BigDecimal governanceCeiling) {

    /** The bounded "nothing resolved yet" input, which makes the runner ask for the reward. */
    public static CampaignPromotionInputs unresolved() {
        return new CampaignPromotionInputs(null, null, null);
    }

    /** True when the host resolved every exact input the Promotion Tools need. */
    public boolean resolved() {
        return referenceCode != null && quantity != null && governanceCeiling != null;
    }
}