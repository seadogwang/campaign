package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataTexts;

/** One semantic Attribute used in a selector's deterministic ordering. */
public record EventSelectorOrder(String attributeId, SortDirection direction) {

    public EventSelectorOrder {
        attributeId = MetadataTexts.requireId(attributeId, "eventSelector.order.attributeId");
        direction = MetadataTexts.requireNonNull(
                direction, "eventSelector.order.direction", MetadataErrorCodes.METADATA_VALUE_INVALID);
    }
}
