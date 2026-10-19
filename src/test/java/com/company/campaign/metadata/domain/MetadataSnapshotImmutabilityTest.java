package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.repository.InMemoryMetadataSnapshotRepository;
import com.company.campaign.metadata.service.MetadataSearchResult;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Every reachable collection and nested value of a published snapshot is a deeply immutable defensive copy. */
class MetadataSnapshotImmutabilityTest {

    @Test
    void snapshotCollectionsAndNestedValuesAreImmutable() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        AttributeDefinition attribute = snapshot.attributes().get(0);
        MetricDefinition metric = snapshot.metrics().get(0);

        assertThatThrownBy(() -> snapshot.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> attribute.aliases().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> attribute.allowedOperators().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> attribute.enumValues().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> attribute.enumValues().get(0).aliases().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> attribute.positiveExamples().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metric.aliases().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metric.supportedWindows().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metric.filter().entries().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> metric.filter().entries().get(0).values().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.definitions(MetadataKind.ATTRIBUTE).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotIsADefensiveCopyOfTheSourceLists() {
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(MetadataFixtures.tierAttribute());
        MetadataSnapshot snapshot = MetadataFixtures.snapshot(
                attributes, new ArrayList<>(), new ArrayList<>());
        attributes.clear();

        assertThat(snapshot.attributes()).hasSize(1);
        assertThat(snapshot.attributes().get(0).id()).isEqualTo("member.tier");
    }

    @Test
    void repositoryNeverExposesAMutableIndex() {
        InMemoryMetadataSnapshotRepository repository = new InMemoryMetadataSnapshotRepository();
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        repository.publish(snapshot);

        List<MetadataSnapshot> retained = repository.retained();
        assertThatThrownBy(retained::clear).isInstanceOf(UnsupportedOperationException.class);

        // A later publication cannot alter the earlier retained snapshot's object graph or hash.
        MetadataSnapshot newer = MetadataFixtures.snapshot("metadata-18", "2",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(), List.of(), List.of());
        repository.publish(newer);

        assertThat(repository.findById("metadata-17")).contains(snapshot);
        assertThat(snapshot.attributes()).hasSize(1);
        assertThat(snapshot.contentHash()).isEqualTo(MetadataFixtures.standardSnapshot().contentHash());
    }

    @Test
    void searchResultsAreImmutable() {
        MetadataSearchResult result = new MetadataSearchResult(
                MetadataKind.ATTRIBUTE, "metadata-17", 10, false,
                List.of(new com.company.campaign.metadata.service.MetadataSearchMatch(
                        MetadataFixtures.tierAttribute(),
                        com.company.campaign.metadata.service.MetadataSearchTier.EXACT_ID)));
        assertThatThrownBy(() -> result.matches().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}