package com.company.campaign.security.data;

import com.company.campaign.shared.ImmutableValues;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Result of projecting a {@link ClassifiedPayload} to one channel.
 *
 * <p>{@link #values()} is the allowlisted, redacted, deep-frozen projection. {@link #omittedFields()} and
 * {@link #redactedFields()} are metadata about what was withheld or replaced; they name fields only and
 * never carry values.
 */
public record ProjectionResult(
        ProjectionChannel channel,
        Map<String, Object> values,
        Set<String> omittedFields,
        Set<String> redactedFields
) {
    public ProjectionResult {
        Objects.requireNonNull(channel, "channel");
        values = ImmutableValues.freezeMap(values);
        omittedFields = immutableSet(omittedFields);
        redactedFields = immutableSet(redactedFields);
    }

    private static Set<String> immutableSet(Set<String> source) {
        if (source == null || source.isEmpty()) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(source));
    }
}