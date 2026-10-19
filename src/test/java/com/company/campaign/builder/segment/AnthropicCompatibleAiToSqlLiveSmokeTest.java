package com.company.campaign.builder.segment;

import com.company.campaign.builder.segment.DevelopmentAiToSqlResult.ParameterBinding;
import com.company.campaign.builder.segment.testsupport.AnthropicCompatibleModelPort;
import com.company.campaign.builder.segment.testsupport.SliceTestFixtures;
import com.company.campaign.contracts.Disposition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.query.sql.SqlParameterType;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The opt-in real-model smoke test.
 *
 * <p>It runs only when {@code AI_SEGMENT_LIVE_SMOKE=true} is set, and reads only {@code ANTHROPIC_AUTH_TOKEN},
 * {@code ANTHROPIC_BASE_URL} and {@code ANTHROPIC_MODEL}. The provider call lives in the test-only
 * {@link AnthropicCompatibleModelPort}, which uses the JDK {@code HttpClient} directly (no provider SDK),
 * bounds every timeout and response size, performs no retry, and keeps the credential only in one
 * {@code Authorization: Bearer} header. When the flag is absent the test is skipped and no other deterministic
 * test is affected.
 *
 * <p>It asks the model for one raw strict JSON Intent for the fixed utterance {@code Gold会员}, runs the full
 * deterministic slice, and asserts one {@code DEVELOPMENT_ONLY} compiled SQL artifact with
 * {@code member.tier = ?} semantics and a typed {@code GOLD} parameter. It never executes PostgreSQL.
 */
class AnthropicCompatibleAiToSqlLiveSmokeTest {

    @Test
    void theLiveModelProducesACompiledDevelopmentOnlyArtifact() {
        Assumptions.assumeTrue(
                "true".equalsIgnoreCase(System.getenv("AI_SEGMENT_LIVE_SMOKE")),
                "opt-in live model smoke test; set AI_SEGMENT_LIVE_SMOKE=true to run");

        String token = System.getenv("ANTHROPIC_AUTH_TOKEN");
        String baseUrl = System.getenv("ANTHROPIC_BASE_URL");
        String model = System.getenv("ANTHROPIC_MODEL");
        Assumptions.assumeTrue(token != null && !token.isBlank(), "ANTHROPIC_AUTH_TOKEN is required");
        Assumptions.assumeTrue(baseUrl != null && !baseUrl.isBlank(), "ANTHROPIC_BASE_URL is required");
        Assumptions.assumeTrue(model != null && !model.isBlank(), "ANTHROPIC_MODEL is required");

        MetadataSnapshot minimalSnapshot = MetadataFixtures.snapshot(
                List.of(MetadataFixtures.tierAttribute()), List.of(), List.of());
        SegmentIntentModelPort livePort = new AnthropicCompatibleModelPort(token, baseUrl, model);

        DevelopmentAiToSqlRequest request = new DevelopmentAiToSqlRequest(
                AnthropicCompatibleModelPort.SMOKE_UTTERANCE, "zh-CN", minimalSnapshot,
                SliceTestFixtures.policy(), SliceTestFixtures.registry(),
                MetadataFixtures.context(MetadataFixtures.allPermissions()),
                SliceTestFixtures.validationPolicy(),
                SliceTestFixtures.catalog(), SliceTestFixtures.sqlCompilePolicy(), SliceTestFixtures.fx());

        DevelopmentAiToSqlResult result = new DevelopmentAiToSqlService(
                livePort, SegmentValidationFixtures.allowAll()).invoke(request);

        assertThat(result.disposition()).isEqualTo(Disposition.RESOLVED);
        assertThat(result.isCompiled()).isTrue();
        assertThat(result.evidenceClass()).isEqualTo("DEVELOPMENT_ONLY");
        assertThat(result.sql()).contains("\"tier\" = ?");
        assertThat(result.parameters()).contains(new ParameterBinding(SqlParameterType.ENUM, "GOLD", false));
        assertThat(result.sql()).doesNotContain("GOLD");
    }
}