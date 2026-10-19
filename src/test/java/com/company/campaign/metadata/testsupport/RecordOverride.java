package com.company.campaign.metadata.testsupport;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

/**
 * Test-only helper that rebuilds an immutable record with one component replaced.
 *
 * <p>It goes through the record's canonical constructor, so the record's own validation still runs: a
 * replacement that would make the definition invalid fails exactly as production construction would.
 * This keeps hash-coverage tests explicit about which field changed without hand-copying long argument
 * lists.
 */
public final class RecordOverride {

    private RecordOverride() {
    }

    /** Returns a copy of {@code record} with the named component replaced; other components are copied. */
    public static <T extends Record> T with(T record, String component, Object value) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        boolean found = false;
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent candidate = components[index];
            types[index] = candidate.getType();
            if (candidate.getName().equals(component)) {
                arguments[index] = value;
                found = true;
            } else {
                try {
                    arguments[index] = candidate.getAccessor().invoke(record);
                } catch (ReflectiveOperationException exception) {
                    throw new IllegalStateException("Cannot read record component " + candidate.getName(), exception);
                }
            }
        }
        if (!found) {
            throw new IllegalArgumentException("No component named '" + component + "' on " + record.getClass());
        }
        return instantiate(record.getClass(), types, arguments);
    }

    private static <T extends Record> T instantiate(Class<?> type, Class<?>[] types, Object[] arguments) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            return (T) constructor.newInstance(arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            // The record's own validation rejected the value; surface its contract failure unchanged.
            if (exception.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Record construction failed", exception.getCause());
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot rebuild record", exception);
        }
    }

    /** Returns a copy with several components replaced at once, for invariants that tie fields together. */
    public static <T extends Record> T withAll(T record, java.util.Map<String, ?> overrides) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        Object[] arguments = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent candidate = components[index];
            types[index] = candidate.getType();
            try {
                arguments[index] = overrides.containsKey(candidate.getName())
                        ? overrides.get(candidate.getName())
                        : candidate.getAccessor().invoke(record);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Cannot read record component " + candidate.getName(), exception);
            }
        }
        return instantiate(record.getClass(), types, arguments);
    }

    /** The component names of a record, for diagnostics. */
    public static java.util.List<String> componentNames(Class<? extends Record> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}