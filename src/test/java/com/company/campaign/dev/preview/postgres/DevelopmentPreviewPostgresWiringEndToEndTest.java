package com.company.campaign.dev.preview.postgres;

import com.company.campaign.builder.agent.SegmentAgentResult;
import com.company.campaign.builder.authoring.SegmentAuthoringSource;
import com.company.campaign.builder.campaignagent.CampaignAgentEnvironment;
import com.company.campaign.builder.campaignagent.CampaignSegmentSubmission;
import com.company.campaign.dev.campaignagent.DevelopmentCampaignAgentConfiguration;
import com.company.campaign.dev.campaignagent.testsupport.DevelopmentSegmentRealHostFixture;
import com.company.campaign.preview.SegmentPreviewService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34C requirement 2 end to end, without a database: with only a fake model and a scripted JDBC boundary, the
 * real enabled opt-in host builds its own genuine Preview port and the accepted Segment Agent resolves an
 * audience through the genuine validation, planning, compilation and read-only execution path.
 *
 * <p>The scripted JDBC boundary is explicitly not PostgreSQL evidence; the environment-gated integration test
 * is the only place that may claim a real database run. What this test proves is the wiring: the
 * auto-published {@code HostSafePreviewPort} really carries the genuine {@link SegmentPreviewService} and the
 * genuine {@link PostgresPreviewExecutionBackend} into the T33 development Segment source, and the resulting
 * Preview response is coherent with the T33 identity and Metadata snapshot pins.
 */
class DevelopmentPreviewPostgresWiringEndToEndTest {

    private static final String UTTERANCE = DevelopmentSegmentRealHostFixture.UTTERANCE;
    private static final String CONVERSATION = DevelopmentSegmentRealHostFixture.CONVERSATION;
    private static final String RUN = DevelopmentSegmentRealHostFixture.RUN;

    @Test
    void theAutoPreviewPortRunsTheGenuinePipelineOverTheReadOnlyJdbcBoundary() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary()
                .withRows("member-b", "member-a");

        enabledWithHostPool(boundary).run(context -> {
            assertThat(context).hasNotFailed();

            DevelopmentSegmentRealHostFixture.FakeTransport transport =
                    context.getBean(DevelopmentSegmentRealHostFixture.FakeTransport.class);
            transport.reset();
            transport.intent(DevelopmentSegmentRealHostFixture.resolvedIntentJson());

            CampaignAgentEnvironment environment = context.getBean(CampaignAgentEnvironment.class);
            CampaignSegmentSubmission submission = environment.segmentSource(CONVERSATION, RUN, UTTERANCE);

            assertThat(submission).isInstanceOf(CampaignSegmentSubmission.Source.class);
            SegmentAuthoringSource source =
                    ((CampaignSegmentSubmission.Source) submission).source().source();
            assertThat(source).isInstanceOf(SegmentAuthoringSource.AiResolved.class);

            SegmentAgentResult result = ((SegmentAuthoringSource.AiResolved) source).result();
            assertThat(result.isReadyForPreview()).isTrue();
            assertThat(result.previewCard()).isNotNull();
            assertThat(result.previewCard().matchedCount()).isEqualTo(2);
            assertThat(result.previewCard().previewHash()).isNotNull();
            // The preview runs under the exact T33 development identity and Metadata snapshot pins.
            assertThat(result.previewCard().metadataSnapshotId())
                    .isEqualTo(DevelopmentSegmentRealHostFixture.reference().metadataSnapshotId());
            assertThat(result.previewCard().asOf())
                    .isEqualTo(DevelopmentSegmentRealHostFixture.reference().asOf().toString());
            assertThat(result.previewCard().dataWatermark())
                    .isEqualTo(DevelopmentSegmentRealHostFixture.reference().dataWatermark().toString());
            assertThat(result.previewCard().timezone())
                    .isEqualTo(DevelopmentSegmentRealHostFixture.reference().timezone().getId());

            assertThat(transport.intentCalls()).isEqualTo(1);
            assertThat(boundary.calls()).contains("executeQuery", "rollback", "close");
            assertThat(boundary.rolledBack()).isTrue();
            assertThat(boundary.bindings()).isNotEmpty();
        });
    }

    @Test
    void aFixturePreviewServiceNeverReplacesTheGenuineOne() {
        ScriptedPreviewJdbcBoundary boundary = new ScriptedPreviewJdbcBoundary().withRows("member-a");

        enabledWithHostPool(boundary).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(SegmentPreviewService.class)).hasSize(1);
            assertThat(context.getBean(SegmentPreviewService.class)).isNotNull();
            // The only Preview port in the context is the one this integration published.
            assertThat(context.getBeanNamesForType(
                    com.company.campaign.builder.tool.SegmentPreviewPort.class)).hasSize(1);
            assertThat(boundary.calls()).isEmpty();
        });
    }

    // ------------------------------------------------------------------ helpers

    private static ApplicationContextRunner enabledWithHostPool(ScriptedPreviewJdbcBoundary boundary) {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        DevelopmentCampaignAgentConfiguration.class, PreviewPostgresConfiguration.class)
                .withBean(PreviewPostgresConfiguration.DATASOURCE_BEAN_NAME, DataSource.class, boundary::dataSource)
                .withBean("previewTestClock", Clock.class,
                        () -> Clock.fixed(DevelopmentSegmentRealHostFixture.NOW, ZoneOffset.UTC),
                        bean -> bean.setPrimary(true))
                .withBean(DevelopmentSegmentRealHostFixture.FakeTransport.class,
                        DevelopmentSegmentRealHostFixture.FakeTransport::new)
                .withPropertyValues(
                        "campaign.agent.development.enabled=true",
                        "campaign.agent.development.preview.postgres.enabled=true",
                        "campaign.agent.development.preview.postgres.url=jdbc:postgresql://127.0.0.1:1/preview",
                        "campaign.agent.development.preview.postgres.username=preview-user",
                        "campaign.agent.development.preview.postgres.password=preview-secret-value",
                        "campaign.agent.development.preview.postgres.schema=loyalty_host",
                        "campaign.agent.development.preview.postgres.sample-key="
                                + "host-owned-sample-key-value-0123456789");
    }
}
