package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Relation-specific semantics for locating one governed event. */
public record EventSelectorBinding(
        String relationId,
        String identityAttributeId,
        List<EventSelectorOrder> orderBy,
        MetadataFilter fixedFilter,
        List<MetricFilterDimension> scopeFilterDimensions) {

    public EventSelectorBinding {
        relationId = MetadataTexts.requireId(relationId, "eventSelector.relationId");
        identityAttributeId = MetadataTexts.requireId(identityAttributeId, "eventSelector.identityAttributeId");
        if (orderBy == null || orderBy.isEmpty()) {
            throw new MetadataException(MetadataErrorCodes.METADATA_VALUE_INVALID,
                    "An event selector binding requires deterministic ordering");
        }
        List<EventSelectorOrder> orders = new ArrayList<>(orderBy.size());
        Set<String> orderedAttributes = new HashSet<>();
        for (EventSelectorOrder order : orderBy) {
            if (order == null || !orderedAttributes.add(order.attributeId())) {
                throw new MetadataException(MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Event selector ordering attributes must be non-null and unique");
            }
            orders.add(order);
        }
        orderBy = List.copyOf(orders);
        fixedFilter = MetadataTexts.requireNonNull(
                fixedFilter, "eventSelector.fixedFilter", MetadataErrorCodes.METADATA_VALUE_INVALID);
        List<MetricFilterDimension> dimensions = scopeFilterDimensions == null
                ? new ArrayList<>()
                : new ArrayList<>(scopeFilterDimensions);
        Set<String> dimensionIds = new HashSet<>();
        for (MetricFilterDimension dimension : dimensions) {
            if (dimension == null || !dimensionIds.add(dimension.attributeId())) {
                throw new MetadataException(MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Event selector scope dimensions must be non-null and unique");
            }
        }
        dimensions.sort(MetricFilterDimension::compareTo);
        scopeFilterDimensions = List.copyOf(dimensions);
    }
}
