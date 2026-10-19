package com.company.campaign.preview.api;

import com.company.campaign.CampaignApplication;
import com.company.campaign.preview.PreviewExecutionBackend;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.security.authorization.AuthorizationService;
import com.company.campaign.security.context.TrustedContextProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requirement: the normal application cannot start a usable Preview endpoint without every safe dependency.
 *
 * <p>The controller is registered only when a host supplies a {@link SegmentPreviewService}, and no Preview
 * service, trusted context provider, Metadata repository, authorization service, execution backend, sample
 * hasher or policy bean exists by default. This test starts the real application context and proves that.
 */
@SpringBootTest(classes = CampaignApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PreviewWiringBoundaryTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Test
    void theDefaultApplicationExposesNoPreviewEndpointOrPreviewComponent() throws Exception {
        assertThat(context.getBeanNamesForType(SegmentPreviewController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(SegmentPreviewService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TrustedContextProvider.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PreviewExecutionBackend.class)).isEmpty();
        assertThat(context.getBeanNamesForType(AuthorizationService.class)).isEmpty();
        assertThat(context.getBeanNamesForType(PreviewSampleHasher.class)).isEmpty();

        mvc.perform(post("/ai/segments/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}