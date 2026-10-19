package com.company.campaign.builder.campaignagent;

/** Whether this Campaign uses any outbound communication component. */
public enum CommunicationDecision {
    UNKNOWN,
    NONE,
    DEFINED;

    /** The declared wire name, or {@code null} when the name is outside the closed vocabulary. */
    public static CommunicationDecision fromWireName(String name) {
        if (name == null) {
            return null;
        }
        for (CommunicationDecision decision : values()) {
            if (decision.name().equals(name)) {
                return decision;
            }
        }
        return null;
    }
}
