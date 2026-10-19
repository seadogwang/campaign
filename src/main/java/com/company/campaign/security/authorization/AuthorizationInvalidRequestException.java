package com.company.campaign.security.authorization;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/** Outward failure for an authorization request that is structurally invalid. */
public final class AuthorizationInvalidRequestException extends ContractException {

    private static final long serialVersionUID = 1L;

    public AuthorizationInvalidRequestException() {
        super(ContractErrorCodes.AUTHORIZATION_INVALID_REQUEST, "Authorization request is invalid", Map.of());
    }
}