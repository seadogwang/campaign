package com.company.campaign.security.context;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Raised when a value claims to be a campaign permission but is not one of the closed wire names. */
public final class UnknownPermissionException extends ContractException {

    private static final long serialVersionUID = 1L;

    public UnknownPermissionException(String wireName) {
        super(
                ContractErrorCodes.PERMISSION_UNKNOWN,
                "Unknown campaign permission",
                Map.of("permission", String.valueOf(wireName)));
    }
}