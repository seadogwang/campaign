package com.company.campaign.campaignagent;

import com.company.campaign.builder.assembly.CampaignAssemblyContext;
import com.company.campaign.conversation.CampaignConversationKey;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * The host-owned factory of the ephemeral T27 assembly context of one conversation.
 *
 * <p>The assembly context binds the bounded conversation/run identity and the frozen catalog/policy pins. It is
 * built from the trusted key and the resolved trusted context only: the request body can never name a campaign,
 * a base revision or a trusted fact.
 *
 * <p>It is neutral — it lives outside every {@code dev} package — so both the development host and the
 * production Campaign host can supply their own frozen pins without either importing the other.
 */
@FunctionalInterface
public interface CampaignAssemblyContextFactory {

    /** Builds the assembly context of one conversation from its trusted key and context. */
    CampaignAssemblyContext create(CampaignConversationKey key, TrustedExecutionContext context);
}