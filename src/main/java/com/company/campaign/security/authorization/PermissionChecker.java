package com.company.campaign.security.authorization;

import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;

import java.util.Collection;
import java.util.Objects;

/**
 * Small permission-check helpers over the trusted context.
 *
 * <p>A null context holds nothing, so every helper fails closed. Checks read the immutable permission set
 * resolved into the context; they never accept a permission list from the caller.
 */
public final class PermissionChecker {

    private PermissionChecker() {
    }

    public static boolean has(TrustedExecutionContext context, CampaignPermission permission) {
        return context != null && context.hasPermission(permission);
    }

    public static boolean hasAll(TrustedExecutionContext context, Collection<CampaignPermission> permissions) {
        return context != null && permissions != null && context.permissions().containsAll(permissions);
    }

    /** Throws {@link PermissionDeniedException} naming the first missing permission, if any. */
    public static void requireAll(TrustedExecutionContext context, Collection<CampaignPermission> permissions) {
        Objects.requireNonNull(permissions, "permissions");
        if (hasAll(context, permissions)) {
            return;
        }
        CampaignPermission missing = permissions.stream()
                .filter(permission -> !has(context, permission))
                .findFirst()
                .orElse(null);
        throw new PermissionDeniedException(missing, null);
    }
}