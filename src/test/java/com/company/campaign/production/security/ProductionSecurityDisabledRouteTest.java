package com.company.campaign.production.security;

import com.company.campaign.CampaignApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The default application must not be locked by the mere presence of the security modules.
 *
 * <p>With the production identity boundary off, the explicit permit-all chain leaves every existing route
 * reachable: the health endpoint answers and a route that does not exist is a plain {@code 404}, never a security
 * {@code 401} or {@code 403}. No decoder, pool, migration or membership repository is created.
 */
@SpringBootTest(classes = CampaignApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProductionSecurityDisabledRouteTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void theHealthRouteStaysReachable() throws Exception {
        mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }

    @Test
    void anUnknownRouteIsNotFoundAndNotLocked() throws Exception {
        mockMvc.perform(get("/api/v1/campaign-agent/turns")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/operations/rollout")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/this-route-does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void noProductionIdentityBeanIsCreated() {
        assertThat(context.getBeanNamesForType(JwtDecoder.class)).isEmpty();
        assertThat(context.getBeanNamesForType(ProductionMembershipRepository.class)).isEmpty();
        assertThat(context.getBeanNamesForType(com.zaxxer.hikari.HikariDataSource.class)).isEmpty();
    }
}