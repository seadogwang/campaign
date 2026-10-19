package com.company.campaign.metadata;

import com.company.campaign.security.authorization.ResourceKind;

/**
 * The closed set of definition kinds a metadata snapshot holds.
 *
 * <p>Each kind maps to exactly one existing {@link ResourceKind} so the T04 service authorizes a definition
 * through the same T02 boundary as every other resource, instead of inventing a parallel permission model.
 */
public enum MetadataKind {

    ATTRIBUTE(ResourceKind.ATTRIBUTE),
    METRIC(ResourceKind.METRIC),
    RELATION(ResourceKind.RELATION);

    private final ResourceKind resourceKind;

    MetadataKind(ResourceKind resourceKind) {
        this.resourceKind = resourceKind;
    }

    /** The T02 resource kind used to authorize a definition of this metadata kind. */
    public ResourceKind resourceKind() {
        return resourceKind;
    }
}