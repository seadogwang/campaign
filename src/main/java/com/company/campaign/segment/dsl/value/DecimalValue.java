package com.company.campaign.segment.dsl.value;

/**
 * A bounded plain-decimal lexeme, stored exactly as declared.
 *
 * <p>Exponent notation, {@code NaN}, infinities and locale separators are rejected. The lexical scale is
 * preserved deliberately: normalizing {@code 1000.00} to {@code 1E+3} or {@code 1000} is a T07 semantic
 * decision, so the editable document must round-trip the declared text unchanged.
 */
public record DecimalValue(String value) implements ScalarValue {

    public DecimalValue {
        value = ValueTexts.requireDecimal(value, "value");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.DECIMAL;
    }
}