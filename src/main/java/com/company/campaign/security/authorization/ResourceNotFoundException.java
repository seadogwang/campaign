package com.company.campaign.security.authorization;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.Map;

/**
 * Non-disclosing outward failure for a resource that is absent or outside the caller's tenant/program.
 *
 * <p>The type, code, message and details are identical in every not-found case. In particular the message
 * and details never contain the target tenant, program, resource identifier or resource kind, so a caller
 * cannot use the failure to learn whether a foreign resource exists.
 */
public final class ResourceNotFoundException extends ContractException {

    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException() {
        super(ContractErrorCodes.RESOURCE_NOT_FOUND, "Resource not found", Map.of());
    }
}