package com.company.campaign.query.sql;

import java.util.List;

/**
 * External authoring form for one versioned physical storage catalog.
 *
 * <p>{@code audienceMembership} is optional. An existing catalog resource that predates Existing Audience
 * References simply omits the field, binds to {@code null} and goes on compiling every reference-free plan
 * exactly as before; a reference compiled against such a catalog fails closed rather than weakening.
 */
public record StorageCatalogResource(
        String catalogVersion,
        List<StorageCatalog.EntityStorage> entities,
        List<StorageCatalog.AttributeStorage> attributes,
        List<StorageCatalog.MetricStorage> metrics,
        List<StorageCatalog.RelationStorage> relations,
        StorageCatalog.DataScopeStorage dataScope,
        StorageCatalog.AudienceMembershipStorage audienceMembership,
        StorageCatalog.FxStrategy fxStrategy,
        StorageCatalog.RefundStorage refundStorage
) {

    /** Publishes the resource through the storage catalog validation and hashing boundary. */
    public StorageCatalog publish() {
        return StorageCatalog.publish(
                catalogVersion, entities, attributes, metrics, relations, dataScope, audienceMembership,
                fxStrategy, refundStorage);
    }
}
