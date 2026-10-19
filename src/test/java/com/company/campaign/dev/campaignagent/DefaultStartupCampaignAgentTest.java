package com.company.campaign.dev.campaignagent;

import com.company.campaign.CampaignApplication;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentConversationService;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T32 requirement 7: the default application startup stays inert. With the development host off — the default —
 * there is no Campaign Agent route mapping, no turn service, no conversation registry and no model client.
 */
@SpringBootTest(classes = CampaignApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class DefaultStartupCampaignAgentTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void theDefaultApplicationCreatesNoCampaignAgentBean() {
        assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CampaignAgentTurnController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CampaignConversationStore.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CampaignAgentConversationService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(CampaignAgentConversationController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(DevelopmentStructuredModelPort.class)).isEmpty();
        assertThat(context.containsBean("campaignAgentDevelopmentTurnService")).isFalse();
        assertThat(context.containsBean("campaignAgentTurnController")).isFalse();
        assertThat(context.containsBean("campaignAgentConversationController")).isFalse();
    }

    @Test
    void theDefaultApplicationMapsNoCampaignAgentRoute() {
        RequestMappingHandlerMapping mapping =
                context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class);
        boolean present = mapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPatternValues().stream())
                .anyMatch(pattern -> pattern.startsWith("/api/v1/campaign-agent"));
        assertThat(present).isFalse();
    }
}