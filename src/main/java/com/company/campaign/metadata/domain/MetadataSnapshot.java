package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.MetadataSchemaVersions;
import com.company.campaign.metadata.MetadataTexts;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * An immutable, content-hashed, versioned Semantic Metadata snapshot.
 *
 * <p>It is a reproducible resolver input, not a database mirror: it pins Attribute, Metric and Relation
 * semantics plus every referenced entity version, filter, currency, NULL, window, refund and empty-set
 * behavior. Construction canonicalizes definitions into a documented order (by stable ID) before hashing,
 * validates snapshot-wide invariants and rejects a declared {@code contentHash} that is not the canonical
 * hash of the material.
 *
 * <p>Owner {@code tenantId}/{@code programId} and the catalog {@code snapshotVersion} are stored here from
 * the server-side snapshot; they are part of the hash material and are never accepted from caller input
 * by the query service.
 */
public record MetadataSnapshot(
        SchemaVersion schemaVersion,
        String snapshotId,
        String snapshotVersion,
        String tenantId,
        String programId,
        ContentHash contentHash,
        List<AttributeDefinition> attributes,
        List<MetricDefinition> metrics,
        List<RelationDefinition> relations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<EventSelectorDefinition> eventSelectors
) {

    public MetadataSnapshot {
        MetadataSchemaVersions.requireSupported(schemaVersion);
        snapshotId = MetadataTexts.requireId(snapshotId, "snapshotId");
        snapshotVersion = MetadataTexts.requireId(snapshotVersion, "snapshotVersion");
        tenantId = MetadataTexts.requireId(tenantId, "tenantId");
        programId = MetadataTexts.requireId(programId, "programId");
        attributes = canonicalDefinitions(attributes, "attributes");
        metrics = canonicalDefinitions(metrics, "metrics");
        relations = canonicalDefinitions(relations, "relations");
        eventSelectors = canonicalEventSelectors(eventSelectors);
        MetadataSnapshotValidator.validate(attributes, metrics, relations, eventSelectors);

        MetadataSnapshotHashMaterial material = materialFor(
                schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, eventSelectors);
        ContentHash expected = MetadataSnapshotHasher.hash(material);
        if (contentHash == null || !contentHash.equals(expected)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_CONTENT_HASH_MISMATCH,
                    "Declared content hash is not the canonical hash of the snapshot material");
        }
    }

    public MetadataSnapshot(
            SchemaVersion schemaVersion,
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            ContentHash contentHash,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        this(schemaVersion, snapshotId, snapshotVersion, tenantId, programId, contentHash,
                attributes, metrics, relations, List.of());
    }

    /** Publishes a snapshot: computes the canonical hash from the material, then validates and freezes it. */
    public static MetadataSnapshot publish(
            SchemaVersion schemaVersion,
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        return publish(schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, List.of());
    }

    public static MetadataSnapshot publish(
            SchemaVersion schemaVersion,
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations,
            List<EventSelectorDefinition> eventSelectors) {

        MetadataSchemaVersions.requireSupported(schemaVersion);
        String safeSnapshotId = MetadataTexts.requireId(snapshotId, "snapshotId");
        String safeSnapshotVersion = MetadataTexts.requireId(snapshotVersion, "snapshotVersion");
        String safeTenantId = MetadataTexts.requireId(tenantId, "tenantId");
        String safeProgramId = MetadataTexts.requireId(programId, "programId");
        List<AttributeDefinition> canonicalAttributes = canonicalDefinitions(attributes, "attributes");
        List<MetricDefinition> canonicalMetrics = canonicalDefinitions(metrics, "metrics");
        List<RelationDefinition> canonicalRelations = canonicalDefinitions(relations, "relations");
        List<EventSelectorDefinition> canonicalSelectors = canonicalEventSelectors(eventSelectors);
        MetadataSnapshotValidator.validate(
                canonicalAttributes, canonicalMetrics, canonicalRelations, canonicalSelectors);

        ContentHash hash = MetadataSnapshotHasher.hash(materialFor(
                schemaVersion,
                safeSnapshotId,
                safeSnapshotVersion,
                safeTenantId,
                safeProgramId,
                canonicalAttributes,
                canonicalMetrics,
                canonicalRelations,
                canonicalSelectors));
        return new MetadataSnapshot(
                schemaVersion,
                safeSnapshotId,
                safeSnapshotVersion,
                safeTenantId,
                safeProgramId,
                hash,
                canonicalAttributes,
                canonicalMetrics,
                canonicalRelations,
                canonicalSelectors);
    }

    /** The exact material this snapshot's {@link #contentHash()} covers. */
    public MetadataSnapshotHashMaterial hashMaterial() {
        return materialFor(
                schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, eventSelectors);
    }

    /** The definitions of one kind, already in canonical stable-ID order. */
    public List<? extends MetadataDefinition> definitions(MetadataKind kind) {
        return switch (kind) {
            case ATTRIBUTE -> attributes;
            case METRIC -> metrics;
            case RELATION -> relations;
        };
    }

    private static MetadataSnapshotHashMaterial materialFor(
            SchemaVersion schemaVersion,
            String snapshotId,
            String snapshotVersion,
            String tenantId,
            String programId,
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations,
            List<EventSelectorDefinition> eventSelectors) {
        return new MetadataSnapshotHashMaterial(
                schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, eventSelectors);
    }

    private static <T extends MetadataDefinition> List<T> canonicalDefinitions(List<T> definitions, String field) {
        // Documented canonical order: ascending stable definition ID, independent of insertion order.
        return MetadataVocabulary.canonicalDefinitions(definitions, field);
    }

    private static List<EventSelectorDefinition> canonicalEventSelectors(List<EventSelectorDefinition> selectors) {
        if (selectors == null || selectors.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<EventSelectorDefinition> copy = new java.util.ArrayList<>(selectors);
        copy.sort(java.util.Comparator.comparing(EventSelectorDefinition::id));
        return List.copyOf(copy);
    }
}
