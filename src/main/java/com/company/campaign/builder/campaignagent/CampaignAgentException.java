package com.company.campaign.builder.campaignagent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A bounded failure of the top-level Campaign Agent.
 *
 * <p>It carries exactly one stable machine code and one bounded invariant name. It never echoes a model
 * output, a prompt, a Tool argument, a capability payload, a Provider fact, a member identifier or an
 * exception message, so a refused turn cannot become a channel for restricted data.
 */
public final class CampaignAgentException extends ContractException {

    private static final long serialVersionUID = 1L;

    /** The bounded invariant this failure was raised for; never a value. */
    private final String invariant;

    public CampaignAgentException(String code, String invariant, String message) {
        super(code, message, invariant == null ? Map.of() : Map.of("invariant", invariant));
        this.invariant = invariant;
    }

    /** The bounded invariant name, or null when the failure has no single invariant. */
    public String invariant() {
        return invariant;
    }

    /** A bounded failure carrying one stable code, its invariant and a value-free message. */
    public static CampaignAgentException invalid(String code, String invariant, String message) {
        return new CampaignAgentException(code, invariant, message);
    }

    /** A bounded failure carrying one stable code and a value-free message. */
    public static CampaignAgentException of(String code, String message) {
        return new CampaignAgentException(code, null, message);
    }
}