package com.company.campaign.production.security;

import com.company.campaign.contracts.LocaleTag;
import com.company.campaign.contracts.TrustedExecutionContextRef;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.security.context.MembershipStatus;
import com.company.campaign.security.context.PrincipalMembership;
import com.company.campaign.security.context.VerifiedIdentity;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Locally signed-JWT and membership fixtures for the production identity tests.
 *
 * <p>These are <strong>contract evidence only</strong>: a locally generated RSA key and a locally signed token
 * prove the accepted verification policy, never a live issuer. Nothing here is PostgreSQL or OIDC evidence.
 */
final class ProductionTestFixtures {

    static final String ISSUER = "https://production.issuer.test";
    static final String AUDIENCE = "campaign-production-api";
    static final String SUBJECT = "user-production-1";
    static final String MEMBERSHIP_ID = "membership-production-1";
    static final String TENANT = "tenant-production";
    static final String PROGRAM = "program-production";
    static final String DATA_SCOPE = "scope-production";
    static final String SNAPSHOT_ID = "core-loyalty-metadata";
    static final String SELECTOR_CLAIM = "membership";
    static final String SELECTOR_VALUE = "selector-1";
    static final String TIMEZONE = "Asia/Shanghai";
    static final String CURRENCY = "CNY";

    private static final RSAKey PRIMARY = generate("primary-key");
    private static final RSAKey OTHER = generate("other-key");

    private ProductionTestFixtures() {
    }

    static RSAKey primaryKey() {
        return PRIMARY;
    }

    static RSAKey otherKey() {
        return OTHER;
    }

    /** A Nimbus decoder over the primary local public key; the production policy is installed by the caller. */
    static NimbusJwtDecoder hostDecoder() {
        try {
            return NimbusJwtDecoder.withPublicKey(PRIMARY.toRSAPublicKey()).build();
        } catch (com.nimbusds.jose.JOSEException failure) {
            throw new IllegalStateException("the local test public key could not be exported", failure);
        }
    }

    /** A validated production JWT configuration over the local issuer and audience. */
    static ProductionSecurityProperties properties() {
        ProductionSecurityProperties properties = new ProductionSecurityProperties();
        properties.setEnabled(true);
        properties.getJwt().setIssuerUri(ISSUER);
        properties.getJwt().setAudience(AUDIENCE);
        properties.getJwt().setAllowedAlgorithms(Set.of("RS256", "ES256"));
        properties.getJwt().setClockSkew(Duration.ofSeconds(60));
        properties.getJwt().setMaxIssuedAtAge(Duration.ofHours(1));
        properties.getMembership().setMode(ProductionMembershipMode.HOST);
        properties.getMembership().setSelectClaim(SELECTOR_CLAIM);
        properties.validate();
        return properties;
    }

    /** The complete production validator chain over the validated configuration. */
    static org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> validators(
            ProductionSecurityProperties properties, Clock clock) {
        return ProductionJwtValidators.production(properties.getJwt(), clock);
    }

    /** A locally signed token with the accepted claims, valid from {@code issuedAt}. */
    static String token(RSAKey signingKey, String subject, String issuer, String audience,
            Instant issuedAt, Instant expiresAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .notBeforeTime(Date.from(issuedAt.minusSeconds(1)))
                .claim(SELECTOR_CLAIM, SELECTOR_VALUE);
        return sign(signingKey, claims);
    }

    /** A locally signed token with an arbitrary claims set. */
    static String sign(RSAKey signingKey, JWTClaimsSet.Builder claims) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception failure) {
            throw new IllegalStateException("the local test token could not be signed", failure);
        }
    }

    /** A decoded, verified-looking {@link Jwt} for the provider and validator unit tests. */
    static Jwt jwt(Instant issuedAt, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("local-test-token")
                .header("alg", "RS256")
                .subject(SUBJECT)
                .claim("iss", ISSUER)
                .audience(List.of(AUDIENCE));
        if (issuedAt != null) {
            builder.issuedAt(issuedAt);
            builder.expiresAt(issuedAt.plusSeconds(600));
        }
        extraClaims.forEach(builder::claim);
        if (!extraClaims.containsKey(SELECTOR_CLAIM)) {
            builder.claim(SELECTOR_CLAIM, SELECTOR_VALUE);
        }
        return builder.build();
    }

    static Jwt jwt(Instant issuedAt) {
        return jwt(issuedAt, Map.of());
    }

    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** The one server-owned membership the fixtures agree on, valid at {@code authenticatedAt}. */
    static ResolvedMembership resolvedMembership(Instant authenticatedAt) {
        PrincipalMembership membership = new PrincipalMembership(
                MEMBERSHIP_ID, SUBJECT, TENANT, PROGRAM, DATA_SCOPE, MembershipStatus.ACTIVE,
                Set.of(CampaignPermission.values()));
        TrustedExecutionContextRef reference = new TrustedExecutionContextRef(
                TENANT, PROGRAM, SUBJECT, DATA_SCOPE, SNAPSHOT_ID,
                LocaleTag.of(Locale.SIMPLIFIED_CHINESE), ZoneId.of(TIMEZONE), CURRENCY,
                authenticatedAt, authenticatedAt.minusSeconds(60));
        return new ResolvedMembership(membership, reference);
    }

    static VerifiedIdentity identity(Instant authenticatedAt) {
        return new VerifiedIdentity(SUBJECT, ISSUER, authenticatedAt);
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).algorithm(JWSAlgorithm.RS256).generate();
        } catch (Exception failure) {
            throw new IllegalStateException("a local test RSA key could not be generated", failure);
        }
    }
}