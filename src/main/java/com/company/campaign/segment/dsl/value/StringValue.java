package com.company.campaign.segment.dsl.value;

/**
 * A non-null STRING lexeme.
 *
 * <p>Empty text is deliberately accepted as a meaningful lexeme: it is a real value distinct from the
 * {@code IS_NULL}/{@code IS_NOT_NULL} operators, which carry no value at all. Size is bounded; case,
 * Unicode normalization and collation are Metadata-owned and applied by T07.
 */
public record StringValue(String value) implements ScalarValue {

    public StringValue {
        value = ValueTexts.requireText(value, "value");
    }

    @Override
    public ValueKind kind() {
        return ValueKind.STRING;
    }
}