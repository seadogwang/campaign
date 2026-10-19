package com.company.campaign.metadata.compat;

import com.company.campaign.evaluation.segment.contract.MetadataSnapshotDescriptor;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractException;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A T03 descriptor can be checked against a T04 snapshot's exact identity without T03 importing T04. */
class MetadataSnapshotDescriptorVerifierTest {

    private static final String PAYLOAD = "evaluation/segment/metadata/metadata-17.json";

    @Test
    void acceptsADescriptorThatMatchesTheSnapshotIdentity() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataSnapshotDescriptor descriptor = new MetadataSnapshotDescriptor(
                snapshot.snapshotId(), snapshot.snapshotVersion(), snapshot.contentHash(), PAYLOAD);

        assertThat(MetadataSnapshotDescriptorVerifier.matches(descriptor, snapshot)).isTrue();
        MetadataSnapshotDescriptorVerifier.verify(descriptor, snapshot);
    }

    @Test
    void rejectsMismatchedIdVersionAndHashWithoutLeakingIdentity() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        ContentHash otherHash = ContentHash.sha256("other".getBytes());

        for (MetadataSnapshotDescriptor descriptor : new MetadataSnapshotDescriptor[] {
                new MetadataSnapshotDescriptor("metadata-99", "1", snapshot.contentHash(), PAYLOAD),
                new MetadataSnapshotDescriptor("metadata-17", "2", snapshot.contentHash(), PAYLOAD),
                new MetadataSnapshotDescriptor("metadata-17", "1", otherHash, PAYLOAD)}) {
            assertThat(MetadataSnapshotDescriptorVerifier.matches(descriptor, snapshot)).isFalse();
            Throwable thrown = catchThrowable(() ->
                    MetadataSnapshotDescriptorVerifier.verify(descriptor, snapshot));
            assertThat(thrown).isInstanceOf(ContractException.class);
            ContractException failure = (ContractException) thrown;
            assertThat(failure.code()).isEqualTo(MetadataErrorCodes.METADATA_DESCRIPTOR_MISMATCH);
            assertThat(failure.getMessage() + failure.details())
                    .doesNotContain("metadata-99", "metadata-17", otherHash.value());
        }
    }
}