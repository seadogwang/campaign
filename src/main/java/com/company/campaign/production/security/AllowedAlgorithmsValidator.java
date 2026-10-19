package com.company.campaign.production.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Set;

/**
 * Rejects a verified token whose JOSE {@code alg} header is not on the configured asymmetric allowlist.
 *
 * <p>Signature verification already restricts which key can verify the token; this validator adds the explicit
 * algorithm allowlist the production boundary requires, so a token signed with an algorithm the host did not
 * allowlist — including the symmetric and {@code none} algorithms — is refused even if a key would otherwise
 * verify it. It inspects only the verified JOSE header and discloses nothing.
 */
public final class AllowedAlgorithmsValidator implements OAuth2TokenValidator<Jwt> {

    static final String ALGORITHM_HEADER = "alg";

    private final Set<String> allowedAlgorithms;

    public AllowedAlgorithmsValidator(Set<String> allowedAlgorithms) {
        this.allowedAlgorithms = Set.copyOf(allowedAlgorithms);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object algorithm = token == null ? null : token.getHeaders().get(ALGORITHM_HEADER);
        if (algorithm instanceof String name && allowedAlgorithms.contains(name)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token", "The token signature algorithm is not allowed.", null));
    }
}