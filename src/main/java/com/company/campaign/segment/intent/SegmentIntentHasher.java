package com.company.campaign.segment.intent;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;

import java.util.Objects;

/**
 * The single supported way to compute the Intent hash.
 *
 * <p>It hashes the whole Intent through the same wire projection the codec serializes, so the hash and the
 * canonical bytes can never disagree. Every resolution-relevant field is covered; there is no display-only
 * field on the Intent to exclude, and there is no overload that accepts a value already carrying its own
 * hash, so recursive self-hashing is impossible. The same exact Intent always hashes identically.
 */
public final class SegmentIntentHasher {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private SegmentIntentHasher() {
    }

    /** Canonical SHA-256 hash of the Intent's resolution-relevant content. */
    public static ContentHash hash(SegmentIntent intent) {
        Objects.requireNonNull(intent, "intent");
        return CANONICAL_JSON.hash(SegmentIntentWire.root(intent));
    }
}