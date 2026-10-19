package com.company.campaign.metadata.service;

import com.company.campaign.metadata.MetadataKind;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of a bounded metadata catalog search.
 *
 * <p>Only permitted definitions appear; denied definitions are removed before ordering, so they cannot
 * influence counts, order or the {@link #truncated()} flag.
 */
public record MetadataSearchResult(
        MetadataKind kind,
        String snapshotId,
        int limit,
        boolean truncated,
        List<MetadataSearchMatch> matches
) {
    public MetadataSearchResult {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(snapshotId, "snapshotId");
        matches = matches == null ? List.of() : List.copyOf(matches);
    }
}