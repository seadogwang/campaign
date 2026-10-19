package com.company.campaign.production.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Clock;
import java.util.List;

/**
 * Builds the one validator chain every production token must satisfy.
 *
 * <p>It composes Spring/Nimbus primitives rather than cryptography of our own: the exact issuer is checked by
 * {@link JwtIssuerValidator}, expiration and not-before by {@link JwtTimestampValidator} with the configured
 * bounded skew, the exact audience, the issued-at window and the asymmetric algorithm allowlist by the two small
 * validators in this package. Signature verification itself is performed by the injected
 * {@link org.springframework.security.oauth2.jwt.NimbusJwtDecoder}.
 */
final class ProductionJwtValidators {

    private ProductionJwtValidators() {
    }

    /** The complete validator chain for one validated JWT configuration. */
    static OAuth2TokenValidator<Jwt> production(ProductionSecurityProperties.Jwt properties, Clock clock) {
        // Spring's timestamp validator defaults to the ambient clock; give it the one injected clock so
        // expiration and not-before are evaluated against exactly the same instant as the issued-at window.
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.getClockSkew());
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.getIssuerUri()),
                new AudienceValidator(properties.getAudience()),
                timestamps,
                new IssuedAtValidator(clock, properties.getMaxIssuedAtAge(), properties.getClockSkew()),
                new AllowedAlgorithmsValidator(properties.getAllowedAlgorithms()));
    }

    /** Requires the exact configured audience to be one of the verified token's audiences. */
    static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

        private final String audience;

        AudienceValidator(String audience) {
            this.audience = audience;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            List<String> audiences = token == null ? null : token.getAudience();
            if (audiences != null && audiences.contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token", "The token audience is not accepted.", null));
        }
    }
}