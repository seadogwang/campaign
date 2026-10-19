package com.company.campaign.segment.dsl.codec;

import com.company.campaign.segment.dsl.contract.SegmentDslHashMaterial;
import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The single supported way to compute an editable Segment DSL hash.
 *
 * <p>It hashes {@link SegmentDslHashMaterial} only, through the same wire projection the codec serializes, so
 * the hash and the document's canonical bytes can never disagree. There is no overload that accepts a
 * published document, so a caller cannot accidentally hash a value that already contains its own hash.
 *
 * <p>Living beside the codec keeps the wire projection package-private: no mutable serialization map escapes
 * the codec boundary.
 */
public final class SegmentDslHasher {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private SegmentDslHasher() {
    }

    /** Canonical SHA-256 hash of the editable document's hash material. */
    public static ContentHash hash(SegmentDslHashMaterial material) {
        Objects.requireNonNull(material, "material");
        return CANONICAL_JSON.hash(SegmentDslWire.hashMaterial(material));
    }
}