package com.company.campaign.segment.canonical;

import com.company.campaign.metadata.MetadataKind;
import com.company.campaign.metadata.domain.ComparisonOperator;

/** One canonical Attribute comparison applied to rows before a Metric aggregate. */
public record CanonicalMetricFilter(
        CanonicalDefinition definition,
        ComparisonOperator operator,
        CanonicalValue value
) implements Comparable<CanonicalMetricFilter> {

    public CanonicalMetricFilter {
        CanonicalInvariants.requirePresent(definition, "metricFilterDefinition");
        CanonicalInvariants.requirePresent(operator, "metricFilterOperator");
        if (definition.kind() != MetadataKind.ATTRIBUTE) {
            throw CanonicalInvariants.failure(
                    CanonicalErrorCodes.CANONICAL_SCOPE_INVALID,
                    "metricFilterDefinition",
                    "A canonical Metric filter must reference an Attribute definition.");
        }
        CanonicalInvariants.requireOperatorValue(operator, value);
        if (value != null) {
            CanonicalInvariants.requireValueKind(value, definition.dataType(), "metricFilterOperand");
        }
    }

    @Override
    public int compareTo(CanonicalMetricFilter other) {
        int comparison = definition.id().compareTo(other.definition.id());
        if (comparison != 0) {
            return comparison;
        }
        comparison = operator.compareTo(other.operator);
        if (comparison != 0) {
            return comparison;
        }
        return valueText(value).compareTo(valueText(other.value));
    }

    private static String valueText(CanonicalValue value) {
        return value == null ? "" : new CanonicalSegmentCodec().writeValueString(value);
    }
}
