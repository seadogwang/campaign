package com.company.campaign.dev.campaignagent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A bounded startup failure of the opt-in local Campaign Agent development host.
 *
 * <p>It carries one stable machine code and a value-free message. It never echoes a credential, a base URL, a
 * model name, an identity value or a raw configuration value.
 */
public final class DevelopmentCampaignAgentConfigurationException extends ContractException {

    private static final long serialVersionUID = 1L;

    public DevelopmentCampaignAgentConfigurationException(String code, String message) {
        super(code, message, Map.of());
    }

    public DevelopmentCampaignAgentConfigurationException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }
}