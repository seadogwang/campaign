package com.company.campaign.builder.campaignagent;

/** Read-only host Tool that discovers business resources for one trusted tenant and program. */
@FunctionalInterface
public interface CampaignBusinessResourceTool {

    CampaignBusinessResourceCatalog discover(String tenantId, String programId);
}
