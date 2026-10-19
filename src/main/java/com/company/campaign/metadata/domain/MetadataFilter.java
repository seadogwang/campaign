package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTextNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable, deterministically ordered filter semantics.
 *
 * <p>Entries are ordered by normalized key and duplicate normalized keys are rejected, so two callers
 * supplying the same logical filter always produce the same canonical bytes.
 */
public record MetadataFilter(List<MetadataFilterEntry> entries) {

    public MetadataFilter {
        if (entries == null || entries.isEmpty()) {
            entries = List.of();
        } else {
            List<MetadataFilterEntry> copy = new ArrayList<>(entries.size());
            Set<String> keys = new LinkedHashSet<>();
            for (MetadataFilterEntry entry : entries) {
                if (entry == null) {
                    throw new MetadataException(
                            MetadataErrorCodes.METADATA_VALUE_INVALID, "A filter entry must not be null");
                }
                String normalizedKey = MetadataTextNormalizer.normalize(entry.key());
                if (!keys.add(normalizedKey)) {
                    throw new MetadataException(
                            MetadataErrorCodes.METADATA_NORMALIZED_COLLISION,
                            "A filter contains a duplicate key after normalization");
                }
                copy.add(entry);
            }
            copy.sort(MetadataFilterEntry::compareTo);
            entries = List.copyOf(copy);
        }
    }

    /** An empty filter. */
    public static MetadataFilter empty() {
        return new MetadataFilter(List.of());
    }
}