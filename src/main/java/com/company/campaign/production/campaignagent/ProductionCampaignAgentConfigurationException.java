package com.company.campaign.production.campaignagent;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * A fail-closed startup failure of the production Campaign Agent host.
 *
 * <p>It carries exactly one stable {@link ProductionCampaignAgentErrorCodes} code and a value-free message: no
 * credential, URL, schema, model id, identity value, prompt or output is ever attached.
 */
public final class ProductionCampaignAgentConfigurationException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ProductionCampaignAgentConfigurationException(String code, String message) {
        super(code, message, Map.of());
    }

    public ProductionCampaignAgentConfigurationException(String code, String message, Throwable cause) {
        super(code, message, Map.of(), cause);
    }
}