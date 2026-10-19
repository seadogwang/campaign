package com.company.campaign.security.context;

import java.util.Objects;
import java.util.Set;

/**
 * Authoritative tenant/program membership for one verified user.
 *
 * <p>The membership is resolved server-side from the identity and the tenant directory. It owns the only
 * values a security decision may trust for user, tenant, program and DataScope, plus an immutable permission
 * set. Nothing here is accepted from a model-controlled payload.
 *
 * <p>{@link #permissions()} is defensively frozen, so a decision made against a membership cannot be
 * invalidated by mutating the set afterwards.
 */
public record PrincipalMembership(
        String membershipId,
        String userId,
        String tenantId,
        String programId,
        String dataScopeId,
        MembershipStatus status,
        Set<CampaignPermission> permissions
) {
    public PrincipalMembership {
        membershipId = requireText(membershipId, "membershipId");
        userId = requireText(userId, "userId");
        tenantId = requireText(tenantId, "tenantId");
        programId = requireText(programId, "programId");
        dataScopeId = requireText(dataScopeId, "dataScopeId");
        Objects.requireNonNull(status, "status");
        permissions = CampaignPermissions.immutableCopy(permissions);
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public boolean hasPermission(CampaignPermission permission) {
        return permission != null && permissions.contains(permission);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}