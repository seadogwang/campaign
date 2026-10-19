package com.company.campaign.security.data;

import com.company.campaign.shared.canonical.CanonicalJson;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.Map;

/**
 * Immutable payload size, nesting depth and collection limits.
 *
 * <p>Limits are values rather than constants so a channel can enforce a tighter budget without changing
 * code. Enforcement happens before projection: an oversize payload fails with a stable code instead of
 * being truncated, so a redaction decision never sees a partial tree.
 */
public record ProjectionLimits(int maxPayloadBytes, int maxDepth, int maxCollectionSize) {

    /** A conservative default budget for in-memory projection. */
    public static final ProjectionLimits DEFAULT = new ProjectionLimits(65_536, 16, 1_000);

    private static final CanonicalJson CANONICAL_JSON = new CanonicalJson();

    public ProjectionLimits {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive");
        }
        if (maxCollectionSize <= 0) {
            throw new IllegalArgumentException("maxCollectionSize must be positive");
        }
    }

    /** Enforces every limit against a JSON-shaped value. */
    void enforce(Object value) {
        if (value == null) {
            return;
        }
        int actualBytes = CANONICAL_JSON.write(value).length;
        if (actualBytes > maxPayloadBytes) {
            throw PayloadLimitExceededException.size(actualBytes, maxPayloadBytes);
        }
        enforceDepth(value, 1);
        enforceCollections(value);
    }

    private void enforceDepth(Object value, int depth) {
        if (depth > maxDepth) {
            throw PayloadLimitExceededException.depth(depth, maxDepth);
        }
        if (value instanceof Map<?, ?> map) {
            for (Object element : map.values()) {
                enforceDepth(element, depth + 1);
            }
        } else if (value instanceof Collection<?> collection) {
            for (Object element : collection) {
                enforceDepth(element, depth + 1);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                enforceDepth(Array.get(value, index), depth + 1);
            }
        }
    }

    private void enforceCollections(Object value) {
        if (value instanceof Map<?, ?> map) {
            checkCollectionSize(map.size());
            for (Object element : map.values()) {
                enforceCollections(element);
            }
        } else if (value instanceof Collection<?> collection) {
            checkCollectionSize(collection.size());
            for (Object element : collection) {
                enforceCollections(element);
            }
        } else if (value != null && value.getClass().isArray()) {
            int length = Array.getLength(value);
            checkCollectionSize(length);
            for (int index = 0; index < length; index++) {
                enforceCollections(Array.get(value, index));
            }
        }
    }

    private void checkCollectionSize(int size) {
        if (size > maxCollectionSize) {
            throw PayloadLimitExceededException.collection(size, maxCollectionSize);
        }
    }
}