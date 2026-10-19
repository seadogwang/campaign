package com.company.campaign.security.authorization;

import java.util.Objects;

/**
 * Reference to a tenant/program-owned resource.
 *
 * <p>The reference is resolved server-side (from storage or a catalog), not read from a Tool payload. It
 * carries the ownership the authorization service compares with the trusted context; a caller cannot omit
 * or rewrite the owner to escape the cross-tenant check.
 *
 * <p>{@link #resourceId()} is an opaque, non-sensitive identifier. Tests, logs and audit records must not
 * treat it as a place to put member samples or other restricted data.
 */
public record OwnedResourceRef(ResourceKind kind, String resourceId, String tenantId, String programId) {

    public OwnedResourceRef {
        Objects.requireNonNull(kind, "kind");
        resourceId = requireText(resourceId, "resourceId");
        tenantId = requireText(tenantId, "tenantId");
        programId = requireText(programId, "programId");
    }

    public static OwnedResourceRef of(ResourceKind kind, String resourceId, String tenantId, String programId) {
        return new OwnedResourceRef(kind, resourceId, tenantId, programId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}