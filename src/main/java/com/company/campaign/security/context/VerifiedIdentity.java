package com.company.campaign.security.context;

import java.time.Instant;
import java.util.Objects;

/**
 * An identity that an authentication adapter has already verified.
 *
 * <p>This value is produced only by the host authentication layer (for example an OIDC token verifier). It
 * is never read from a Tool payload, Agent envelope or free-form request map, and it carries no permission
 * of its own — authority comes from the {@link PrincipalMembership} the identity is paired with.
 *
 * <p>{@link #authenticatedAt()} is supplied by the adapter, so the security core never reads the system
 * clock and a fixture can reproduce an authentication instant exactly.
 */
public record VerifiedIdentity(String subjectId, String issuer, Instant authenticatedAt) {

    public VerifiedIdentity {
        subjectId = requireText(subjectId, "subjectId");
        issuer = requireText(issuer, "issuer");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}