package com.company.campaign.segment.dsl.value;

/**
 * An exact ISO local date, for example {@code 2026-01-01}.
 *
 * <p>The grammar is the strict {@code YYYY-MM-DD} form: shortened components, an offset and an invalid
 * calendar day are all rejected. A local date carries no timezone, so interpreting a boundary from it is a
 * T07 timezone-policy decision, never a silent default applied here.
 */
public record DateValue(String value) implements ScalarValue {

    public DateValue {
        value = ValueTexts.requireLocalDate(value, "value");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DATE;
    }
}