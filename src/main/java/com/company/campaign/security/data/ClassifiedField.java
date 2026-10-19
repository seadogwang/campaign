package com.company.campaign.security.data;

import com.company.campaign.shared.ImmutableValues;

import java.util.Objects;

/**
 * A single named field together with its explicit classification.
 *
 * <p>Projection is driven by these descriptors rather than by reflecting over an arbitrary domain object.
 * That keeps the classification decision next to the value instead of guessing it from a bean, and it means
 * a field that was never classified is simply absent rather than silently serialized.
 *
 * <p>The value is deep-frozen on construction, so a payload cannot be mutated after a projection or
 * redaction decision has been made about it.
 */
public record ClassifiedField(String name, DataClassification classification, Object value) {

    public ClassifiedField {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(classification, "classification");
        value = ImmutableValues.freeze(value);
    }

    public static ClassifiedField of(String name, DataClassification classification, Object value) {
        return new ClassifiedField(name, classification, value);
    }
}