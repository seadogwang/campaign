package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

/**
 * Empty-set behavior and (for {@code ZERO}) the value an aggregate yields.
 *
 * <p>{@code ZERO} requires a value; {@code NULL} and {@code UNDEFINED} forbid one. Compatibility of the
 * value with the metric result type is enforced by the owning metric definition.
 */
public record AggregateEmptySet(EmptySetBehavior behavior, MetadataScalarValue value) {

    public AggregateEmptySet {
        behavior = MetadataTexts.requireNonNull(
                behavior, "emptySetBehavior", MetadataErrorCodes.METADATA_VALUE_INVALID);
        if (behavior == EmptySetBehavior.ZERO) {
            if (value == null) {
                throw new MetadataException(
                        MetadataErrorCodes.METADATA_VALUE_INVALID,
                        "A ZERO empty-set behavior requires an explicit value");
            }
        } else if (value != null) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_VALUE_INVALID,
                    "Only a ZERO empty-set behavior may carry a value");
        }
    }

    public static AggregateEmptySet nullValue() {
        return new AggregateEmptySet(EmptySetBehavior.NULL, null);
    }

    public static AggregateEmptySet undefined() {
        return new AggregateEmptySet(EmptySetBehavior.UNDEFINED, null);
    }

    public static AggregateEmptySet zero(SemanticDataType dataType) {
        return new AggregateEmptySet(EmptySetBehavior.ZERO, new MetadataScalarValue(dataType, "0"));
    }
}