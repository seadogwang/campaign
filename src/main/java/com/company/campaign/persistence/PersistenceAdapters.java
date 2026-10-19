package com.company.campaign.persistence;

import com.company.campaign.shared.canonical.CanonicalJson;
import com.company.campaign.shared.canonical.ContentHash;
import com.fasterxml.jackson.core.type.TypeReference;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * Centralized JSON, string, enum and time conversions used by mapper-backed persistence services.
 *
 * <p>Every method is null-safe: a null input always produces a null output, so callers never need to
 * guard against a null argument. The private {@link CanonicalJson} instance is shared across all
 * serialization and deserialization paths to guarantee a single canonical representation.
 *
 * <p>This is a pure utility class; it holds no mutable state and cannot be instantiated.
 */
public final class PersistenceAdapters {

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    private PersistenceAdapters() {
    }

    /**
     * Converts a domain {@link Instant} to a nullable {@link Timestamp} for JDBC parameter binding.
     *
     * @param instant the domain instant, may be null
     * @return the JDBC timestamp, or null when the input is null
     */
    public static Timestamp instantToTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.from(instant);
    }

    /**
     * Converts a JDBC {@link Timestamp} to a domain {@link Instant} for result-set mapping.
     *
     * @param timestamp the JDBC timestamp, may be null
     * @return the domain instant, or null when the input is null
     */
    public static Instant timestampToInstant(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    /**
     * Extracts the raw string value from a {@link ContentHash} for storage.
     *
     * @param contentHash the content hash, may be null
     * @return the raw hash string, or null when the input is null
     */
    public static String contentHashValue(ContentHash contentHash) {
        if (contentHash == null) {
            return null;
        }
        return contentHash.value();
    }

    /**
     * Wraps a raw hash string in a {@link ContentHash}.
     *
     * @param value the raw hash string, may be null
     * @return a new content hash, or null when the input is null
     * @throws com.company.campaign.shared.canonical.ContentHashException when the value is non-null but invalid
     */
    public static ContentHash contentHashFromString(String value) {
        if (value == null) {
            return null;
        }
        return new ContentHash(value);
    }

    /**
     * Serializes a value to canonical JSON text using the shared {@link CanonicalJson} instance.
     *
     * @param value the value to serialize, must not be null
     * @return canonical JSON text
     */
    public static String canonicalJsonWrite(Object value) {
        Objects.requireNonNull(value, "value");
        return CANONICAL_JSON.writeString(value);
    }

    /**
     * Deserializes canonical JSON text back to a typed contract using the shared {@link CanonicalJson}
     * instance.
     *
     * @param json the canonical JSON text, must not be null
     * @param type the generic type reference describing the target contract, must not be null
     * @param <T>  the target contract type
     * @return the deserialized contract value
     */
    public static <T> T canonicalJsonRead(String json, TypeReference<T> type) {
        Objects.requireNonNull(json, "json");
        Objects.requireNonNull(type, "type");
        return CANONICAL_JSON.read(json, type);
    }
}