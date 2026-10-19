package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.compiler.CapabilityPayload;

/**
 * The host-owned typed inputs of one Schedule requirement.
 *
 * <p>The host — never the routing model — decides which exact bounded {@code schedule.basic} declaration a
 * Schedule component would use: a Tool declaration is host evidence, and the routing vocabulary has no field
 * that could carry one. An absent declaration means the runner must ask a typed clarification rather than
 * fabricate an artifact.
 *
 * <p>The declaration is not normalized here: the accepted {@code schedule.basic} pack is the only authority on
 * which forms and fields exist, and this record never claims a form, an occurrence, an execution key or
 * scheduling work.
 */
public record CampaignScheduleInputs(CapabilityPayload declaration) {

    /** The bounded "nothing resolved yet" input, which makes the runner ask for the schedule. */
    public static CampaignScheduleInputs unresolved() {
        return new CampaignScheduleInputs(null);
    }

    /** True when the host resolved an exact bounded schedule declaration for this utterance. */
    public boolean resolved() {
        return declaration != null && !declaration.isEmpty();
    }
}