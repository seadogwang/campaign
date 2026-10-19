package com.company.campaign.security.authorization;

import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.TrustedExecutionContext;

/**
 * Authorization service that denies unless every check succeeds.
 *
 * <p>Evaluation order, all derived from host values rather than the request payload:
 *
 * <ol>
 *   <li>a structurally invalid request is denied;</li>
 *   <li>an absent resource is a non-disclosing not-found;</li>
 *   <li>a resource owned by another tenant or program is the identical non-disclosing not-found;</li>
 *   <li>the permission required by the resource kind must be held — for a Preview Sample that permission
 *       is {@code audience.preview};</li>
 *   <li>the DataScope policy must return an explicit {@link DataScopeOutcome#ALLOWED} decision, so a
 *       Preview Sample needs both the permission and an approving DataScope.</li>
 * </ol>
 *
 * <p>A missing policy, a null decision or an exception from the policy is treated as unavailable and
 * denied. No path returns allow without an explicit positive DataScope decision.
 */
public final class FailClosedAuthorizationService implements AuthorizationService {

    private final DataScopePolicy dataScopePolicy;

    /** Creates a service with no DataScope policy; every resource remains denied as unavailable. */
    public FailClosedAuthorizationService() {
        this(null);
    }

    public FailClosedAuthorizationService(DataScopePolicy dataScopePolicy) {
        this.dataScopePolicy = dataScopePolicy;
    }

    @Override
    public AuthorizationDecision authorize(AuthorizationRequest request) {
        if (request == null || request.context() == null) {
            return deny(AuthorizationDisposition.INVALID_REQUEST, AuthorizationReason.INVALID_REQUEST, null, null);
        }

        TrustedExecutionContext context = request.context();
        OwnedResourceRef resource = request.resource();
        if (resource == null || resource.kind() == null) {
            return deny(AuthorizationDisposition.NOT_FOUND, AuthorizationReason.UNKNOWN_RESOURCE, null, null);
        }

        ResourceKind kind = resource.kind();
        CampaignPermission required = kind.requiredPermission();

        if (!context.tenantId().equals(resource.tenantId())) {
            return deny(
                    AuthorizationDisposition.NOT_FOUND, AuthorizationReason.CROSS_TENANT_RESOURCE, kind, required);
        }
        if (!context.programId().equals(resource.programId())) {
            return deny(
                    AuthorizationDisposition.NOT_FOUND, AuthorizationReason.CROSS_PROGRAM_RESOURCE, kind, required);
        }
        if (!context.hasPermission(required)) {
            AuthorizationReason reason = kind == ResourceKind.PREVIEW_SAMPLE
                    ? AuthorizationReason.PREVIEW_SAMPLE_MISSING_PERMISSION
                    : AuthorizationReason.MISSING_PERMISSION;
            return deny(AuthorizationDisposition.PERMISSION_DENIED, reason, kind, required);
        }
        if (dataScopePolicy == null) {
            return deny(
                    AuthorizationDisposition.DATASCOPE_UNAVAILABLE,
                    AuthorizationReason.DATASCOPE_POLICY_UNAVAILABLE,
                    kind,
                    required);
        }

        DataScopeDecision decision;
        try {
            decision = dataScopePolicy.evaluate(new DataScopeQuery(context, kind, resource.resourceId()));
        } catch (RuntimeException exception) {
            // A policy that cannot answer must never be read as permission.
            return deny(
                    AuthorizationDisposition.DATASCOPE_UNAVAILABLE,
                    AuthorizationReason.DATASCOPE_UNAVAILABLE,
                    kind,
                    required);
        }
        if (decision == null || decision.outcome() == null) {
            return deny(
                    AuthorizationDisposition.DATASCOPE_UNAVAILABLE,
                    AuthorizationReason.DATASCOPE_UNAVAILABLE,
                    kind,
                    required);
        }

        return switch (decision.outcome()) {
            case ALLOWED -> AuthorizationDecision.allow(kind, required);
            case DENIED -> deny(
                    AuthorizationDisposition.DATASCOPE_DENIED, AuthorizationReason.DATASCOPE_DENIED, kind, required);
            case UNAVAILABLE -> deny(
                    AuthorizationDisposition.DATASCOPE_UNAVAILABLE,
                    AuthorizationReason.DATASCOPE_UNAVAILABLE,
                    kind,
                    required);
        };
    }

    private static AuthorizationDecision deny(
            AuthorizationDisposition disposition,
            AuthorizationReason reason,
            ResourceKind kind,
            CampaignPermission required) {
        return AuthorizationDecision.deny(disposition, reason, kind, required);
    }
}