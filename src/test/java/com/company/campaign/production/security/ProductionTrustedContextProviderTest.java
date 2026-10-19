package com.company.campaign.production.security;

import com.company.campaign.security.context.VerifiedIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request-scoped trusted-context reconstruction of the production provider.
 *
 * <p>It proves the provider accepts subject, issuer and authentication instant only from the verified Spring
 * {@link Jwt}, resolves membership only from that verified identity plus the configured opaque selector, refuses
 * every missing or malformed input with the same non-disclosing exception, and ignores every identity-shaped
 * request header.
 */
class ProductionTrustedContextProviderTest {

    private final Instant now = Instant.parse("2026-10-11T00:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final ProductionSecurityProperties properties = ProductionTestFixtures.properties();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theVerifiedJwtProducesTheExactAcceptedTrustedContext() {
        bind(ProductionTestFixtures.jwt(now));
        ProductionTrustedContextProvider provider = provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>()));

        assertThat(provider.verifiedIdentity())
                .isEqualTo(new VerifiedIdentity(ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER, now));
        assertThat(provider.verifiedMembership().membershipId()).isEqualTo(ProductionTestFixtures.MEMBERSHIP_ID);
        assertThat(provider.verifiedMembership().userId()).isEqualTo(ProductionTestFixtures.SUBJECT);
        assertThat(provider.executionReference().tenantId()).isEqualTo(ProductionTestFixtures.TENANT);
        assertThat(provider.executionReference().programId()).isEqualTo(ProductionTestFixtures.PROGRAM);
        assertThat(provider.executionReference().dataScopeId()).isEqualTo(ProductionTestFixtures.DATA_SCOPE);
        assertThat(provider.executionReference().userId()).isEqualTo(ProductionTestFixtures.SUBJECT);
        assertThat(provider.executionReference().asOf()).isEqualTo(now);
        assertThat(provider.trustedContext().tenantId()).isEqualTo(ProductionTestFixtures.TENANT);
    }

    @Test
    void theConfiguredOpaqueSelectorIsPassedToTheDirectory() {
        List<Optional<String>> selectors = new ArrayList<>();
        bind(ProductionTestFixtures.jwt(now));
        provider(recordingRepository(Optional.of(ProductionTestFixtures.resolvedMembership(now)), selectors));

        assertThat(selectors).containsExactly(Optional.of(ProductionTestFixtures.SELECTOR_VALUE));
    }

    @Test
    void anAbsentConfiguredClaimMeansNoSelector() {
        List<Optional<String>> selectors = new ArrayList<>();
        bind(ProductionTestFixtures.jwt(now, java.util.Map.of(ProductionTestFixtures.SELECTOR_CLAIM, "")));
        provider(recordingRepository(Optional.of(ProductionTestFixtures.resolvedMembership(now)), selectors));

        assertThat(selectors).containsExactly(Optional.empty());
    }

    @Test
    void noAuthenticationFailsClosed() {
        assertThatThrownBy(() -> provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void aNonJwtPrincipalFailsClosed() {
        bind(new UsernamePasswordAuthenticationToken("user", "n/a", List.of()));

        assertThatThrownBy(() -> provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void aMissingIssuedAtClaimFailsClosed() {
        bind(ProductionTestFixtures.jwt(null));

        assertThatThrownBy(() -> provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void aMissingSubjectClaimFailsClosed() {
        Jwt withoutSubject = Jwt.withTokenValue("local-test-token")
                .header("alg", "RS256")
                .claim("iss", ProductionTestFixtures.ISSUER)
                .audience(java.util.List.of(ProductionTestFixtures.AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        bind(withoutSubject);

        assertThatThrownBy(() -> provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void anOversizedSubjectClaimFailsClosed() {
        String oversized = "s".repeat(ProductionSecurityProperties.MAX_IDENTITY_CLAIM_LENGTH + 1);
        Jwt oversizedToken = Jwt.withTokenValue("local-test-token")
                .header("alg", "RS256")
                .subject(oversized)
                .claim("iss", ProductionTestFixtures.ISSUER)
                .audience(java.util.List.of(ProductionTestFixtures.AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .build();
        bind(oversizedToken);

        assertThatThrownBy(() -> provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void aDirectoryRefusalFailsClosed() {
        bind(ProductionTestFixtures.jwt(now));

        assertThatThrownBy(() -> provider(recordingRepository(Optional.empty(), new ArrayList<>())))
                .isInstanceOf(ProductionTrustedContextException.class);
    }

    @Test
    void identityShapedRequestHeadersAreIgnored() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", "tenant-attacker");
        request.addHeader("X-User-Id", "user-attacker");
        request.addHeader("X-Data-Scope-Id", "scope-attacker");
        request.addHeader("X-Campaign-Permissions", "campaign.publish");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        bind(ProductionTestFixtures.jwt(now));

        ProductionTrustedContextProvider provider = provider(recordingRepository(
                Optional.of(ProductionTestFixtures.resolvedMembership(now)), new ArrayList<>()));

        assertThat(provider.verifiedIdentity().subjectId()).isEqualTo(ProductionTestFixtures.SUBJECT);
        assertThat(provider.trustedContext().tenantId()).isEqualTo(ProductionTestFixtures.TENANT);
        assertThat(provider.trustedContext().userId()).isEqualTo(ProductionTestFixtures.SUBJECT);
        assertThat(provider.trustedContext().dataScopeId()).isEqualTo(ProductionTestFixtures.DATA_SCOPE);
        assertThat(provider.toString()).doesNotContain("attacker").doesNotContain("tenant-production");
    }

    private ProductionTrustedContextProvider provider(ProductionMembershipRepository repository) {
        return new ProductionTrustedContextProvider(repository, properties, clock);
    }

    private static void bind(Jwt jwt) {
        // Bind a genuinely authenticated JwtAuthenticationToken, matching the authentication the resource-server
        // filter publishes for a verified bearer request; the provider must accept exactly that contract.
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt);
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static void bind(org.springframework.security.core.Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static ProductionMembershipRepository recordingRepository(
            Optional<ResolvedMembership> result, List<Optional<String>> selectors) {
        return (identity, selection) -> {
            selectors.add(selection);
            return result;
        };
    }
}