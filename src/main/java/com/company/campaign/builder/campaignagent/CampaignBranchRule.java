package com.company.campaign.builder.campaignagent;

/**
 * The explicit decision rule of one branched Campaign flow.
 *
 * <p>A branch is not considered configured from a yes/no decision or a free-form summary alone. The user must
 * have supplied the condition being evaluated and the outcome/action for both possible results. These values
 * remain authoring facts; a later Journey planner must still map them to capabilities supported by the host.
 */
public record CampaignBranchRule(String condition, String whenTrue, String whenFalse) {

    /** Largest accepted text for each part of a branch rule. */
    public static final int MAX_PART_LENGTH = 1024;

    public CampaignBranchRule {
        condition = required(condition, "condition");
        whenTrue = required(whenTrue, "whenTrue");
        whenFalse = required(whenFalse, "whenFalse");
    }

    private static String required(String value, String field) {
        String bounded = CampaignRequirementTexts.boundedText(value, MAX_PART_LENGTH, field);
        if (bounded == null) {
            throw new IllegalArgumentException("A Campaign branch rule requires every condition and outcome.");
        }
        return bounded;
    }
}
