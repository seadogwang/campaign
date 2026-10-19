package com.company.campaign.metadata.domain;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The single supported way to compute a snapshot content hash.
 *
 * <p>It hashes {@link MetadataSnapshotHashMaterial} only. There is no overload that accepts a
 * {@link MetadataSnapshot}, so a caller cannot accidentally hash a value that already contains (and would
 * recursively depend on) its own hash.
 */
public final class MetadataSnapshotHasher {

    /** Immutable and thread-safe, so one shared instance is enough and no mutable singleton is introduced. */
    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private MetadataSnapshotHasher() {
    }

    /** Canonical SHA-256 hash of the snapshot's semantic material. */
    public static ContentHash hash(MetadataSnapshotHashMaterial material) {
        Objects.requireNonNull(material, "material");
        return CANONICAL_JSON.hash(material);
    }
}