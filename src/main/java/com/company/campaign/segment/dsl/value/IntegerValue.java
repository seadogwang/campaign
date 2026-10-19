package com.company.campaign.segment.dsl.value;

/**
 * A signed 64-bit integer.
 *
 * <p>Only a JSON integer can produce this value: the codec rejects a fractional or exponent form such as
 * {@code 3.0} or {@code 3e2} instead of coercing it to an integer. The 64-bit range is explicit, so no
 * wider or implementation-dependent integer type enters the contract.
 */
public record IntegerValue(long value) implements ScalarValue {

    @Override
    public ValueKind kind() {
        return ValueKind.INTEGER;
    }
}