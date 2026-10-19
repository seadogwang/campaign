package com.company.campaign.segment.dsl.value;

/**
 * An ISO date-time that carries an explicit numeric UTC offset or {@code Z}.
 *
 * <p>An offset-free local timestamp is rejected outright: accepting one would require the JVM default
 * timezone, which would make the same document mean different instants on different machines. The declared
 * lexeme is preserved, and no normalization to UTC is applied here — that belongs to T07.
 */
public record DateTimeValue(String value) implements ScalarValue {

    public DateTimeValue {
        value = ValueTexts.requireOffsetDateTime(value, "value");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATETIME;
    }
}