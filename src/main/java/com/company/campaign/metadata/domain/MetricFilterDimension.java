package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

import java.util.EnumSet;
import java.util.List;

/** An allowlisted Attribute that may constrain rows inside one metric aggregate. */
public record MetricFilterDimension(
        String attributeId,
        List<ComparisonOperator> allowedOperators,
        FilterDomain domain,
        FilterIdentifierKind identifierKind
) implements Comparable<MetricFilterDimension> {

    public MetricFilterDimension {
        attributeId = MetadataTexts.requireId(attributeId, "metricFilterAttributeId");
        if (allowedOperators == null || allowedOperators.isEmpty()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "A metric filter dimension must allow at least one operator");
        }
        EnumSet<ComparisonOperator> canonical = EnumSet.noneOf(ComparisonOperator.class);
        for (ComparisonOperator operator : allowedOperators) {
            if (operator == null || !canonical.add(operator)) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                        "Metric filter dimension operators must be non-null and distinct");
            }
        }
        allowedOperators = List.copyOf(canonical);
        domain = MetadataTexts.requireNonNull(
                domain, "metricFilterDomain", MetadataErrorCodes.METADATA_VALUE_INVALID);
        identifierKind = MetadataTexts.requireNonNull(
                identifierKind, "metricFilterIdentifierKind", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if ((domain == FilterDomain.UNSPECIFIED) != (identifierKind == FilterIdentifierKind.UNSPECIFIED)) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "Metric filter domain and identifier kind must be specified together");
        }
    }

    /** Backward-compatible form for Metadata that does not publish two-level filter semantics. */
    public MetricFilterDimension(String attributeId, List<ComparisonOperator> allowedOperators) {
        this(attributeId, allowedOperators, FilterDomain.UNSPECIFIED, FilterIdentifierKind.UNSPECIFIED);
    }

    @Override
    public int compareTo(MetricFilterDimension other) {
        return attributeId.compareTo(other.attributeId);
    }
}
