package com.company.campaign.dev.campaignagent;

import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnController;
import com.company.campaign.builder.campaignagent.http.CampaignAgentTurnService;
import com.company.campaign.builder.tool.SegmentPreviewPort;
import com.company.campaign.conversation.CampaignConversationStore;
import com.company.campaign.dev.campaignagent.testsupport.DevelopmentSegmentRealHostFixture;
import com.company.campaign.dev.segment.DevelopmentStructuredModelPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T33 requirement 6: the opt-in host resolves its Segment source with explicit precedence and fails startup
 * closed on an ambiguous or incompatible wiring, while the disabled default still creates nothing at all.
 */
class DevelopmentSegmentAgentConfigurationWiringTest {

    private static final String UTTERANCE = DevelopmentSegmentRealHostFixture.UTTERANCE;
    private static final String CONVERSATION = DevelopmentSegmentRealHostFixture.CONVERSATION;
    private static final String RUN = DevelopmentSegmentRealHostFixture.RUN;

    private static ApplicationContextRunner enabled() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentCampaignAgentConfiguration.class)
                .withPropertyValues("campaign.agent.development.enabled=true");
    }

    private static DevelopmentStructuredModelPort plainTransport() {
        return prompt -> "{}".getBytes(StandardCharsets.UTF_8);
    }

    private static SegmentPreviewPort previewPort() {
        return new DevelopmentSegmentRealHostFixture.FakePreviewPort(
                DevelopmentSegmentRealHostFixture.identity(), 4);
    }

    @Test
    void anExplicitHostSourceProviderAlwaysWinsEvenWhenAPreviewPortExists() {
        DevelopmentSegmentSourceProvider explicit = (conversationId, runId, utterance) ->
                CampaignSegmentSubmission.unavailable("CAMPAIGN_AGENT_SEGMENT_SOURCE_REQUIRED");

        enabled()
                .withBean(DevelopmentStructuredModelPort.class, DevelopmentSegmentAgentConfigurationWiringTest::plainTransport)
                .withBean(SegmentPreviewPort.class, DevelopmentSegmentAgentConfigurationWiringTest::previewPort)
                .withBean(DevelopmentSegmentSourceProvider.class, () -> explicit)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CampaignAgentEnvironment environment = context.getBean(CampaignAgentEnvironment.class);
                    CampaignSegmentSubmission submission = environment.segmentSource(
                            CONVERSATION, RUN, UTTERANCE);
                    assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Unavailable.class);
                    assertThat(((CampaignSegmentSubmission.Unavailable) submission).code())
                            .isEqualTo("CAMPAIGN_AGENT_SEGMENT_SOURCE_REQUIRED");
                    assertThat(context.getBeansOfType(DevelopmentSegmentSourceProvider.class)).hasSize(1);
                });
    }

    @Test
    void exactlyOnePreviewPortBuildsTheRealDevelopmentSegmentProvider() {
        DevelopmentSegmentRealHostFixture.FakeTransport transport =
                new DevelopmentSegmentRealHostFixture.FakeTransport()
                        .intent(DevelopmentSegmentRealHostFixture.resolvedIntentJson());

        enabled()
                // One bean resolves as the structured transport and, being a SegmentIntentModelPort, is the
                // same object the real Segment source is built over — exactly one model, never two.
                .withBean(DevelopmentSegmentRealHostFixture.FakeTransport.class, () -> transport)
                .withBean(SegmentPreviewPort.class, DevelopmentSegmentAgentConfigurationWiringTest::previewPort)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CampaignAgentEnvironment environment = context.getBean(CampaignAgentEnvironment.class);
                    CampaignSegmentSubmission submission = environment.segmentSource(
                            CONVERSATION, RUN, UTTERANCE);
                    assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
                    SegmentAuthoringSource source =
                            ((CampaignSegmentSubmission.Source) submission).source().source();
                    assertThat(source).isInstanceOf(SegmentAuthoringSource.AiResolved.class);
                    assertThat(((SegmentAuthoringSource.AiResolved) source).result().isReadyForPreview())
                            .isTrue();
                });
    }

    @Test
    void withNoPreviewPortTheHonestUnavailableProviderStays() {
        enabled()
                .withBean(DevelopmentStructuredModelPort.class, DevelopmentSegmentAgentConfigurationWiringTest::plainTransport)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CampaignAgentEnvironment environment = context.getBean(CampaignAgentEnvironment.class);
                    assertThat(environment.segmentSource(CONVERSATION, RUN, UTTERANCE)).isNull();
                });
    }

    @Test
    void moreThanOnePreviewPortFailsStartupWithABoundedConfigurationCode() {
        enabled()
                .withBean(DevelopmentStructuredModelPort.class, DevelopmentSegmentAgentConfigurationWiringTest::plainTransport)
                .withBean("firstPreview", SegmentPreviewPort.class, DevelopmentSegmentAgentConfigurationWiringTest::previewPort)
                .withBean("secondPreview", SegmentPreviewPort.class, DevelopmentSegmentAgentConfigurationWiringTest::previewPort)
                .run(context -> assertConfigurationFailure(
                        context.getStartupFailure(),
                        DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID));
    }

    @Test
    void moreThanOneSourceProviderFailsStartupWithABoundedConfigurationCode() {
        enabled()
                .withBean(DevelopmentStructuredModelPort.class, DevelopmentSegmentAgentConfigurationWiringTest::plainTransport)
                .withBean("firstSource", DevelopmentSegmentSourceProvider.class,
                        () -> DevelopmentSegmentSourceProvider.none())
                .withBean("secondSource", DevelopmentSegmentSourceProvider.class,
                        () -> DevelopmentSegmentSourceProvider.none())
                .run(context -> assertConfigurationFailure(
                        context.getStartupFailure(),
                        DevelopmentCampaignAgentErrorCodes.CONFIGURATION_INVALID));
    }

    @Test
    void anIncompatibleModelTransportFailsStartupWithABoundedConfigurationCode() {
        enabled()
                .withBean(DevelopmentStructuredModelPort.class, DevelopmentSegmentAgentConfigurationWiringTest::plainTransport)
                .withBean(SegmentPreviewPort.class, DevelopmentSegmentAgentConfigurationWiringTest::previewPort)
                .run(context -> assertConfigurationFailure(
                        context.getStartupFailure(),
                        DevelopmentCampaignAgentErrorCodes.SEGMENT_MODEL_TRANSPORT_INCOMPATIBLE));
    }

    @Test
    void theDisabledDefaultWiresNoCampaignAgentAtAll() {
        new ApplicationContextRunner()
                .withUserConfiguration(DevelopmentCampaignAgentConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(CampaignAgentEnvironment.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignAgentTurnService.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignAgentTurnController.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(CampaignConversationStore.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(DevelopmentSegmentSourceProvider.class)).isEmpty();
                });
    }

    private static void assertConfigurationFailure(Throwable failure, String code) {
        assertThat(failure).isNotNull();
        Throwable cause = failure;
        while (cause != null && !(cause instanceof DevelopmentCampaignAgentConfigurationException)) {
            cause = cause.getCause();
        }
        assertThat(cause).isInstanceOf(DevelopmentCampaignAgentConfigurationException.class);
        assertThat(((DevelopmentCampaignAgentConfigurationException) cause).code()).isEqualTo(code);
    }
}
