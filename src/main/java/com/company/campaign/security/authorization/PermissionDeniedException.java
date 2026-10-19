package com.company.campaign.security.authorization;

import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Outward failure for a same-boundary principal that lacks a required permission.
 *
 * <p>This failure is only produced after tenant and program ownership already matched, so naming the
 * resource kind and required permission does not disclose a foreign resource. Neither tenant nor program
 * identifier is ever included.
 */
public final class PermissionDeniedException extends ContractException {

    private static final long serialVersionUID = 1L;

    public PermissionDeniedException(CampaignPermission requiredPermission, ResourceKind resourceKind) {
        super(ContractErrorCodes.PERMISSION_DENIED, "Permission denied", details(requiredPermission, resourceKind));
    }

    private static Map<String, Object> details(CampaignPermission requiredPermission, ResourceKind resourceKind) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (requiredPermission != null) {
            details.put("requiredPermission", requiredPermission.wireName());
        }
        if (resourceKind != null) {
            details.put("resourceKind", resourceKind.name());
        }
        return details;
    }
}