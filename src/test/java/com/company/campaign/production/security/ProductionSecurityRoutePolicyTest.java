package com.company.campaign.production.security;

import com.company.campaign.CampaignApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The route policy, stateless behaviour and bounded failure envelopes of the enabled production boundary.
 *
 * <p>It runs the real application context with the production identity boundary enabled over a locally signed
 * token and a host-supplied membership repository, so the evidence is the actual servlet filter chain — not a
 * stand-alone chain builder. The locally signed token is contract evidence only.
 */
@SpringBootTest(
        classes = {CampaignApplication.class, ProductionSecurityRoutePolicyTest.HostBeans.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "campaign.production.enabled=true",
                "campaign.production.jwt.issuer-uri=https://production.issuer.test",
                "campaign.production.jwt.audience=campaign-production-api",
                "campaign.production.membership.mode=HOST",
                "campaign.production.membership.select-claim=membership"})
@AutoConfigureMockMvc
class ProductionSecurityRoutePolicyTest {

    private static final String CAMPAIGN_AGENT_ROUTE = "/api/v1/campaign-agent/turns";
    private static final String OPERATIONS_ROUTE = "/api/v1/operations/rollout";

    @Autowired
    private MockMvc mockMvc;

    @TestConfiguration
    static class HostBeans {

        @Bean
        NimbusJwtDecoder campaignProductionHostJwtDecoder() {
            return ProductionTestFixtures.hostDecoder();
        }

        @Bean("hostMembershipRepository")
        ProductionMembershipRepository hostMembershipRepository() {
            return (identity, selection) -> Optional.of(ProductionTestFixtures.resolvedMembership(Instant.now()));
        }
    }

    @Test
    void healthStaysPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void anUnauthenticatedCampaignAgentRequestIsRefusedWithABoundedJsonEnvelope() throws Exception {
        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.error").value(BoundedJsonAuthenticationEntryPoint.CODE));
    }

    @Test
    void anUnauthenticatedOperationsRequestIsRefused() throws Exception {
        mockMvc.perform(get(OPERATIONS_ROUTE))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(BoundedJsonAuthenticationEntryPoint.CODE));
    }

    @Test
    void aVerifiedBearerTokenReachesTheRoute() throws Exception {
        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE)
                        .header("Authorization", "Bearer " + validToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aBadSignatureIsRefused() throws Exception {
        String forged = ProductionTestFixtures.token(
                ProductionTestFixtures.otherKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, Instant.now(), Instant.now().plusSeconds(600));

        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE).header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(BoundedJsonAuthenticationEntryPoint.CODE));
    }

    @Test
    void aWrongIssuerIsRefused() throws Exception {
        String forged = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, "https://other.issuer.test",
                ProductionTestFixtures.AUDIENCE, Instant.now(), Instant.now().plusSeconds(600));

        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE).header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aWrongAudienceIsRefused() throws Exception {
        String forged = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                "another-audience", Instant.now(), Instant.now().plusSeconds(600));

        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE).header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        String expired = ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, Instant.now().minusSeconds(3_600), Instant.now().minusSeconds(600));

        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE).header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void identityShapedHeadersDoNotReplaceTheBearerToken() throws Exception {
        mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE)
                        .header("X-Tenant-Id", "tenant-attacker")
                        .header("X-User-Id", "user-attacker"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theFailureResponseDisclosesNoTokenClaimOrDecoderText() throws Exception {
        String body = mockMvc.perform(get(CAMPAIGN_AGENT_ROUTE))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("Bearer")
                .doesNotContain("token")
                .doesNotContain("iss")
                .doesNotContain("aud")
                .doesNotContain("Exception");
    }

    @Test
    void theChainIsStatelessAndCreatesNoSession() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void aPostToTheProtectedRouteWithoutATokenIsRefused() throws Exception {
        mockMvc.perform(post(CAMPAIGN_AGENT_ROUTE))
                .andExpect(status().isUnauthorized());
    }

    private static String validToken() {
        return ProductionTestFixtures.token(
                ProductionTestFixtures.primaryKey(), ProductionTestFixtures.SUBJECT, ProductionTestFixtures.ISSUER,
                ProductionTestFixtures.AUDIENCE, Instant.now(), Instant.now().plusSeconds(600));
    }
}