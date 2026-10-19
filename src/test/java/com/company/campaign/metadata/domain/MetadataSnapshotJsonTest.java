package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.company.campaign.shared.error.ContractErrorCodes;
import com.company.campaign.shared.error.ContractException;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/** A retained snapshot must round-trip through strict canonical JSON; unknown and duplicate fields fail. */
class MetadataSnapshotJsonTest {

    private static final CanonicalJson JSON = new CanonicalJson();

    @Test
    void roundTripsThroughCanonicalJson() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        String canonical = JSON.writeString(snapshot);

        assertThat(JSON.writeString(snapshot)).isEqualTo(canonical);
        MetadataSnapshot restored = JSON.read(canonical, MetadataSnapshot.class);
        assertThat(restored).isEqualTo(snapshot);
        assertThat(restored.contentHash()).isEqualTo(snapshot.contentHash());
        assertThat(JSON.writeString(restored)).isEqualTo(canonical);
    }

    @Test
    void canonicalBytesAreIndependentOfDefinitionOrdering() {
        MetadataSnapshot forward = MetadataFixtures.snapshot("metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                java.util.List.of(MetadataFixtures.tierAttribute(), MetadataFixtures.snapshotAttribute("member.city", "Member City")),
                java.util.List.of(), java.util.List.of());
        MetadataSnapshot reversed = MetadataFixtures.snapshot("metadata-17", "1",
                MetadataFixtures.TENANT, MetadataFixtures.PROGRAM,
                java.util.List.of(MetadataFixtures.snapshotAttribute("member.city", "Member City"), MetadataFixtures.tierAttribute()),
                java.util.List.of(), java.util.List.of());

        assertThat(JSON.write(forward)).isEqualTo(JSON.write(reversed));
    }

    @Test
    void rejectsUnknownFields() {
        String canonical = JSON.writeString(MetadataFixtures.standardSnapshot());
        String tampered = canonical.substring(0, canonical.length() - 1) + ",\"unexpected\":1}";

        Throwable thrown = catchThrowable(() -> JSON.read(tampered, MetadataSnapshot.class));
        assertThat(thrown).isInstanceOf(ContractException.class);
        assertThat(((ContractException) thrown).code()).isEqualTo(ContractErrorCodes.CONTRACT_UNKNOWN_FIELD);
    }

    @Test
    void rejectsDuplicateKeys() {
        String canonical = JSON.writeString(MetadataFixtures.standardSnapshot());
        String duplicated = canonical.replace(
                "\"snapshotId\":\"metadata-17\"",
                "\"snapshotId\":\"metadata-17\",\"snapshotId\":\"metadata-17\"");
        assertThat(duplicated).isNotEqualTo(canonical);

        Throwable thrown = catchThrowable(() -> JSON.read(duplicated, MetadataSnapshot.class));
        assertThat(thrown).isInstanceOf(ContractException.class);
        assertThat(((ContractException) thrown).code()).isEqualTo(ContractErrorCodes.JSON_DUPLICATE_KEY);
    }

    @Test
    void rejectsADeclaredHashThatDoesNotMatchTheMaterial() {
        MetadataSnapshot snapshot = MetadataFixtures.standardSnapshot();
        String canonical = JSON.writeString(snapshot);
        String wrongHash = ContentHash.sha256("tampered".getBytes(StandardCharsets.UTF_8)).value();
        String tampered = canonical.replace(snapshot.contentHash().value(), wrongHash);
        assertThat(tampered).isNotEqualTo(canonical);

        ContractAssertions.assertFailsWithCode(
                () -> JSON.read(tampered, MetadataSnapshot.class),
                MetadataErrorCodes.METADATA_CONTENT_HASH_MISMATCH);
    }

    @Test
    void rejectsAnUnsupportedSchemaVersion() {
        String canonical = JSON.writeString(MetadataFixtures.standardSnapshot());
        String tampered = canonical.replace("\"schemaVersion\":\"1.0\"", "\"schemaVersion\":\"1.1\"");
        assertThat(tampered).isNotEqualTo(canonical);

        ContractAssertions.assertFailsWithCode(
                () -> JSON.read(tampered, MetadataSnapshot.class),
                MetadataErrorCodes.METADATA_SCHEMA_VERSION_UNSUPPORTED);
    }
}