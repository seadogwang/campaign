package com.company.campaign.security.authorization;

import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * A request to authorize access to one resource.
 *
 * <p>The request holds only two host-controlled values: the trusted context and the owned resource
 * reference. Permissions, DataScope and ownership are all derived inside the authorization service from
 * those two values, so a caller — and therefore a model-controlled payload — cannot supply or override the
 * tenant, program, user, permission or DataScope an authorization decision is based on.
 *
 * <p>A null resource is a valid request meaning "no such resource"; it is denied with the same
 * non-disclosing outward failure as a cross-boundary resource.
 */
public record AuthorizationRequest(TrustedExecutionContext context, OwnedResourceRef resource) {

    public static AuthorizationRequest of(TrustedExecutionContext context, OwnedResourceRef resource) {
        return new AuthorizationRequest(context, resource);
    }

    public static AuthorizationRequest of(
            TrustedExecutionContext context,
            ResourceKind kind,
            String resourceId,
            String tenantId,
            String programId) {
        return new AuthorizationRequest(context, new OwnedResourceRef(kind, resourceId, tenantId, programId));
    }
}