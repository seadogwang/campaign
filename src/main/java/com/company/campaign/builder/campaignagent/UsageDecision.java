package com.company.campaign.builder.campaignagent;

/**
 * The closed, host-owned usage decision of one capability.
 *
 * <p>The model may output REQUIRED, NOT_REQUIRED or UNCERTAIN. The host validates the
 * enum strictly and rejects any unknown value. The user's explicit answer (via a
 * COMPONENT_DECISION clarification) takes precedence over subsequent model guesses.
 */
public enum UsageDecision {

    /** This capability is required for the Campaign. */
    REQUIRED,

    /** This capability is not required for the Campaign. */
    NOT_REQUIRED,

    /**
     * The model cannot determine whether this capability is required.
     *
     * <p>An UNCERTAIN decision produces a COMPONENT_DECISION clarification asking the
     * user to choose. It must not carry resource bindings.
     */
    UNCERTAIN;

    /** The exact stable machine code. */
    public String code() {
        return name();
    }

    /**
     * The declared decision whose exact code equals the argument, or {@code null} when none does.
     */
    public static UsageDecision fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (UsageDecision decision : values()) {
            if (decision.name().equals(code)) {
                return decision;
            }
        }
        return null;
    }

    /** True when resource bindings are forbidden for this decision. */
    public boolean forbidsResources() {
        return this == NOT_REQUIRED || this == UNCERTAIN;
    }
}