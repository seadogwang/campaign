package com.company.campaign.production.security;

import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local signed-JWT contract evidence of the production decoder.
 *
 * <p>Every token here is signed with a locally generated key and verified by a locally built Nimbus decoder:
 * this proves the accepted policy — exact issuer, exact audience, signature, expiration, not-before, bounded
 * issued-at and the asymmetric algorithm allowlist — and is <strong>not</strong> live OIDC evidence.
 */
class ProductionJwtValidationTest {

    private final Instant now = Instant.parse("2026-10-11T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ProductionSecurityProperties properties = ProductionTestFixtures.properties();

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder decoder = ProductionTestFixtures.hostDecoder();
        decoder.setJwtValidator(ProductionTestFixtures.validators(properties, clock));
        return decoder;
    }

    @Test
    void aLocallySignedTokenWithTheAcceptedClaimsIsVerified() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, now, now.plusSeconds(600));

        Jwt verified = decoder().decode(token);

        assertThat(verified.getSubject()).isEqualTo(ProductionTestFixtures.SUBJECT);
        assertThat(verified.getClaimAsString("iss")).isEqualTo(ProductionTestFixtures.ISSUER);
        assertThat(verified.getIssuedAt()).isEqualTo(now);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRejected() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.otherKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, now, now.plusSeconds(600));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aWrongIssuerIsRejected() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, "https://other.issuer.test",
                ProductionTestFixtures.AUDIENCE, now, now.plusSeconds(600));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aWrongAudienceIsRejected() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                "another-audience", now, now.plusSeconds(600));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anExpiredTokenIsRejected() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, now.minusSeconds(3_600), now.minusSeconds(600));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aNotYetValidTokenIsRejected() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(ProductionTestFixtures.SUBJECT)
                .issuer(ProductionTestFixtures.ISSUER)
                .audience(ProductionTestFixtures.AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(3_600)))
                .notBeforeTime(Date.from(now.plusSeconds(600)));
        String token = ProductionTestFixtures.sign(ProductionTestFixtures.primaryKey(), claims);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void aMissingIssuedAtIsRejected() {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(ProductionTestFixtures.SUBJECT)
                .issuer(ProductionTestFixtures.ISSUER)
                .audience(ProductionTestFixtures.AUDIENCE)
                .expirationTime(Date.from(now.plusSeconds(3_600)));
        String token = ProductionTestFixtures.sign(ProductionTestFixtures.primaryKey(), claims);

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anOversizedIssuedAtAgeIsRejected() {
        String token = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, now.minusSeconds(7_200), now.plusSeconds(3_600));

        assertThatThrownBy(() -> decoder().decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void anAlgorithmOutsideTheAllowlistIsRejectedByTheValidator() {
        Jwt token = ProductionTestFixtures.jwt(now);
        Jwt symmetric = Jwt.withTokenValue("local-test-token")
                .header("alg", "HS256")
                .subject(ProductionTestFixtures.SUBJECT)
                .claim("iss", ProductionTestFixtures.ISSUER)
                .audience(List.of(ProductionTestFixtures.AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();

        AllowedAlgorithmsValidator validator = new AllowedAlgorithmsValidator(Set.of("RS256"));
        OAuth2TokenValidatorResult result = validator.validate(symmetric);

        assertThat(result.hasErrors()).isTrue();
        assertThat(new AllowedAlgorithmsValidator(Set.of("RS256")).validate(token).hasErrors()).isFalse();
    }
}