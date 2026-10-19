package com.company.campaign.preview.api;

import com.company.campaign.preview.PreviewPolicy;
import com.company.campaign.preview.PreviewSampleHasher;
import com.company.campaign.preview.SegmentPreviewService;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requirement 15: MockMvc proves the exact success and error envelopes, the content-type, method and body
 * behavior, the deterministic request id and the absence of any trusted-field binding path. Every dependency
 * is supplied explicitly by the test; no Spring component scanning and no default wiring is used.
 */
class SegmentPreviewControllerTest {

    private static final String ENDPOINT = "/ai/segments/preview";

    @TempDir
    Path tempDir;

    private FixtureDataset dataset;
    private RecordingTrustedContextProvider provider;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        dataset = PreviewTestSupport.dataset(tempDir);
        provider = RecordingTrustedContextProvider.of(SegmentValidationFixtures.context());
        mvc = mvc(service(PreviewTestSupport.policy(), SegmentValidationFixtures.allowAll()));
    }

    @Test
    void exactSuccessEnvelopeAndNoForbiddenFields() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").value(matchesPattern("^req-[0-9a-f]{32}$")))
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.canonicalAstHash").value(matchesPattern("^sha256:[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.queryPlanId").value(matchesPattern("^qp-[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.queryPlanHash").value(matchesPattern("^sha256:[0-9a-f]{64}$")))
                .andExpect(jsonPath("$.data.matchedCount").value(3))
                .andExpect(jsonPath("$.data.conditionStats").isArray())
                .andExpect(jsonPath("$.data.executionContext.metadataSnapshotId").value("metadata-17"))
                .andExpect(jsonPath("$.data.executionContext.asOf").value("2026-10-01T02:00:00Z"))
                .andExpect(jsonPath("$.data.executionContext.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.executionContext.dataWatermark").value("2026-10-01T01:55:00Z"))
                .andExpect(jsonPath("$.data.sample").doesNotExist())
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.tenantId").doesNotExist())
                .andExpect(jsonPath("$.data.programId").doesNotExist())
                .andExpect(jsonPath("$.data.dataScopeId").doesNotExist())
                .andExpect(jsonPath("$.data.userId").doesNotExist())
                .andExpect(jsonPath("$.data.sql").doesNotExist())
                .andExpect(jsonPath("$.data.parameters").doesNotExist())
                .andExpect(jsonPath("$.data.fixtureId").doesNotExist())
                .andExpect(jsonPath("$.data.memberIds").doesNotExist());
    }

    @Test
    void exactErrorEnvelopeForAnInjectedTrustedField() throws Exception {
        String base = PreviewTestSupport.utf8(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));
        String injected = "{\"dataScopeId\":\"scope-999\"," + base.substring(1);

        String response = mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(injected.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEGMENT_PREVIEW_REQUEST_UNKNOWN_FIELD"))
                .andExpect(jsonPath("$.error.message").isNotEmpty())
                .andExpect(jsonPath("$.error.details").exists())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("scope-999");
    }

    @Test
    void theEnvelopeNeverCopiesAnExceptionMessageOrCause() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"segmentDsl\":".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEGMENT_PREVIEW_REQUEST_JSON_MALFORMED"))
                .andExpect(jsonPath("$.error.message").value("The Segment Preview request body is not valid JSON."));
    }

    @Test
    void queryParametersAndHeadersCannotBindTrustedExecutionContext() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .queryParam("dataScopeId", "scope-999")
                        .queryParam("asOf", "2020-01-01T00:00:00Z")
                        .header("X-DataScope", "scope-999")
                        .header("X-Tenant-Id", "tenant-999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.matchedCount").value(3))
                .andExpect(jsonPath("$.data.executionContext.asOf").value("2026-10-01T02:00:00Z"));
    }

    @Test
    void rejectsUnsupportedMethodDeterministically() throws Exception {
        mvc.perform(get(ENDPOINT))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEGMENT_PREVIEW_METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsUnsupportedMediaTypeDeterministically() throws Exception {
        mvc.perform(post(ENDPOINT)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SEGMENT_PREVIEW_UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void rejectsOversizedBodiesDeterministically() throws Exception {
        PreviewPolicy tiny = new PreviewPolicy(64, 128, 100, 1_000_000, Duration.ofHours(24));
        MockMvc tinyMvc = mvc(service(tiny, SegmentValidationFixtures.allowAll()));

        tinyMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("SEGMENT_PREVIEW_REQUEST_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void theRequestIdIsDeterministicForTheSameBody() throws Exception {
        byte[] body = PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true);
        String first = mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(first).isEqualTo(second);
    }

    @Test
    void aRequestedSampleReturnsOnlyOpaqueHashes() throws Exception {
        PreviewAuthorizationStub authorization = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        MockMvc sampleMvc = mvc(service(PreviewTestSupport.policy(), authorization));

        String response = sampleMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sample").isArray())
                .andExpect(jsonPath("$.data.sample[0]").value(matchesPattern("^sha256:[0-9a-f]{64}$")))
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(response)
                .doesNotContain("m-001").doesNotContain("m-003").doesNotContain(PreviewTestSupport.KEY_A);
        org.assertj.core.api.Assertions.assertThat(authorization.sampleCallCount()).isEqualTo(1);
    }

    private MockMvc mvc(SegmentPreviewService service) {
        return MockMvcBuilders.standaloneSetup(new SegmentPreviewController(service)).build();
    }

    private SegmentPreviewService service(
            PreviewPolicy policy, com.company.campaign.security.authorization.AuthorizationService authorization) {
        PreviewSampleHasher hasher = PreviewTestSupport.hasher(PreviewTestSupport.KEY_A);
        return PreviewTestSupport.service(
                provider, PreviewTestSupport.repository(), PreviewTestSupport.fixtureBackend(dataset),
                authorization, policy, hasher);
    }
}