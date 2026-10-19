package com.company.campaign.dev.campaignagent;

import com.company.campaign.campaignagent.CampaignAssemblyContextFactory;

/**
 * The development view of the host-owned factory of the ephemeral T27 assembly context of one conversation.
 *
 * <p>It is the accepted development interface, now extending the neutral
 * {@link CampaignAssemblyContextFactory}, so the development host and the production Campaign host share one
 * neutral type while the development host keeps its clearly named development boundary.
 */
@FunctionalInterface
public interface DevelopmentCampaignAssemblyContextFactory extends CampaignAssemblyContextFactory {
}