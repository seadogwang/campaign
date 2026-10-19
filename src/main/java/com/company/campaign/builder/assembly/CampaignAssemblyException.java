package com.company.campaign.builder.assembly;

import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * One stable, non-disclosing failure of the Campaign assembly boundary.
 *
 * <p>The message and details never contain a rejected Tool identifier, payload value, template body, reward
 * secret, Provider fact, credential or implementation exception message.
 */
public final class CampaignAssemblyException extends ContractException {

    private static final long serialVersionUID = 1L;

    public CampaignAssemblyException(String code, String message) {
        super(code, message, Map.of());
    }

    /** Builds one bounded assembly rejection. */
    public static CampaignAssemblyException invalid(String code, String message) {
        return new CampaignAssemblyException(code, message);
    }
}