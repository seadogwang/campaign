package com.company.campaign.segment.dsl;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.segment.dsl.codec.SegmentDslCodec;
import com.company.campaign.segment.dsl.contract.MetadataBinding;
import com.company.campaign.segment.dsl.contract.SegmentDslErrorCodes;
import com.company.campaign.segment.dsl.predicate.AttributePredicate;
import com.company.campaign.segment.dsl.testsupport.SegmentDslFixtures;
import com.company.campaign.segment.dsl.value.EnumValue;
import com.company.campaign.segment.dsl.value.TypedValue;
import com.company.campaign.segment.dsl.predicate.ConditionValue;
import com.company.campaign.metadata.domain.ComparisonOperator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.company.campaign.testsupport.ContractAssertions.assertFailsWithCode;
import static org.assertj.core.api.Assertions.assertThat;

/** The explicit T04 snapshot verifier and the every-pin binding rules. */
class SegmentDslMetadataBindingTest {

    @Test
    void bindingFromSnapshotCarriesEveryPinExactly() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataBinding binding = MetadataBinding.fromSnapshot(snapshot);
        assertThat(binding.snapshotId()).isEqualTo(snapshot.snapshotId());
        assertThat(binding.snapshotVersion()).isEqualTo(snapshot.snapshotVersion());
        assertThat(binding.metadataSchemaVersion()).isEqualTo(snapshot.schemaVersion());
        assertThat(binding.contentHash()).isEqualTo(snapshot.contentHash());
    }

    @Test
    void verifierAcceptsAnExactT04Snapshot() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataBinding.fromSnapshot(snapshot).verify(snapshot);
    }

    @Test
    void verifierRejectsEveryMismatchedPin() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        MetadataBinding binding = MetadataBinding.fromSnapshot(snapshot);

        MetadataSnapshot otherId = MetadataFixtures.snapshot(
                "metadata-18", "1", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.tierAttribute()), List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
        assertFailsWithCode(() -> binding.verify(otherId), SegmentDslErrorCodes.METADATA_BINDING_INVALID);

        MetadataSnapshot otherVersion = MetadataFixtures.snapshot(
                "metadata-17", "2", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.tierAttribute()), List.of(MetadataFixtures.netPaidAmountMetric()),
                List.of(MetadataFixtures.ordersRelation()));
        assertFailsWithCode(() -> binding.verify(otherVersion), SegmentDslErrorCodes.METADATA_BINDING_INVALID);

        MetadataSnapshot otherContent = MetadataFixtures.snapshot(
                "metadata-17", "1", MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                List.of(MetadataFixtures.tierAttribute(), MetadataFixtures.snapshotAttribute("member.extra", "Extra")),
                List.of(MetadataFixtures.netPaidAmountMetric()), List.of(MetadataFixtures.ordersRelation()));
        assertFailsWithCode(() -> binding.verify(otherContent), SegmentDslErrorCodes.METADATA_BINDING_INVALID);

        assertFailsWithCode(() -> binding.verify(null), SegmentDslErrorCodes.METADATA_BINDING_INVALID);
    }

    @Test
    void bindingConstructorRejectsUnsupportedSchemaVersionAndMalformedIdentifiers() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        assertFailsWithCode(
                () -> new MetadataBinding("metadata-17", "1", new SchemaVersion(2, 0), snapshot.contentHash()),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> new MetadataBinding("bad id", "1", MetadataSchemaVersions.SUPPORTED, snapshot.contentHash()),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> new MetadataBinding("metadata-17", null, MetadataSchemaVersions.SUPPORTED, snapshot.contentHash()),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
        assertFailsWithCode(
                () -> new MetadataBinding("metadata-17", "1", MetadataSchemaVersions.SUPPORTED, null),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
    }

    @Test
    void structuralParseDoesNotVerifyTheSnapshotButTheExplicitVerifierDoes() {
        // A document binds by pin only; T05 parse checks structure and the editable hash, not the live snapshot.
        MetadataBinding foreignBinding = SegmentDslFixtures.binding("metadata-18");
        TypedValue gold = new EnumValue("GOLD");
        var predicate = new AttributePredicate(
                "n-a", "member.tier", ComparisonOperator.EQUAL, ConditionValue.of(gold));
        var document = SegmentDslFixtures.publish(foreignBinding, predicate);
        var codec = new SegmentDslCodec();
        var reparsed = codec.parse(codec.writeString(document));
        assertThat(reparsed.metadata().snapshotId()).isEqualTo("metadata-18");

        // The explicit adapter is what fails closed against the real snapshot.
        assertFailsWithCode(
                () -> reparsed.metadata().verify(MetadataFixtures.standardSnapshot()),
                SegmentDslErrorCodes.METADATA_BINDING_INVALID);
    }
}