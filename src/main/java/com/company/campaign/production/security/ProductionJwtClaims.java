package com.company.campaign.production.security;

import com.company.campaign.security.context.VerifiedIdentity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads the only three facts the production identity boundary accepts from a verified
 * {@link Jwt}: the subject, the issuer and the authentication instant.
 *
 * <p>The subject and issuer must be present, non-blank and within {@link
 * ProductionSecurityProperties#MAX_IDENTITY_CLAIM_LENGTH}; the instant comes from {@code iat} and must be present
 * and inside the configured issued-at window. Anything missing, blank, malformed or over-limit raises the bounded
 * {@link ProductionTrustedContextException}, so a malformed token fails closed. No request header, body field,
 * query parameter, role or scope is ever consulted.
 */
final class ProductionJwtClaims {

    private ProductionJwtClaims() {
    }

    /** The verified identity of one already-verified token, or a bounded refusal. */
    static VerifiedIdentity verifiedIdentity(Jwt jwt, ProductionSecurityProperties properties, Clock clock) {
        if (jwt == null || properties == null || clock == null) {
            throw new ProductionTrustedContextException();
        }
        String subject = requireClaim(jwt.getSubject());
        String issuer = requireClaim(jwt.getClaimAsString("iss"));
        Instant issuedAt = jwt.getIssuedAt();
        if (issuedAt == null) {
            throw new ProductionTrustedContextException();
        }
        Instant now = clock.instant();
        Duration skew = properties.getJwt().getClockSkew();
        Duration maxAge = properties.getJwt().getMaxIssuedAtAge();
        if (issuedAt.isAfter(now.plus(skew)) || issuedAt.isBefore(now.minus(maxAge).minus(skew))) {
            throw new ProductionTrustedContextException();
        }
        return new VerifiedIdentity(subject, issuer, issuedAt);
    }

    /**
     * The opaque selector from the configured claim, if any.
     *
     * <p>Only the claim <em>name</em> is configuration; the value is treated strictly as an opaque selector. A
     * configured claim that is absent or blank yields no selector, and a non-text or over-limit value fails
     * closed.
     */
    static Optional<String> selectionValue(Jwt jwt, String claimName) {
        if (jwt == null || claimName == null || claimName.isBlank()) {
            return Optional.empty();
        }
        Object raw = jwt.getClaims().get(claimName);
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String value)) {
            throw new ProductionTrustedContextException();
        }
        if (value.isBlank()) {
            return Optional.empty();
        }
        if (value.length() > ProductionSecurityProperties.MAX_SELECTOR_CLAIM_LENGTH) {
            throw new ProductionTrustedContextException();
        }
        return Optional.of(value);
    }

    private static String requireClaim(String value) {
        if (value == null || value.isBlank()
                || value.length() > ProductionSecurityProperties.MAX_IDENTITY_CLAIM_LENGTH) {
            throw new ProductionTrustedContextException();
        }
        return value;
    }
}