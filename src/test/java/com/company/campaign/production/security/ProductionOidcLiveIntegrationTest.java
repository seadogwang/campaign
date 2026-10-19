package com.company.campaign.production.security;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment-gated live OIDC/JWK verification of the production decoder.
 *
 * <p>It is skipped unless an operator supplies a dedicated issuer: {@code CAMPAIGN_PRODUCTION_OIDC_JWK_SET_URI},
 * {@code CAMPAIGN_PRODUCTION_OIDC_ISSUER_URI}, {@code CAMPAIGN_PRODUCTION_OIDC_AUDIENCE} and a test
 * {@code CAMPAIGN_PRODUCTION_OIDC_TOKEN}. When they are supplied it fetches the real JWK Set and verifies the real
 * token through the exact production validator chain; without them it skips honestly and no substitute runs.
 */
@EnabledIfEnvironmentVariable(
        named = ProductionOidcLiveIntegrationTest.JWK_SET_URI_VARIABLE,
        matches = ".+",
        disabledReason = "a live OIDC issuer and test token are not configured")
class ProductionOidcLiveIntegrationTest {

    static final String JWK_SET_URI_VARIABLE = "CAMPAIGN_PRODUCTION_OIDC_JWK_SET_URI";
    static final String ISSUER_VARIABLE = "CAMPAIGN_PRODUCTION_OIDC_ISSUER_URI";
    static final String AUDIENCE_VARIABLE = "CAMPAIGN_PRODUCTION_OIDC_AUDIENCE";
    static final String TOKEN_VARIABLE = "CAMPAIGN_PRODUCTION_OIDC_TOKEN";

    @Test
    void aRealIssuerTokenPassesTheExactProductionValidatorChain() {
        String jwkSetUri = text(JWK_SET_URI_VARIABLE);
        String issuer = text(ISSUER_VARIABLE);
        String audience = text(AUDIENCE_VARIABLE);
        String token = text(TOKEN_VARIABLE);
        Assumptions.assumeTrue(jwkSetUri != null && issuer != null && audience != null && token != null,
                "the live OIDC issuer, audience and test token are not all configured");

        ProductionSecurityProperties properties = new ProductionSecurityProperties();
        properties.setEnabled(true);
        properties.getJwt().setIssuerUri(issuer);
        properties.getJwt().setJwkSetUri(jwkSetUri);
        properties.getJwt().setAudience(audience);
        properties.validate();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(ProductionJwtValidators.production(properties.getJwt(), Clock.systemUTC()));

        Jwt verified = decoder.decode(token);

        assertThat(verified.getSubject()).isNotBlank();
        assertThat(verified.getClaimAsString("iss")).isEqualTo(issuer);
    }

    private static String text(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : value;
    }
}