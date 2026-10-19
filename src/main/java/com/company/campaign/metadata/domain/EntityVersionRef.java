package com.company.campaign.metadata.domain;

import com.company.campaign.contracts.SchemaVersion;
import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataTexts;

/**
 * A pinned reference to one entity and the schema version its fields were read from.
 *
 * <p>The version is part of the snapshot's hash material, so a definition cannot silently float onto a
 * newer entity schema after publication.
 */
public record EntityVersionRef(SubjectKind entity, SchemaVersion schemaVersion) {

    public EntityVersionRef {
        entity = MetadataTexts.requireNonNull(entity, "entity", MetadataErrorCodes.METADATA_VALUE_INVALID);
        schemaVersion = MetadataTexts.requireNonNull(
                schemaVersion, "schemaVersion", MetadataErrorCodes.METADATA_VALUE_INVALID);
    }
}