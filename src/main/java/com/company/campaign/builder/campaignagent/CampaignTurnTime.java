package com.company.campaign.builder.campaignagent;

import java.time.Instant;
import java.time.ZoneId;

import java.util.Objects;

/** The immutable host-supplied time snapshot used to ground one Campaign turn. */
public record CampaignTurnTime(Instant instant, ZoneId zone) {

    public CampaignTurnTime {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
    }
}
