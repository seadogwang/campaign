package com.company.campaign.production.security;

/**
 * The one bounded, non-disclosing refusal raised when a production trusted context cannot be established.
 *
 * <p>It is raised when there is no valid bearer request bound to the current execution, when a required claim is
 * missing, malformed, blank or over-limit, or when the membership directory refused the lookup. The message never
 * contains a token, a claim, a subject, an issuer, a JWK Set URL, a membership identifier or decoder exception
 * text, so a refusal can never be used to probe the directory.
 */
public final class ProductionTrustedContextException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProductionTrustedContextException() {
        super("The production trusted context is unavailable for this execution.");
    }

    /** The stable machine code of this refusal. */
    public String code() {
        return ProductionSecurityErrorCodes.PRODUCTION_TRUSTED_CONTEXT_UNAVAILABLE;
    }
}