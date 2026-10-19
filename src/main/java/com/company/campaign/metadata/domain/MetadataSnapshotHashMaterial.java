package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.SchemaVersion;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * The exact material a {@link MetadataSnapshot} hashes.
 *
 * <p>It mirrors every snapshot field except {@code contentHash}, which is the only thing excluded. Owner
 * fields ({@code tenantId}/{@code programId}) and every version pin are deliberately included: moving
 * identical semantics across an ownership boundary must not preserve identity accidentally.
 *
 * <p>Keeping the material in a separate record is what avoids recursive self-hashing — the hash is
 * computed over this value, never over the snapshot that stores the hash.
 *
 * <p>The record is deeply safe at its own boundary: each definition list is defensively copied, a null
 * element is rejected and the copy is sorted by ascending stable definition ID. A caller that constructs
 * material from an {@code ArrayList} and then mutates it cannot change the material's bytes or hash, and two
 * callers that supply the same definitions in different orders obtain identical material and hashes. Unlike
 * a published snapshot it deliberately does <em>not</em> run cross-definition validation, so it stays usable
 * for per-field hash-mutation tests.
 */
public record MetadataSnapshotHashMaterial(
        SchemaVersion schemaVersion,
        String snapshotId,
        String snapshotVersion,
        String tenantId,
        String programId,
        List<AttributeDefinition> attributes,
        List<MetricDefinition> metrics,
        List<RelationDefinition> relations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<EventSelectorDefinition> eventSelectors
) {

    public MetadataSnapshotHashMaterial {
        attributes = MetadataVocabulary.canonicalDefinitions(attributes, "attributes");
        metrics = MetadataVocabulary.canonicalDefinitions(metrics, "metrics");
        relations = MetadataVocabulary.canonicalDefinitions(relations, "relations");
        if (eventSelectors == null || eventSelectors.isEmpty()) {
            eventSelectors = List.of();
        } else {
            java.util.ArrayList<EventSelectorDefinition> copy = new java.util.ArrayList<>(eventSelectors);
            copy.sort(java.util.Comparator.comparing(EventSelectorDefinition::id));
            eventSelectors = List.copyOf(copy);
        }
    }

    public MetadataSnapshotHashMaterial(
            SchemaVersion schemaVersion,
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        this(schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, List.of());
    }
}
