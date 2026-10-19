package com.company.campaign.production.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Requires a verified token's {@code iat} claim to be present and inside the configured bounded window.
 *
 * <p>Spring's timestamp validator covers expiration and not-before; this validator additionally refuses a token
 * with no issued-at instant, an issued-at instant in the future beyond the bounded clock skew, and an issued-at
 * instant older than the configured maximum age. It reads only the claim and the injected {@link Clock}, and its
 * failure message discloses nothing about the token.
 */
public final class IssuedAtValidator implements OAuth2TokenValidator<Jwt> {

    private final Clock clock;
    private final Duration maxAge;
    private final Duration clockSkew;

    public IssuedAtValidator(Clock clock, Duration maxAge, Duration clockSkew) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Instant issuedAt = token == null ? null : token.getIssuedAt();
        if (issuedAt == null) {
            return failure();
        }
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(clockSkew)) || issuedAt.isBefore(now.minus(maxAge).minus(clockSkew))) {
            return failure();
        }
        return OAuth2TokenValidatorResult.success();
    }

    private static OAuth2TokenValidatorResult failure() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "The token issued-at claim is missing or outside the accepted window.", null));
    }
}