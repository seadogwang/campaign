package com.company.campaign.metadata.service;

import com.company.campaign.metadata.domain.MetadataDefinition;

import java.util.Objects;

/**
 * One permitted definition discovered by search, with the tier it matched at.
 *
 * <p>The tier is lexical discovery metadata only; there is deliberately no score, evidence list or
 * accepted candidate here.
 */
public record MetadataSearchMatch(MetadataDefinition definition, MetadataSearchTier tier) {

    public MetadataSearchMatch {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(tier, "tier");
    }
}