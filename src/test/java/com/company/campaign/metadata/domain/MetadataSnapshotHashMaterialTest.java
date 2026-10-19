package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Finding 6: the public hash material is deeply safe at its own boundary — mutable caller lists cannot change
 * it after construction and definition order never changes its bytes or hash.
 */
class MetadataSnapshotHashMaterialTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void mutatingSourceListsAfterConstructionCannotChangeMaterialBytesOrHash() {
        AttributeDefinition tier = MetadataFixtures.tierAttribute();
        List<AttributeDefinition> attributes = new ArrayList<>();
        attributes.add(tier);
        List<MetricDefinition> metrics = new ArrayList<>();
        List<RelationDefinition> relations = new ArrayList<>();

        MetadataSnapshotHashMaterial material = material(attributes, metrics, relations);
        byte[] bytesBefore = JSON.write(material);
        ContentHash hashBefore = MetadataSnapshotHasher.hash(material);

        attributes.clear();
        attributes.add(MetadataFixtures.snapshotAttribute("member.city", "Member City"));
        metrics.add(MetadataFixtures.netPaidAmountMetric());
        relations.add(MetadataFixtures.ordersRelation());

        assertThat(material.attributes()).containsExactly(tier);
        assertThat(material.metrics()).isEmpty();
        assertThat(material.relations()).isEmpty();
        assertThat(JSON.write(material)).isEqualTo(bytesBefore);
        assertThat(MetadataSnapshotHasher.hash(material)).isEqualTo(hashBefore);
    }

    @Test
    void reversedInputOrderProducesIdenticalMaterialAndHash() {
        AttributeDefinition a = MetadataFixtures.tierAttribute();
        AttributeDefinition b = MetadataFixtures.snapshotAttribute("member.city", "Member City");

        MetadataSnapshotHashMaterial forward = material(List.of(a, b), List.of(), List.of());
        MetadataSnapshotHashMaterial reversed = material(List.of(b, a), List.of(), List.of());

        assertThat(reversed).isEqualTo(forward);
        assertThat(reversed.attributes()).extracting(MetadataDefinition::id)
                .containsExactly("member.city", "member.tier");
        assertThat(JSON.write(reversed)).isEqualTo(JSON.write(forward));
        assertThat(MetadataSnapshotHasher.hash(reversed)).isEqualTo(MetadataSnapshotHasher.hash(forward));
    }

    @Test
    void definitionListsAreImmutable() {
        MetadataSnapshotHashMaterial material =
                material(List.of(MetadataFixtures.tierAttribute()), List.of(), List.of());
        assertThatThrownBy(() -> material.attributes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> material.metrics().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> material.relations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullElementsAreRejected() {
        ContractAssertions.assertFailsWithCode(
                () -> material(Arrays.asList(MetadataFixtures.tierAttribute(), null), List.of(), List.of()),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> material(List.of(), Arrays.asList(MetadataFixtures.netPaidAmountMetric(), null), List.of()),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
        ContractAssertions.assertFailsWithCode(
                () -> material(List.of(), List.of(), Arrays.asList(MetadataFixtures.ordersRelation(), null)),
                MetadataErrorCodes.METADATA_VALUE_INVALID);
    }

    @Test
    void materialRemainsUsableForPerFieldMutationTestsWithoutCrossReferenceValidity() {
        // A metric whose relation is absent from the material still hashes: the material is deliberately not
        // a publishable, cross-reference-valid snapshot.
        MetadataSnapshotHashMaterial material = material(
                List.of(), List.of(MetadataFixtures.netPaidAmountMetric()), List.of());
        assertThat(MetadataSnapshotHasher.hash(material)).isNotNull();
    }

    private static MetadataSnapshotHashMaterial material(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return new MetadataSnapshotHashMaterial(
                MetadataSchemaVersions.SUPPORTED, "metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                attributes, metrics, relations);
    }
}