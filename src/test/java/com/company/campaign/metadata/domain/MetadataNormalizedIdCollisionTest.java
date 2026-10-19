package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataTextNormalizer;
import com.company.campaign.metadata.testsupport.MetadataFixtures;
import com.company.campaign.metadata.testsupport.RecordOverride;
import com.company.campaign.testsupport.ContractAssertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Finding 5: a stable identifier has exactly one normalized identity across the whole snapshot, because
 * search normalizes IDs case-insensitively and two case variants would both be exact-ID matches.
 */
class MetadataNormalizedIdCollisionTest {

    @Test
    void rejectsSameKindIdsThatCollideOnlyAfterNormalization() {
        AttributeDefinition lower = MetadataFixtures.snapshotAttribute("member.tier", "Member Tier");
        AttributeDefinition upper = MetadataFixtures.snapshotAttribute("MEMBER.TIER", "Upper Tier");

        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(List.of(lower, upper), List.of(), List.of()),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rejectsCrossKindIdsThatCollideOnlyAfterNormalization() {
        AttributeDefinition attribute = MetadataFixtures.snapshotAttribute("member.tier", "Member Tier");
        MetricDefinition metric = RecordOverride.withAll(MetadataFixtures.textMinMetric(), java.util.Map.of(
                "id", "MEMBER.TIER", "displayName", "Upper Metric"));

        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(
                        List.of(attribute), List.of(metric), List.of(MetadataFixtures.ordersRelation())),
                MetadataErrorCodes.METADATA_NORMALIZED_COLLISION);
    }

    @Test
    void rawEqualIdsKeepTheDedicatedDuplicateCode() {
        AttributeDefinition first = MetadataFixtures.snapshotAttribute("member.tier", "Member Tier");
        AttributeDefinition second = MetadataFixtures.snapshotAttribute("member.tier", "Other Tier");

        ContractAssertions.assertFailsWithCode(
                () -> MetadataFixtures.snapshot(List.of(first, second), List.of(), List.of()),
                MetadataErrorCodes.METADATA_DUPLICATE_DEFINITION_ID);
    }

    @Test
    void idsThatDifferBeyondNormalizationAreAccepted() {
        assertThat(MetadataTextNormalizer.normalize("member.tier"))
                .isNotEqualTo(MetadataTextNormalizer.normalize("member.tier_2"));
        assertThat(MetadataFixtures.snapshot(List.of(
                MetadataFixtures.snapshotAttribute("member.tier", "Member Tier"),
                MetadataFixtures.snapshotAttribute("member.tier_2", "Second Tier")),
                List.of(), List.of())).isNotNull();
    }

    @Test
    void aDefinitionWhoseDisplayNameEqualsItsOwnIdIsNotAmbiguous() {
        // Ambiguity is between different definitions, never a definition versus itself.
        AttributeDefinition selfNamed = MetadataFixtures.snapshotAttribute("member.city", "member.city");
        assertThat(selfNamed.displayName()).isEqualTo(selfNamed.id());
        assertThat(MetadataFixtures.snapshot(List.of(selfNamed), List.of(), List.of())).isNotNull();
    }
}