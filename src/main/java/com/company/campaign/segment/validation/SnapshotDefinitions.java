package com.company.campaign.segment.validation;

import com.company.campaign.metadata.domain.MetadataDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One frozen lookup over the bound snapshot's definitions by stable ID.
 *
 * <p>A T04 snapshot keeps definition IDs unique across kinds, so a single map is enough to answer both "does
 * this ID exist" and "of which kind is it" — which is exactly what the kind-mismatch rule needs. The map is
 * built once and never mutated; it is populated from the immutable snapshot, not from caller input.
 */
final class SnapshotDefinitions {

    private final MetadataSnapshot snapshot;
    private final Map<String, MetadataDefinition> byId = new LinkedHashMap<>();

    SnapshotDefinitions(MetadataSnapshot snapshot) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        snapshot.attributes().forEach(definition -> byId.put(definition.id(), definition));
        snapshot.metrics().forEach(definition -> byId.put(definition.id(), definition));
        snapshot.relations().forEach(definition -> byId.put(definition.id(), definition));
    }

    MetadataSnapshot snapshot() {
        return snapshot;
    }

    /** The definition with this stable ID, or null when the snapshot has none. */
    MetadataDefinition find(String definitionId) {
        return definitionId == null ? null : byId.get(definitionId);
    }
}