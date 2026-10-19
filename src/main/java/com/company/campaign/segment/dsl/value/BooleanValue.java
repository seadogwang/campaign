package com.company.campaign.segment.dsl.value;

/**
 * A JSON boolean.
 *
 * <p>Only a real JSON boolean produces this value; the strings {@code "true"}/{@code "false"} and the
 * numbers {@code 1}/{@code 0} are rejected by the codec rather than coerced.
 */
public record BooleanValue(boolean value) implements ScalarValue {

    @Override
    public ValueKind kind() {
        return ValueKind.BOOLEAN;
    }
}