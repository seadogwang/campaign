package com.company.campaign.metadata.domain;

import com.company.campaign.metadata.MetadataErrorCodes;
import com.company.campaign.metadata.MetadataException;
import com.company.campaign.metadata.MetadataTextNormalizer;
import com.company.campaign.metadata.MetadataTexts;

import java.util.List;

/**
 * One immutable filter key and its deterministically ordered values.
 *
 * <p>Values keep their display text but are sorted by normalized key, and duplicate normalized values
 * within the entry are rejected instead of being silently collapsed.
 */
public record MetadataFilterEntry(String key, List<String> values) implements Comparable<MetadataFilterEntry> {

    public MetadataFilterEntry {
        key = MetadataTexts.requireId(key, "filterKey");
        values = MetadataTexts.canonicalTextList(values, "filterValue");
        if (values.isEmpty()) {
            throw new MetadataException(
                    MetadataErrorCodes.METADATA_VALUE_INVALID,
                    "A filter entry must declare at least one value");
        }
    }

    /** Normalized key used for ordering and duplicate detection. */
    public String normalizedKey() {
        return MetadataTextNormalizer.normalize(key);
    }

    @Override
    public int compareTo(MetadataFilterEntry other) {
        return normalizedKey().compareTo(other.normalizedKey());
    }
}