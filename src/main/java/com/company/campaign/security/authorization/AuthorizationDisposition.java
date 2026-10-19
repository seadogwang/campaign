package com.company.campaign.security.authorization;

/**
 * Outward outcome of an authorization decision.
 *
 * <p>{@link #NOT_FOUND} is deliberately shared by an absent resource and a resource owned by another tenant
 * or program: the caller must not be able to tell the two apart. The internal reason for a {@code NOT_FOUND}
 * is preserved only on the {@link AuthorizationDecision} and in the safe audit event.
 */
public enum AuthorizationDisposition {

    ALLOWED,
    NOT_FOUND,
    PERMISSION_DENIED,
    DATASCOPE_DENIED,
    DATASCOPE_UNAVAILABLE,
    INVALID_REQUEST;

    public boolean isAllowed() {
        return this == ALLOWED;
    }
}