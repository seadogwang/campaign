package com.company.campaign.dev.segment;

import com.company.campaign.metadata.domain.SemanticDataType;
import com.company.campaign.query.sql.SqlIdentifier;
import com.company.campaign.query.sql.StorageCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DevelopmentStorageSupplement(
        String catalogVersion,
        Map<String, SemanticDataType> attributeTypeOverrides,
        List<StorageCatalog.EntityStorage> entities,
        List<StorageCatalog.AttributeStorage> attributes,
        List<StorageCatalog.MetricStorage> metrics,
        List<StorageCatalog.RelationStorage> relations,
        Map<String, Map<String, SqlIdentifier>> metricFilterColumnAdditions) {

    StorageCatalog apply(StorageCatalog base) {
        Map<String, StorageCatalog.EntityStorage> mergedEntities = new LinkedHashMap<>();
        base.entities().forEach(value -> mergedEntities.put(entityKey(value), value));
        entities.forEach(value -> mergedEntities.put(entityKey(value), value));

        Map<String, StorageCatalog.AttributeStorage> mergedAttributes = new LinkedHashMap<>();
        for (StorageCatalog.AttributeStorage value : base.attributes()) {
            SemanticDataType override = attributeTypeOverrides.get(value.definitionId());
            mergedAttributes.put(value.definitionId(), override == null ? value : new StorageCatalog.AttributeStorage(
                    value.definitionId(), value.subject(), value.entitySchemaVersion(), override,
                    value.valueColumn(), value.currencyColumn()));
        }
        attributes.forEach(value -> mergedAttributes.put(value.definitionId(), value));

        Map<String, StorageCatalog.MetricStorage> mergedMetrics = new LinkedHashMap<>();
        for (StorageCatalog.MetricStorage value : base.metrics()) {
            Map<String, SqlIdentifier> additions = metricFilterColumnAdditions.get(value.definitionId());
            mergedMetrics.put(value.definitionId(), additions == null ? value : withFilterColumns(value, additions));
        }
        metrics.forEach(value -> mergedMetrics.put(value.definitionId(), value));

        Map<String, StorageCatalog.RelationStorage> mergedRelations = new LinkedHashMap<>();
        base.relations().forEach(value -> mergedRelations.put(value.relationId(), value));
        relations.forEach(value -> mergedRelations.put(value.relationId(), value));

        return StorageCatalog.publish(
                catalogVersion,
                List.copyOf(mergedEntities.values()),
                List.copyOf(mergedAttributes.values()),
                List.copyOf(mergedMetrics.values()),
                List.copyOf(mergedRelations.values()),
                base.dataScope(), base.fxStrategy(), base.refundStorage());
    }

    private static StorageCatalog.MetricStorage withFilterColumns(
            StorageCatalog.MetricStorage value, Map<String, SqlIdentifier> additions) {
        Map<String, SqlIdentifier> merged = new LinkedHashMap<>(value.filterColumns());
        merged.putAll(additions);
        return new StorageCatalog.MetricStorage(
                value.definitionId(), value.subject(), value.entitySchemaVersion(), value.resultType(),
                value.relationId(), value.inputField(), value.inputColumn(), value.currencyField(),
                value.currencyColumn(), value.eventTimeField(), value.eventTimeColumn(), merged,
                value.supportedAggregates(), value.supportedEmptySetBehaviors(), value.refundSupported());
    }

    private static String entityKey(StorageCatalog.EntityStorage value) {
        return value.entityVersion().entity() + ":" + value.entityVersion().schemaVersion().value();
    }
}
