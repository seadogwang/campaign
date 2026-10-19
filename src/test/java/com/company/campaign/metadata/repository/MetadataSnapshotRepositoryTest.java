package com.company.campaign.metadata.repository;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** Publishing, idempotent re-registration, conflicts and immutable retention of historical snapshots. */
class MetadataSnapshotRepositoryTest {

    @Test
    void publishesAndResolvesByIdPinAndContentHash() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);

        assertThat(repository.findById("metadata-17")).contains(snapshot);
        assertThat(repository.findByPin("metadata-17", "1", snapshot.contentHash())).contains(snapshot);
        assertThat(repository.findByContentHash(snapshot.contentHash())).contains(snapshot);
        assertThat(repository.findByPin("metadata-17", "2", snapshot.contentHash())).isEmpty();
        assertThat(repository.findByPin("metadata-17", "1",
                com.company.campaign.shared.canonical.ContentHash.sha256("other".getBytes()))).isEmpty();
        assertThat(repository.findById("missing")).isEmpty();
    }

    @Test
    void retainsMultipleHistoricalSnapshots() {
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        MetadataSnapshot older = MetadataFixtures.standardSnapshot();
        MetadataSnapshot newer = MetadataFixtures.snapshot(
                "metadata-18", "2", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.tierAttribute()),
                List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
        repository.publish(older);
        repository.publish(newer);

        assertThat(repository.retained()).containsExactly(older, newer);
        assertThat(repository.findById("metadata-17")).contains(older);
    }

    @Test
    void exactIdempotentReRegistrationIsAllowed() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(snapshot);
        repository.publish(snapshot);

        assertThat(repository.retained()).hasSize(1);
    }

    @Test
    void conflictingReuseOfASnapshotIdFails() {
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        repository.publish(MetadataFixtures.standardSnapshot());
        MetadataSnapshot conflicting = MetadataFixtures.snapshot(
                "metadata-17", "2", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(), List.of(), List.of());

        Throwable thrown = catchThrowable(() -> repository.publish(conflicting));
        assertThat(thrown).isInstanceOf(MetadataException.class);
        assertThat(((MetadataException) thrown).code()).isEqualTo(MetadataErrorCodes.METADATA_SNAPSHOT_CONFLICT);
        // The conflict must not replace the retained snapshot.
        assertThat(repository.findById("metadata-17")).contains(MetadataFixtures.standardSnapshot());
    }

    @Test
    void publishingANewerSnapshotPreservesTheOlderSnapshotBytesHashAndGraph() {
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        MetadataSnapshot older = MetadataFixtures.standardSnapshot();
        repository.publish(older);

        com.company.campaign.shared.canonical.CanonicalJson json =
                new com.company.campaign.shared.canonical.CanonicalJson();
        byte[] bytesBefore = json.write(older);
        com.company.campaign.shared.canonical.ContentHash hashBefore = older.contentHash();
        String displayBefore = older.attributes().get(0).displayName();

        repository.publish(MetadataFixtures.snapshot("metadata-18", "2",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.snapshotAttribute("member.city", "Member City")),
                List.of(), List.of()));

        MetadataSnapshot retained = repository.findById("metadata-17").orElseThrow();
        assertThat(json.write(retained)).isEqualTo(bytesBefore);
        assertThat(retained.contentHash()).isEqualTo(hashBefore);
        assertThat(retained.attributes().get(0).displayName()).isEqualTo(displayBefore);
        assertThat(retained).isSameAs(older);
    }

    @Test
    void requireByIdFailsWithTheStableNotFoundCode() {
        MetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        ContractAssertions.assertFailsWithCode(
                () -> repository.requireById("metadata-17"), MetadataErrorCodes.METADATA_SNAPSHOT_NOT_FOUND);
    }
}