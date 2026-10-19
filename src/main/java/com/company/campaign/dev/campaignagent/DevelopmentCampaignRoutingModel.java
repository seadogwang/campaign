package com.company.campaign.dev.campaignagent;

import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import com.company.campaign.model.StructuredCampaignRoutingModel;

import java.util.Objects;

/**
 * The development {@link com.company.campaign.builder.campaignagent.CampaignRoutingModelPort} over the shared
 * development structured transport.
 *
 * <p>Its behavior is exactly the accepted development behavior — it is now a typed subclass of the neutral
 * {@link StructuredCampaignRoutingModel} so the same thin, pinned adapter serves the development host and the
 * production Campaign host without either importing the other. It keeps its development name and its
 * development-only transport type, so the opt-in development host and its tests are source- and
 * binary-compatible.
 */
public final class DevelopmentCampaignRoutingModel extends StructuredCampaignRoutingModel {

    public DevelopmentCampaignRoutingModel(DevelopmentStructuredModelPort transport) {
        super(Objects.requireNonNull(transport, "transport"));
    }

    /** A bounded diagnostic: the pinned contract only, never a prompt or an output. */
    @Override
    public String toString() {
        return "DevelopmentCampaignRoutingModel[" + super.toString() + ']';
    }
}