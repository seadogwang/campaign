package com.company.campaign.production.security;

/**
 * The stable machine codes of the default-off production identity boundary.
 *
 * <p>They are a separate, additive vocabulary from the readiness, conversation and Preview codes: enabling the
 * production identity boundary is host configuration, and a missing issuer must be distinguishable from a
 * readiness probe failure, a conversation store failure and a Preview failure. No existing code is renamed,
 * removed or reinterpreted.
 *
 * <p>Every code is bounded and non-disclosing: it never contains an issuer URL, a JWK Set URL, a credential, a
 * subject, a membership identifier, a claimed permission or a driver message.
 */
public final class ProductionSecurityErrorCodes {

    /** The production host is enabled but a required setting is absent, out of bounds or malformed. */
    public static final String PRODUCTION_SECURITY_CONFIGURATION_INVALID =
            "PRODUCTION_SECURITY_CONFIGURATION_INVALID";

    /** The development Campaign Agent host and the production identity boundary were both enabled. */
    public static final String PRODUCTION_DEVELOPMENT_HOST_CONFLICT =
            "PRODUCTION_DEVELOPMENT_HOST_CONFLICT";

    /** The configured JWK Set or decoder could not be established, and no host decoder was supplied. */
    public static final String PRODUCTION_JWK_SET_UNAVAILABLE =
            "PRODUCTION_JWK_SET_UNAVAILABLE";

    /** The bounded PostgreSQL membership pool or its ordered migration run could not be established. */
    public static final String PRODUCTION_MEMBERSHIP_POOL_UNAVAILABLE =
            "PRODUCTION_MEMBERSHIP_POOL_UNAVAILABLE";

    /** Host mode was selected but exactly one host membership repository was not supplied. */
    public static final String PRODUCTION_MEMBERSHIP_REPOSITORY_REQUIRED =
            "PRODUCTION_MEMBERSHIP_REPOSITORY_REQUIRED";

    /** More than one host membership repository was supplied, so the wiring is ambiguous. */
    public static final String PRODUCTION_MEMBERSHIP_REPOSITORY_AMBIGUOUS =
            "PRODUCTION_MEMBERSHIP_REPOSITORY_AMBIGUOUS";

    /** A membership lookup failed at the database boundary; the cause is never disclosed. */
    public static final String PRODUCTION_MEMBERSHIP_UNAVAILABLE =
            "PRODUCTION_MEMBERSHIP_UNAVAILABLE";

    /** No valid bearer request is bound to the current execution, or the trusted context is refused. */
    public static final String PRODUCTION_TRUSTED_CONTEXT_UNAVAILABLE =
            "PRODUCTION_TRUSTED_CONTEXT_UNAVAILABLE";

    private ProductionSecurityErrorCodes() {
    }
}