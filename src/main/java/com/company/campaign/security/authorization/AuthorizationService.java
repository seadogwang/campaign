package com.company.campaign.security.authorization;

/**
 * Fail-closed authorization entry point.
 *
 * <p>{@link #authorize} always returns a decision, including the internal reason needed for audit;
 * {@link #requireAuthorized} converts a denial into the outward failure appropriate to it. The
 * cross-boundary and absent-resource cases share {@link ResourceNotFoundException}, so callers cannot
 * distinguish them.
 */
public interface AuthorizationService {

    AuthorizationDecision authorize(AuthorizationRequest request);

    /** Evaluates the request and throws the outward failure when access is not allowed. */
    default void requireAuthorized(AuthorizationRequest request) {
        AuthorizationDecision decision = authorize(request);
        switch (decision.disposition()) {
            case ALLOWED -> {
                // Access is permitted.
            }
            case NOT_FOUND -> throw new ResourceNotFoundException();
            case PERMISSION_DENIED ->
                    throw new PermissionDeniedException(decision.requiredPermission(), decision.resourceKind());
            case DATASCOPE_DENIED -> throw DataScopeException.denied(decision.resourceKind());
            case DATASCOPE_UNAVAILABLE -> throw DataScopeException.unavailable();
            case INVALID_REQUEST -> throw new AuthorizationInvalidRequestException();
        }
    }
}