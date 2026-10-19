package com.company.campaign.security.authorization;

import com.company.campaign.security.context.CampaignPermission;

import java.util.Objects;

/**
 * Result of one authorization evaluation.
 *
 * <p>The decision carries the outward disposition plus the internal reason, resource kind and required
 * permission so the caller can audit why it happened. Only {@link #disposition()} and the derived public
 * exception may leave the security boundary; the internal fields must not be copied into a response that a
 * caller could use to probe for foreign resources.
 */
public record AuthorizationDecision(
        AuthorizationDisposition disposition,
        AuthorizationReason reason,
        ResourceKind resourceKind,
        CampaignPermission requiredPermission
) {
    public AuthorizationDecision {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(reason, "reason");
    }

    public boolean allowed() {
        return disposition.isAllowed();
    }

    public static AuthorizationDecision allow(ResourceKind resourceKind, CampaignPermission requiredPermission) {
        return new AuthorizationDecision(
                AuthorizationDisposition.ALLOWED, AuthorizationReason.ALLOWED, resourceKind, requiredPermission);
    }

    public static AuthorizationDecision deny(
            AuthorizationDisposition disposition,
            AuthorizationReason reason,
            ResourceKind resourceKind,
            CampaignPermission requiredPermission) {
        return new AuthorizationDecision(disposition, reason, resourceKind, requiredPermission);
    }
}