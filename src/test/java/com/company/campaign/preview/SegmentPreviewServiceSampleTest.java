package com.company.campaign.preview;

import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.adapter.FixturePreviewExecutionBackend;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.AuthorizationDisposition;
import com.company.campaign.security.authorization.AuthorizationReason;
import com.company.campaign.security.authorization.AuthorizationRequest;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirements 10-12: a sample is absent by default and authorization is not called; a requested sample uses
 * exactly one correct PREVIEW_SAMPLE/AUDIENCE_PREVIEW decision; every malformed, wrong-kind, wrong-permission
 * or denied decision fails closed with no sample; an authorized sample is a bounded, sorted list of keyed
 * opaque hashes that never reveals a raw member identifier or the key.
 */
class SegmentPreviewServiceSampleTest {

    @TempDir
    Path tempDir;

    private FixtureDataset dataset;
    private RecordingTrustedContextProvider provider;
    private MetadataSnapshotRepository repository;

    @BeforeEach
    void setUp() {
        dataset = PreviewTestSupport.dataset(tempDir);
        provider = RecordingTrustedContextProvider.of(SegmentValidationFixtures.context());
        repository = PreviewTestSupport.repository();
    }

    @Test
    void sampleIsAbsentByDefaultAndAuthorizationIsNotCalled() {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        SegmentPreviewService service = service(stub, PreviewTestSupport.policy());

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), false, true));

        assertThat(response.sample()).isNull();
        assertThat(stub.sampleCallCount()).isZero();
    }

    @Test
    void aRequestedSampleUsesExactlyOneCorrectPreviewSampleDecision() {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        SegmentPreviewService service = service(stub, PreviewTestSupport.policy());

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));

        assertThat(stub.sampleCallCount()).isEqualTo(1);
        List<AuthorizationRequest> requests = stub.sampleRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).resource().kind()).isEqualTo(ResourceKind.PREVIEW_SAMPLE);
        assertThat(requests.get(0).resource().tenantId()).isEqualTo("tenant-001");
        assertThat(requests.get(0).resource().programId()).isEqualTo("program-001");
        assertThat(requests.get(0).context().dataScopeId()).isEqualTo("scope-008");

        assertThat(response.sample()).hasSize(3);
        assertThat(response.sample()).allMatch(hash -> hash.matches("^sha256:[0-9a-f]{64}$"));
    }

    @Test
    void everyUnacceptableSampleDecisionFailsClosedWithNoSample() {
        assertNoSample(AuthorizationDecision.deny(AuthorizationDisposition.PERMISSION_DENIED,
                AuthorizationReason.PREVIEW_SAMPLE_MISSING_PERMISSION, ResourceKind.PREVIEW_SAMPLE,
                CampaignPermission.AUDIENCE_PREVIEW));
        assertNoSample(AuthorizationDecision.deny(AuthorizationDisposition.NOT_FOUND,
                AuthorizationReason.UNKNOWN_RESOURCE, ResourceKind.PREVIEW_SAMPLE,
                CampaignPermission.AUDIENCE_PREVIEW));
        assertNoSample(AuthorizationDecision.deny(AuthorizationDisposition.DATASCOPE_DENIED,
                AuthorizationReason.DATASCOPE_DENIED, ResourceKind.PREVIEW_SAMPLE,
                CampaignPermission.AUDIENCE_PREVIEW));
        // Wrong kind.
        assertNoSample(AuthorizationDecision.allow(ResourceKind.SEGMENT, CampaignPermission.SEGMENT_EVALUATE));
        // Wrong permission.
        assertNoSample(AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.CAMPAIGN_READ));
        // ALLOWED disposition with a non-ALLOWED reason.
        assertNoSample(AuthorizationDecision.deny(AuthorizationDisposition.ALLOWED,
                AuthorizationReason.MISSING_PERMISSION, ResourceKind.PREVIEW_SAMPLE,
                CampaignPermission.AUDIENCE_PREVIEW));
        // Null decision.
        assertNoSample(null);

        // A thrown sample boundary failure.
        PreviewAuthorizationStub throwing =
                PreviewAuthorizationStub.throwingSample(SegmentValidationFixtures.allowAll());
        SegmentPreviewService throwingService = service(throwing, PreviewTestSupport.policy());
        PreviewResponse response = throwingService.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));
        assertThat(response.sample()).isNull();
        assertThat(throwing.sampleCallCount()).isEqualTo(1);
    }

    @Test
    void anAuthorizedSampleIsServerCappedAndSorted() {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        PreviewPolicy policy = new PreviewPolicy(
                PreviewTestSupport.policy().maxRequestBytes(), 128, 1, 1_000_000, Duration.ofHours(24));
        SegmentPreviewService service = service(stub, policy);

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));

        assertThat(response.sample()).hasSize(1);
    }

    @Test
    void theSameKeyAndContextProduceTheSameSampleAcrossCalls() {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        SegmentPreviewService service = service(stub, PreviewTestSupport.policy());

        PreviewResponse first = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));
        PreviewResponse second = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));

        assertThat(first.sample()).isEqualTo(second.sample());
        assertThat(first.contentHash()).isEqualTo(second.contentHash());
    }

    @Test
    void neitherRawMemberIdentifiersNorTheKeyEverAppearInTheResponse() {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        SegmentPreviewService service = service(stub, PreviewTestSupport.policy());

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));
        String serialized = new CanonicalJson().writeString(response);

        assertThat(serialized).doesNotContain("m-001").doesNotContain("m-003").doesNotContain("m-006");
        assertThat(serialized).doesNotContain("tenant-001").doesNotContain("program-001")
                .doesNotContain("scope-008").doesNotContain("user-001");
        assertThat(serialized).doesNotContain(PreviewTestSupport.KEY_A);
        assertThat(serialized).doesNotContain("GOLD");
        assertThat(response.sample()).isNotNull();
    }

    private void assertNoSample(AuthorizationDecision decision) {
        PreviewAuthorizationStub stub = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(), decision);
        SegmentPreviewService service = service(stub, PreviewTestSupport.policy());

        PreviewResponse response = service.preview(
                PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true));

        assertThat(response.status()).isEqualTo("SUCCESS");
        assertThat(response.sample()).as(String.valueOf(decision)).isNull();
        assertThat(stub.sampleCallCount()).isEqualTo(1);
    }

    private SegmentPreviewService service(PreviewAuthorizationStub authorization, PreviewPolicy policy) {
        return PreviewTestSupport.service(
                provider, repository, PreviewTestSupport.fixtureBackend(dataset),
                authorization, policy, PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));
    }
}