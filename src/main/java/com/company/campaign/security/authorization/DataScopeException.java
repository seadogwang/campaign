package com.company.campaign.security.authorization;

import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outward failure for a DataScope refusal or when no trustworthy DataScope decision was available.
 *
 * <p>Both outcomes fail closed. The unavailable variant carries no resource information at all; the denied
 * variant may name the resource kind because ownership already matched.
 */
public final class DataScopeException extends ContractException {

    private static final long serialVersionUID = 1L;

    private DataScopeException(String code, String message, Map<String, Object> details) {
        super(code, message, details);
    }

    public static DataScopeException denied(ResourceKind resourceKind) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (resourceKind != null) {
            details.put("resourceKind", resourceKind.name());
        }
        return new DataScopeException(ContractErrorCodes.DATASCOPE_DENIED, "DataScope denied", details);
    }

    public static DataScopeException unavailable() {
        return new DataScopeException(ContractErrorCodes.DATASCOPE_UNAVAILABLE, "DataScope unavailable", Map.of());
    }
}