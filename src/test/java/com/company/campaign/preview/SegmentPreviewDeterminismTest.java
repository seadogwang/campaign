package com.company.campaign.preview;

import com.company.campaign.metadata.repository.MetadataSnapshotRepository;
import com.company.campaign.preview.testsupport.PreviewAuthorizationStub;
import com.company.campaign.preview.testsupport.PreviewTestSupport;
import com.company.campaign.preview.testsupport.RecordingTrustedContextProvider;
import com.company.campaign.query.fixture.FixtureDataset;
import com.company.campaign.security.authorization.AuthorizationDecision;
import com.company.campaign.security.authorization.ResourceKind;
import com.company.campaign.security.context.CampaignPermission;
import com.company.campaign.segment.validation.testsupport.SegmentValidationFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 13: repeated identical Preview calls (at least 100) yield byte-identical data and envelope
 * material, except the explicitly deterministic request identifier, which is also identical.
 */
class SegmentPreviewDeterminismTest {

    private static final int CALLS = 100;

    @TempDir
    Path tempDir;

    @Test
    void oneHundredIdenticalCallsAreByteIdentical() {
        FixtureDataset dataset = PreviewTestSupport.dataset(tempDir);
        RecordingTrustedContextProvider provider =
                RecordingTrustedContextProvider.of(SegmentValidationFixtures.context());
        MetadataSnapshotRepository repository = PreviewTestSupport.repository();
        PreviewAuthorizationStub authorization = PreviewAuthorizationStub.sample(
                SegmentValidationFixtures.allowAll(),
                AuthorizationDecision.allow(ResourceKind.PREVIEW_SAMPLE, CampaignPermission.AUDIENCE_PREVIEW));
        SegmentPreviewService service = PreviewTestSupport.service(
                provider, repository, PreviewTestSupport.fixtureBackend(dataset), authorization,
                PreviewTestSupport.policy(), PreviewTestSupport.hasher(PreviewTestSupport.KEY_A));

        byte[] body = PreviewTestSupport.body(PreviewTestSupport.goldDocument(), true, true);
        CanonicalJson canonical = new CanonicalJson();

        Set<String> responseHashes = new HashSet<>();
        Set<String> canonicalBodies = new HashSet<>();
        Set<String> planIds = new HashSet<>();
        Set<String> requestIds = new HashSet<>();

        for (int index = 0; index < CALLS; index++) {
            PreviewResponse response = service.preview(body);
            responseHashes.add(response.contentHash().value());
            canonicalBodies.add(canonical.writeString(response));
            planIds.add(response.queryPlanId());
            requestIds.add(com.company.campaign.preview.api.PreviewRequestId.from(body));
        }

        assertThat(responseHashes).hasSize(1);
        assertThat(canonicalBodies).hasSize(1);
        assertThat(planIds).hasSize(1);
        assertThat(requestIds).hasSize(1);
        assertThat(provider.callCount()).isEqualTo(CALLS);
        assertThat(authorization.sampleCallCount()).isEqualTo(CALLS);
    }
}