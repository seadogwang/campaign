package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Snapshot-wide invariants that no single definition can check on its own: ID uniqueness across kinds both
 * raw and normalized, per-kind normalized alias/name collisions and metric-to-relation compatibility.
 */
final class MetadataSnapshotValidator {

    private MetadataSnapshotValidator() {
    }

    static void validate(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations,
            List<EventSelectorDefinition> eventSelectors) {

        Set<String> ids = new LinkedHashSet<>();
        for (AttributeDefinition attribute : attributes) {
            requireUniqueId(ids, attribute.id());
        }
        for (MetricDefinition metric : metrics) {
            requireUniqueId(ids, metric.id());
        }
        for (RelationDefinition relation : relations) {
            requireUniqueId(ids, relation.id());
        }
        for (EventSelectorDefinition selector : eventSelectors) {
            requireUniqueId(ids, selector.id());
        }

        // Search normalizes IDs case-insensitively, so a snapshot must not hold two IDs that share one
        // normalized identity: otherwise both would be an exact-ID match and discovery would be ambiguous.
        requireUniqueNormalizedIds(attributes, metrics, relations);

        requireDistinctKeys(attributeKeys(attributes), "attribute");
        requireDistinctKeys(metricKeys(metrics), "metric");
        requireDistinctKeys(relationKeys(relations), "relation");

        Map<String, RelationDefinition> relationsById = new LinkedHashMap<>();
        relations.forEach(relation -> relationsById.put(relation.id(), relation));
        Map<String, AttributeDefinition> attributesById = new LinkedHashMap<>();
        attributes.forEach(attribute -> attributesById.put(attribute.id(), attribute));
        for (MetricDefinition metric : metrics) {
            RelationDefinition relation = relationsById.get(metric.relationId());
            if (relation == null) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_RELATION_REFERENCE_UNKNOWN,
                        "Metric references a relation that is not part of the snapshot");
            }
            if (relation.sourceEntity() != metric.subject()) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE,
                        "Metric subject is incompatible with the referenced relation source");
            }
            requireEventTimeCompatibility(metric, relation);
            requireScopeFilterCompatibility(metric, relation, attributesById);
        }
        for (EventSelectorDefinition selector : eventSelectors) {
            requireEventSelectorCompatibility(selector, relationsById, attributesById);
        }
    }

    private static void requireEventSelectorCompatibility(
            EventSelectorDefinition selector,
            Map<String, RelationDefinition> relationsById,
            Map<String, AttributeDefinition> attributesById) {
        for (EventSelectorBinding binding : selector.bindings()) {
            RelationDefinition relation = relationsById.get(binding.relationId());
            if (relation == null || relation.sourceEntity() != selector.subject()) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE,
                        "Event selector references an incompatible relation");
            }
            requireTargetAttribute(binding.identityAttributeId(), relation, attributesById);
            for (EventSelectorOrder order : binding.orderBy()) {
                requireTargetAttribute(order.attributeId(), relation, attributesById);
            }
            for (MetadataFilterEntry entry : binding.fixedFilter().entries()) {
                requireTargetAttribute(entry.key(), relation, attributesById);
            }
            for (MetricFilterDimension dimension : binding.scopeFilterDimensions()) {
                AttributeDefinition attribute = requireTargetAttribute(
                        dimension.attributeId(), relation, attributesById);
                if (!attribute.allowedOperators().containsAll(dimension.allowedOperators())) {
                    throw new MetadataException(
                            MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                            "Event selector scope dimension allows a forbidden operator");
                }
            }
        }
    }

    private static AttributeDefinition requireTargetAttribute(
            String attributeId,
            RelationDefinition relation,
            Map<String, AttributeDefinition> attributesById) {
        AttributeDefinition attribute = attributesById.get(attributeId);
        if (attribute == null
                || attribute.subject() != relation.targetEntity()
                || !attribute.entityVersion().equals(relation.targetVersion())) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE,
                    "Event selector Attribute is incompatible with its relation target");
        }
        return attribute;
    }

    private static void requireScopeFilterCompatibility(
            MetricDefinition metric,
            RelationDefinition relation,
            Map<String, AttributeDefinition> attributesById) {
        for (MetricFilterDimension dimension : metric.scopeFilterDimensions()) {
            AttributeDefinition attribute = attributesById.get(dimension.attributeId());
            if (attribute == null) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_RELATION_REFERENCE_UNKNOWN,
                        "Metric filter dimension references an Attribute outside the snapshot");
            }
            if (attribute.subject() != relation.targetEntity()
                    || !attribute.entityVersion().equals(relation.targetVersion())) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE,
                        "Metric filter dimension is incompatible with the relation target entity version");
            }
            if (!attribute.allowedOperators().containsAll(dimension.allowedOperators())) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Metric filter dimension allows an operator forbidden by its Attribute");
            }
        }
    }

    private static void requireUniqueId(Set<String> ids, String id) {
        if (!ids.add(id)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_DUPLICATE_DEFINITION_ID,
                    "Snapshot definition IDs must be unique across every kind");
        }
    }

    private static void requireUniqueNormalizedIds(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        Set<String> normalized = new LinkedHashSet<>();
        for (MetadataDefinition definition : allDefinitions(attributes, metrics, relations)) {
            if (!normalized.add(MetadataTextNormalizer.normalize(definition.id()))) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                        "Snapshot definition IDs must be unique after normalization");
            }
        }
    }

    /**
     * A metric that uses a bounded window, or that pins an event-time field, must reference a relation whose
     * event-time capability exists and agrees exactly on field and semantics. A {@code LIFETIME}-only metric
     * that omits event time structurally carries no such requirement.
     */
    private static void requireEventTimeCompatibility(MetricDefinition metric, RelationDefinition relation) {
        boolean boundedWindow = metric.supportedWindows().stream().anyMatch(TimeWindowKind::isBounded);
        if (!boundedWindow && metric.eventTimeField() == null) {
            return;
        }
        RelationEventTimeCapability capability = relation.eventTimeCapability();
        if (capability == null
                || !capability.eventTimeField().equals(metric.eventTimeField())
                || capability.semantics() != metric.eventTimeSemantics()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_RELATION_INCOMPATIBLE,
                    "Metric event-time semantics are incompatible with the referenced relation");
        }
    }

    private static List<MetadataDefinition> allDefinitions(
            List<AttributeDefinition> attributes,
            List<MetricDefinition> metrics,
            List<RelationDefinition> relations) {
        List<MetadataDefinition> all =
                new ArrayList<>(attributes.size() + metrics.size() + relations.size());
        all.addAll(attributes);
        all.addAll(metrics);
        all.addAll(relations);
        return all;
    }

    private static void requireDistinctKeys(List<String> keys, String kind) {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : keys) {
            if (!seen.add(key)) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                        "Duplicate normalized identifier or alias in definition kind '" + kind + "'");
            }
        }
    }

    private static List<String> attributeKeys(List<AttributeDefinition> definitions) {
        List<String> keys = new ArrayList<>();
        definitions.forEach(definition -> keys.addAll(definition.discoverableKeys()));
        return keys;
    }

    private static List<String> metricKeys(List<MetricDefinition> definitions) {
        List<String> keys = new ArrayList<>();
        definitions.forEach(definition -> keys.addAll(definition.discoverableKeys()));
        return keys;
    }

    private static List<String> relationKeys(List<RelationDefinition> definitions) {
        List<String> keys = new ArrayList<>();
        definitions.forEach(definition -> keys.addAll(definition.discoverableKeys()));
        return keys;
    }
}
