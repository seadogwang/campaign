package com.company.campaign.builder.campaignagent;

/**
 * The one typed runner of a Skill of the closed Campaign Skill vocabulary.
 *
 * <p>A runner is handed a {@link CampaignSkillCommand} and nothing else: no routing output, no other Skill's
 * artifact and no mutable workspace. It may not be registered or replaced by a caller — the router owns its
 * fixed runner set, and every runner of this build is a server-owned class.
 */
@FunctionalInterface
public interface CampaignSkillRunner {

    /** Runs one Skill and returns its closed typed outcome. */
    CampaignSkillOutcome run(CampaignSkillCommand command);
}