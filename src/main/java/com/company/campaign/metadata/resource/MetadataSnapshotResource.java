package com.company.campaign.metadata.resource;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.domain.AttributeDefinition;
import com.company.campaign.metadata.domain.EventSelectorDefinition;
import com.company.campaign.metadata.domain.MetadataSnapshot;
import com.company.campaign.metadata.domain.MetricDefinition;
import com.company.campaign.metadata.domain.RelationDefinition;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** External authoring form for one versioned Metadata snapshot. */
public record MetadataSnapshotResource(
        SchemaVersion schemaVersion,
        String snapshotId,
        String snapshotVersion,
        String tenantId,
        String programId,
        List<AttributeDefinition> attributes,
        List<MetricDefinition> metrics,
        List<RelationDefinition> relations,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<EventSelectorDefinition> eventSelectors
) {

    /** Publishes the resource through the same canonical validation and hashing boundary as any snapshot. */
    public MetadataSnapshot publish() {
        return MetadataSnapshot.publish(
                schemaVersion, snapshotId, snapshotVersion, tenantId, programId,
                attributes, metrics, relations, eventSelectors);
    }
}
