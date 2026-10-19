package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTexts;

/**
 * Optional event-time capability of a relation.
 *
 * <p>A capability present on the relation must name a real event-time field and must not declare
 * {@link EventTimeSemantics#NOT_APPLICABLE}; the capability simply being absent is the way to say a
 * relation has no event time.
 */
public record RelationEventTimeCapability(EventTimeSemantics semantics, String eventTimeField) {

    public RelationEventTimeCapability {
        semantics = MetadataTexts.requireNonNull(
                semantics, "relationEventTimeSemantics", MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID);
        if (semantics == EventTimeSemantics.NOT_APPLICABLE) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_SEMANTIC_COMBINATION_INVALID,
                    "A relation event-time capability must declare an event-time semantics");
        }
        eventTimeField = MetadataTexts.requireId(eventTimeField, "relationEventTimeField");
    }
}