package com.company.campaign.builder.campaignagent;

/**
 * The closed, host-owned source of one capability usage decision.
 *
 * <p>MODEL decisions come from the routing model's capability judgment. USER decisions
 * come from explicit clarification answers. SYSTEM decisions are host-derived (e.g.
 * inferred from a Campaign type, or a deterministic rule).
 *
 * <p>A USER decision takes precedence: once the user explicitly answers a
 * COMPONENT_DECISION clarification, subsequent model outputs may not override it
 * unless the user explicitly modifies the Campaign.
 */
public enum DecisionSource {

    /** The routing model produced this decision. */
    MODEL,

    /** The user answered a COMPONENT_DECISION clarification. */
    USER,

    /** A host-owned deterministic rule produced this decision. */
    SYSTEM;

    /** The exact stable machine code. */
    public String code() {
        return name();
    }

    public static DecisionSource fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (DecisionSource source : values()) {
            if (source.name().equals(code)) {
                return source;
            }
        }
        return null;
    }
}