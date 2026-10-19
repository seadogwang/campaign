package com.company.campaign.security.authorization;

/**
 * Stable internal reason for an authorization decision.
 *
 * <p>Reasons are recorded on the decision and in the safe audit event; they are not part of the public
 * failure for a cross-boundary denial, which must stay indistinguishable from a missing resource.
 */
public enum AuthorizationReason {

    ALLOWED("AUTHORIZATION_ALLOWED"),
    CROSS_TENANT_RESOURCE("CROSS_TENANT_RESOURCE"),
    CROSS_PROGRAM_RESOURCE("CROSS_PROGRAM_RESOURCE"),
    UNKNOWN_RESOURCE("UNKNOWN_RESOURCE"),
    MISSING_PERMISSION("MISSING_PERMISSION"),
    PREVIEW_SAMPLE_MISSING_PERMISSION("PREVIEW_SAMPLE_MISSING_PERMISSION"),
    DATASCOPE_DENIED("DATASCOPE_DENIED"),
    DATASCOPE_UNAVAILABLE("DATASCOPE_UNAVAILABLE"),
    DATASCOPE_POLICY_UNAVAILABLE("DATASCOPE_POLICY_UNAVAILABLE"),
    INVALID_REQUEST("INVALID_REQUEST");

    private final String wireName;

    AuthorizationReason(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}