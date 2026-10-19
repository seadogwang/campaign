package com.company.campaign.builder.campaignagent;

import com.company.campaign.builder.authoring.SegmentAuthoringRequest;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;

import java.util.Objects;

/**
 * One host-owned resolved-or-terminal Segment submission, paired with the frozen request it compiles under.
 *
 * <p>The pair is what the accepted {@code CampaignSegmentAdapter} consumes: the adapter re-binds the source
 * into the request and re-runs the one accepted {@code SegmentAuthoringIntegration}, so the Segment Skill, its
 * Tools, its envelope writer and the compiler remain the authoritative implementations. The routing model has
 * no field that could produce or influence any of this.
 */
public record CampaignSegmentSource(SegmentAuthoringSource source, SegmentAuthoringRequest request) {

    public CampaignSegmentSource {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");
    }
}