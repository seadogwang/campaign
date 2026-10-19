package com.company.campaign.shared;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defensive deep-freezing of JSON-shaped values held by contracts.
 *
 * <p>Contracts expose {@code Map}/{@code List} components (issue details, Tool arguments). A shallow
 * {@code Map.copyOf} still leaks mutation through nested collections, so values are copied recursively
 * into unmodifiable containers.
 *
 * <p>{@code java.util.Map.copyOf} is deliberately not used: JSON permits null keys and values, which
 * {@code Map.copyOf} rejects.
 */
public final class ImmutableValues {

    private ImmutableValues() {
    }

    /** Returns a deep-frozen copy of a JSON-shaped value; unknown types are returned unchanged. */
    @SuppressWarnings("unchecked")
    public static <T> T freeze(T value) {
        if (value instanceof Map<?, ?> map) {
            return (T) freezeMap((Map<String, Object>) map);
        }
        if (value instanceof List<?> list) {
            return (T) freezeList((List<Object>) list);
        }
        if (value instanceof Set<?> set) {
            return (T) freezeSet((Set<Object>) set);
        }
        if (value instanceof Collection<?> collection) {
            return (T) freezeList(new ArrayList<>((Collection<Object>) collection));
        }
        return value;
    }

    public static <K, V> Map<K, V> freezeMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<K, V> copy = new LinkedHashMap<>(source.size());
        source.forEach((key, value) -> copy.put(key, freeze(value)));
        return Collections.unmodifiableMap(copy);
    }

    public static <E> List<E> freezeList(List<E> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<E> copy = new ArrayList<>(source.size());
        source.forEach(element -> copy.add(freeze(element)));
        return Collections.unmodifiableList(copy);
    }

    public static <E> Set<E> freezeSet(Set<E> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        Set<E> copy = new LinkedHashSet<>(source.size());
        source.forEach(element -> copy.add(freeze(element)));
        return Collections.unmodifiableSet(copy);
    }
}